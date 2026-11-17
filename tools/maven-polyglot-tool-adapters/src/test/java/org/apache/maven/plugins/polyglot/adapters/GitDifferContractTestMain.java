/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.polyglot.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The git differ process ({@code GitDiffMain}) with the real git program: trees mode tells "differences" from git's
 * errors (both exit 1) by standard error and output validity and checks both trees first; repository mode includes
 * untracked files without touching the real index; user configuration and environment do not change the bytes.
 * Prints NOT RUN when git is not on PATH.
 */
public final class GitDifferContractTestMain {
    static final String GIT = System.getProperty("m3.git", "git");
    private static int passed;

    private GitDifferContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        if (!gitAvailable()) {
            System.out.println("NOT RUN: git differ contract cases (git not on PATH)");
            return;
        }
        Path ws = Files.createTempDirectory("git-differ-").toRealPath();
        try {
            cases(ws);
            System.out.println("PASS: " + passed + " git differ contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void cases(Path ws) throws Exception {
        treesFailuresAreNotDifferences(ws);
        treesDifferencesAndEquality(ws);
        repositoryModeIsPinnedAndComplete(ws);
        environmentIsPinned();
    }

    private static void treesFailuresAreNotDifferences(Path ws) throws Exception {
        write(ws.resolve("t/before/f.txt"), "one\r\ntwo\r\n");
        write(ws.resolve("t/after/f.txt"), "one\r\nTWO\r\n");
        write(ws.resolve("t/file.txt"), "x\n");
        Path out = ws.resolve("t/out/f.patch");
        require(trees(ws, "before", "aftr", out) == GitDiffMain.FAILED && !Files.exists(out),
                "a misspelled tree fails and leaves no patch that reads as 'no differences'");
        require(trees(ws, "befor", "after", out) == GitDiffMain.FAILED && !Files.exists(out), "a missing before tree");
        require(trees(ws, "before", "file.txt", out) == GitDiffMain.FAILED && !Files.exists(out),
                "a folder against a file is refused");
        gitErrorIsNotADifference(ws);
        unreadableFileFails(ws, out);
        passed++;
    }

    /** git 2.53 for a tree it cannot read: exit 1, an error line, an empty --output file. */
    private static void gitErrorIsNotADifference(Path ws) throws IOException {
        Path empty = write(ws.resolve("t/empty.patch"), "");
        Path patch = write(ws.resolve("t/real.patch"), "diff --git a/f b/f\n");
        PinnedGit.Result error = new PinnedGit.Result(1, new byte[0], "error: Could not access 'before/aftr'\n");
        PinnedGit.Result differences = new PinnedGit.Result(1, new byte[0], "");
        require(!GitDiffMain.treesSucceeded(error, empty) && !GitDiffMain.treesSucceeded(differences, empty)
                && !GitDiffMain.treesSucceeded(error, patch) && GitDiffMain.treesSucceeded(differences, patch)
                && GitDiffMain.treesSucceeded(new PinnedGit.Result(0, new byte[0], "warning: x\n"), empty),
                "exit 1 is 'differences' only with a patch and no error line");
    }

    /** A file git cannot open after the trees exist: denied with icacls on Windows, mode 000 elsewhere. */
    private static void unreadableFileFails(Path ws, Path out) throws Exception {
        Path locked = write(ws.resolve("t/after/locked.txt"), "secret\n");
        if (!deny(locked)) {
            System.out.println("NOT RUN: unreadable-file git error case (cannot remove read access here)");
            return;
        }
        try {
            require(trees(ws, "before", "after", out) == GitDiffMain.FAILED && !Files.exists(out),
                    "a git error on an unreadable file is a failure, never a patch");
        } finally {
            allow(locked);
            Files.delete(locked);
        }
    }

    private static boolean deny(Path file) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return icacls(file, "/deny", "*S-1-1-0:(R)") && !Files.isReadable(file);
        }
        Files.setPosixFilePermissions(file, java.util.Set.of());
        return !Files.isReadable(file);
    }

    private static void allow(Path file) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            icacls(file, "/reset");
        } else {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static boolean icacls(Path file, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("icacls", file.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static void treesDifferencesAndEquality(Path ws) throws Exception {
        Path out = ws.resolve("t/out/f.patch");
        require(trees(ws, "before", "after", out) == 0, "differences are a success");
        byte[] patch = Files.readAllBytes(out);
        require(new String(patch, StandardCharsets.UTF_8).startsWith("diff --git ")
                && new String(patch, StandardCharsets.UTF_8).contains("-two\r\n+TWO\r\n"), "CRLF kept byte for byte");
        require(trees(ws, "before", "before", out) == 0 && Files.size(out) == 0, "equal trees: empty patch, exit 0");
        passed++;
    }

    private static int trees(Path ws, String before, String after, Path out) throws Exception {
        Path dir = ws.resolve("t");
        return child(dir, Map.of(), "trees", GIT, before, after, out.toString()).exit;
    }

    /** One work tree diffed twice: with no user configuration and with a hostile one; the bytes must be equal. */
    private static void repositoryModeIsPinnedAndComplete(Path ws) throws Exception {
        Path repo = repository(ws.resolve("r"));
        Path empty = write(ws.resolve("cfg/empty"), "");
        Map<String, String> clean = Map.of("GIT_CONFIG_GLOBAL", empty.toString(), "GIT_CONFIG_NOSYSTEM", "1");
        Child plain = child(repo, clean, "repo", GIT, repo.toString(), "HEAD", ws.resolve("plain.patch").toString());
        Child hostile = child(repo, hostile(ws), "repo", GIT, repo.toString(), "HEAD",
                ws.resolve("hostile.patch").toString());
        byte[] a = Files.readAllBytes(ws.resolve("plain.patch"));
        byte[] b = Files.readAllBytes(ws.resolve("hostile.patch"));
        require(plain.exit == 0 && hostile.exit == 0 && Arrays.equals(a, b), "user configuration and environment do"
                + " not change the bytes:\n" + new String(a, StandardCharsets.UTF_8) + "\n---\n" + new String(b));
        completeAndUntouched(repo, new String(a, StandardCharsets.UTF_8));
        passed++;
    }

    private static void completeAndUntouched(Path repo, String patch) throws Exception {
        require(patch.contains("diff --git a/added.txt b/added.txt\nnew file mode") && patch.contains("+new\r\n")
                && patch.contains("-two\r\n+TWO\r\n") && patch.contains(" a\n \n b\n"), "untracked, CRLF, blank context");
        require(status(repo).contains("?? added.txt"), "the real index is untouched: " + status(repo));
    }

    private static Path repository(Path repo) throws Exception {
        write(repo.resolve("crlf.txt"), "one\r\ntwo\r\n");
        write(repo.resolve("blank.txt"), "a\n\nb\nc\n");
        git(repo, "init", "--quiet");
        git(repo, "add", "crlf.txt", "blank.txt");
        git(repo, "-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "--quiet", "-m", "before");
        write(repo.resolve("crlf.txt"), "one\r\nTWO\r\n");
        write(repo.resolve("blank.txt"), "a\n\nb\nC\n");
        write(repo.resolve("added.txt"), "new\r\n");
        return repo;
    }

    /** A user configuration that changes diff bytes, plus environment variables git reads. */
    private static Map<String, String> hostile(Path ws) throws IOException {
        Path attributes = write(ws.resolve("cfg/attributes"), "* text=auto eol=lf\n");
        Path order = write(ws.resolve("cfg/order"), "crlf.txt\nadded.txt\n");
        Path config = write(ws.resolve("cfg/user"), "[core]\n\tautocrlf = true\n\tquotePath = false\n\tabbrev = 12\n"
                + "\tattributesFile = " + attributes.toString().replace('\\', '/') + "\n[diff]\n\tsuppressBlankEmpty = true\n"
                + "\tnoprefix = true\n\tmnemonicPrefix = true\n\talgorithm = patience\n\tcontext = 1\n\tindentHeuristic = false\n"
                + "\torderFile = " + order.toString().replace('\\', '/') + "\n\trenames = copies\n");
        Map<String, String> env = new HashMap<>(Map.of("GIT_CONFIG_GLOBAL", config.toString(), "GIT_DIFF_OPTS",
                "--unified=1", "GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "diff.srcPrefix", "GIT_CONFIG_VALUE_0", "x/"));
        env.put("LC_ALL", "de_DE.UTF-8");
        return env;
    }

    /** System and global configuration are replaced (no system file, an empty global file); lazy fetches are off. */
    private static void environmentIsPinned() {
        Map<String, String> env = new HashMap<>(Map.of("GIT_DIFF_OPTS", "-u1", "GIT_EXTERNAL_DIFF", "x",
                "GIT_CONFIG_PARAMETERS", "'diff.noprefix'='true'", "GIT_INDEX_FILE", "i", "GIT_CONFIG_KEY_0", "k",
                "GIT_DIR", "d", "PATH", "p", "GIT_CONFIG_GLOBAL", "g", "GIT_CONFIG_SYSTEM", "s"));
        Path empty = Path.of("scratch", "empty");
        PinnedGit.pin(env, empty);
        require(env.keySet().equals(java.util.Set.of("PATH", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM",
                "GIT_NO_LAZY_FETCH", "GIT_ATTR_NOSYSTEM", "LC_ALL", "GIT_TERMINAL_PROMPT")) && "C".equals(env.get("LC_ALL"))
                && empty.toString().equals(env.get("GIT_CONFIG_GLOBAL")) && "1".equals(env.get("GIT_CONFIG_NOSYSTEM")),
                "pinned environment: " + env);
        passed++;
    }

    record Child(int exit, String output) { }

    /** GitDiffMain in its own JVM, as the engine runs it, with extra environment variables. */
    static Child child(Path dir, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(ToolArgs.java(), "-cp", ToolArgs.home(GitDiffMain.class),
                GitDiffMain.class.getName()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        System.out.print(output.lines().map(l -> "  git differ | " + l + "\n").reduce("", String::concat));
        return new Child(exit, output);
    }

    private static String status(Path repo) throws Exception {
        Process process = new ProcessBuilder(GIT, "status", "--porcelain", "--untracked-files=all").directory(repo.toFile())
                .redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return text;
    }

    static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(GIT, "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        int exit = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
        require(exit == 0, "git " + String.join(" ", args) + " exit " + exit);
    }

    static boolean gitAvailable() {
        try {
            return new ProcessBuilder(GIT, "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException absent) {
            return false;
        }
    }

    static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
