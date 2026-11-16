// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Repository observations (M3-SCALE v3.0 sections 6.3 and 15). Atom: one repository root. Pattern: gateway; it is
 * the only owner of git invocations, and it only reads: HEAD, ignore rules, working-tree changes and tracked files.
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
        out.addAll(Arrays.asList(run("ls-files", "-z", "--others", "--exclude-standard").split("\0")));
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
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        return process;
    }

    private static byte[] drain(Process process) throws IOException {
        try (InputStream in = process.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
