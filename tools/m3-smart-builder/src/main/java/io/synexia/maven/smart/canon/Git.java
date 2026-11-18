// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Repository observations (M3-SCALE v3.0 sections 6.3 and 15). Atom: one repository root. Pattern: gateway; it is
 * the only owner of git invocations, and it only reads: HEAD, ignore rules, working-tree changes,
 * tracked files and the blobs of a tree.
 */
final class Git {
    private final Path root;

    Git(Path root) {
        this.root = root;
    }

    String head() throws IOException, InterruptedException {
        return run("rev-parse", "HEAD").strip();
    }

    String commit(String ref) throws IOException, InterruptedException {
        return run("rev-parse", "--verify", ref + "^{commit}").strip();
    }

    /** Paths whose working-tree content differs from {@code base}, plus untracked files, sorted. */
    Set<String> delta(String base) throws IOException, InterruptedException {
        TreeSet<String> out = new TreeSet<>(Arrays.asList(run("diff", "--name-only", "--no-renames", "-z", base).split("\0")));
        out.addAll(untracked());
        out.remove("");
        return out;
    }

    /** Untracked files that no ignore rule covers, sorted. */
    Set<String> untracked() throws IOException, InterruptedException {
        return names(run("ls-files", "-z", "--others", "--exclude-standard"));
    }

    /** Every file path of the tree of {@code base}, sorted. */
    TreeSet<String> tree(String base) throws IOException, InterruptedException {
        return names(run("ls-tree", "-r", "--name-only", "-z", base));
    }

    /** Tracked files matching {@code pathspec} whose content changed since {@code base}; added, deleted and renamed files are not listed. */
    Set<String> modified(String base, String pathspec) throws IOException, InterruptedException {
        return names(run("diff", "--name-only", "--diff-filter=M", "--no-renames", "-z", base, "--", pathspec));
    }

    /** Content of {@code path} in the tree of {@code base}. */
    String show(String base, String path) throws IOException, InterruptedException {
        return run("show", base + ":" + path);
    }

    /** Receives one blob of {@link #blobs}. */
    interface BlobReader {
        void read(String path, byte[] content) throws IOException;
    }

    /**
     * Hands the content of each path in the tree of {@code rev} to {@code reader}, in order,
     * through one {@code git cat-file --batch} process. A path that is not a blob there, or a
     * blob that is not local (lazy fetching is turned off), fails the read.
     */
    void blobs(String rev, Collection<String> paths, BlobReader reader)
            throws IOException, InterruptedException {
        ProcessBuilder batch = builder(List.of("cat-file", "--batch"));
        batch.environment().put("GIT_NO_LAZY_FETCH", "1");
        Process process = batch.start();
        try (OutputStream ask = process.getOutputStream();
                InputStream answer = new BufferedInputStream(process.getInputStream())) {
            for (String path : paths) {
                ask.write((rev + ":" + path + "\n").getBytes(StandardCharsets.UTF_8));
                ask.flush();
                String[] head = header(answer).split(" ", -1);
                if (head.length < 3 || !head[1].equals("blob")) {
                    throw new IOException("not a local blob at " + rev + ": " + path);
                }
                byte[] content = answer.readNBytes(Integer.parseInt(head[2]));
                if (answer.read() != '\n') throw new IOException("git cat-file output truncated");
                reader.read(path, content);
            }
        }
        if (process.waitFor() != 0) throw new IOException("git cat-file --batch failed");
    }

    private static String header(InputStream answer) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int ch = answer.read(); ch != '\n'; ch = answer.read()) {
            if (ch < 0) throw new IOException("git cat-file ended early");
            line.append((char) ch);
        }
        return line.toString();
    }

    /** The patch from {@code base} to the working tree with no context lines and renames paired; untracked files are not in it. */
    String patch(String base) throws IOException, InterruptedException {
        return run("-c", "core.quotePath=false", "diff", "--unified=0", "--no-color", "-M", base);
    }

    /** Number of lines of tracked and untracked text files that contain {@code word} as a whole word. */
    int lines(String word) throws IOException, InterruptedException {
        Process process = start("grep", "--untracked", "-I", "-w", "-F", "-c", "-e", word);
        String counted = new String(drain(process), StandardCharsets.UTF_8);
        if (process.waitFor() > 1) throw new IOException("git grep failed for " + word);
        return counted.lines().filter(row -> row.contains(":"))
                .mapToInt(row -> Integer.parseInt(row.substring(row.lastIndexOf(':') + 1))).sum();
    }

    private static TreeSet<String> names(String zeroSeparated) {
        TreeSet<String> out = new TreeSet<>(Arrays.asList(zeroSeparated.split("\0")));
        out.remove("");
        return out;
    }

    boolean ignored(String relative) throws IOException, InterruptedException {
        Process process = start("check-ignore", "-q", "--", relative);
        drain(process);
        return process.waitFor() == 0;
    }

    /** Every path git reports as changed or untracked, with its current content hash. */
    Map<String, String> dirty() throws IOException, InterruptedException {
        String[] tokens = run("status", "--porcelain=v1", "-z", "--untracked-files=all").split("\0");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].length() < 4) continue;
            paths.add(tokens[i].substring(3));
            String code = tokens[i].substring(0, 2);
            if ((code.indexOf('R') >= 0 || code.indexOf('C') >= 0) && i + 1 < tokens.length) paths.add(tokens[++i]);
        }
        return Ledger.hashes(root, paths);
    }

    /** Tracked files under the given literal paths, sorted; {@code .} names the whole repository. */
    List<String> files(List<String> files, List<String> dirs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("ls-files", "-z", "--"));
        files.forEach(p -> command.add(":(literal)" + p));
        dirs.forEach(p -> command.add(p.equals(".") ? "." : ":(literal)" + p));
        return new TreeSet<>(Arrays.asList(run(command.toArray(String[]::new)).split("\0"))).stream()
                .filter(p -> !p.isEmpty()).collect(Collectors.toList());
    }

    private String run(String... args) throws IOException, InterruptedException {
        Process process = start(args);
        byte[] out = drain(process);
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("git " + String.join(" ", args) + " failed with exit " + exit);
        return new String(out, StandardCharsets.UTF_8);
    }

    private Process start(String... args) throws IOException {
        Process process = builder(Arrays.asList(args)).start();
        process.getOutputStream().close();
        return process;
    }

    private ProcessBuilder builder(List<String> args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(args);
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    private static byte[] drain(Process process) throws IOException {
        try (InputStream in = process.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
