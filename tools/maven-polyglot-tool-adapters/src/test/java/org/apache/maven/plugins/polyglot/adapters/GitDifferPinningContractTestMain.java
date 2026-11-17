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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The git differ against configuration it must not obey, with the real git program: commands configured inside the
 * target repository (an fsmonitor hook, a clean filter) never run, and system, global and XDG configuration
 * (compression, an ignore file, a diff driver's function-name pattern) never change the patch bytes in either mode.
 * Prints NOT RUN when git is not on PATH. Shares the process helpers of {@link GitDifferContractTestMain}.
 */
public final class GitDifferPinningContractTestMain {
    private static final String GIT = GitDifferContractTestMain.GIT;
    private static int passed;

    private GitDifferPinningContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        if (!GitDifferContractTestMain.gitAvailable()) {
            System.out.println("NOT RUN: git differ pinning contract cases (git not on PATH)");
            return;
        }
        Path ws = Files.createTempDirectory("git-pinning-").toRealPath();
        try {
            cases(ws, Map.of("GIT_CONFIG_GLOBAL", write(ws.resolve("cfg/empty"), "").toString(),
                    "GIT_CONFIG_NOSYSTEM", "1"));
            System.out.println("PASS: " + passed + " git differ pinning contract cases");
        } finally {
            GitDifferContractTestMain.delete(ws);
        }
    }

    private static void cases(Path ws, Map<String, String> clean) throws Exception {
        repositoryHookNeverRuns(ws, clean);
        filterAttributeIsRefused(ws, clean);
        configurationNeverChangesRepositoryBytes(ws, clean);
        configurationNeverChangesTreesBytes(ws, clean);
    }

    private static void repositoryHookNeverRuns(Path ws, Map<String, String> clean) throws Exception {
        Path repo = committed(ws.resolve("hook"), "a.txt", "one\n");
        Path hook = script(ws.resolve("bin/fsmonitor.sh"), "#!/bin/sh\necho ran >> \"$0.ran\"\n");
        GitDifferContractTestMain.git(repo, "config", "core.fsmonitor", slash(hook));
        write(repo.resolve("a.txt"), "two\n");
        GitDifferContractTestMain.Child run = repo(repo, clean, ws.resolve("hook.patch"));
        require(run.exit() == 0 && !Files.exists(Path.of(hook + ".ran")),
                "the repository's fsmonitor hook never runs: " + run);
        passed++;
    }

    private static void filterAttributeIsRefused(Path ws, Map<String, String> clean) throws Exception {
        Path repo = ws.resolve("filter");
        write(repo.resolve(".gitattributes"), "*.txt filter=probe\n");
        committed(repo, "a.txt", "one\n");
        Path filter = script(ws.resolve("bin/clean.sh"), "#!/bin/sh\necho ran >> \"$0.ran\"\ncat\n");
        GitDifferContractTestMain.git(repo, "config", "filter.probe.clean", slash(filter));
        write(repo.resolve("a.txt"), "two\n");
        Path out = ws.resolve("filter.patch");
        GitDifferContractTestMain.Child run = repo(repo, clean, out);
        require(run.exit() == GitDiffMain.FAILED && !Files.exists(out) && !Files.exists(Path.of(filter + ".ran"))
                && run.output().contains("filter"), "a path with a filter attribute is refused, no filter runs: " + run);
        passed++;
    }

    private static void configurationNeverChangesRepositoryBytes(Path ws, Map<String, String> clean) throws Exception {
        Path repo = changedRepository(ws.resolve("bytes"));
        byte[] expected = Files.readAllBytes(repoPatch(repo, clean, ws.resolve("clean.patch")));
        String text = new String(expected, java.nio.charset.StandardCharsets.UTF_8);
        require(text.contains("GIT binary patch") && text.contains("added.txt") && text.contains("@@ class A {"),
                "the clean patch holds the binary, the untracked file and the function context: " + text);
        hostile(ws).forEach((name, env) -> require(Arrays.equals(expected, bytes(repo, env, ws.resolve(name + ".patch"))),
                "configuration from " + name + " changes no byte"));
        passed++;
    }

    /** A committed binary file and function-context file, both changed, and an untracked file. */
    private static Path changedRepository(Path repo) throws Exception {
        write(repo.resolve("A.java"), "class A {\n" + lines("    int f%d;\n", 10) + "}\n");
        Files.write(repo.resolve("bin.dat"), binary(4096, 0));
        committed(repo, ".gitattributes", "*.java diff=probe\n");
        write(repo.resolve("A.java"), "class A {\n" + lines("    int f%d;\n", 7) + "    int changed;\n"
                + lines("    int g%d;\n", 2) + "}\n");
        Files.write(repo.resolve("bin.dat"), binary(4096, 1));
        write(repo.resolve("added.txt"), "new\n");
        return repo;
    }

    private static void configurationNeverChangesTreesBytes(Path ws, Map<String, String> clean) throws Exception {
        Path dir = ws.resolve("trees");
        Files.createDirectories(dir.resolve("before"));
        Files.createDirectories(dir.resolve("after"));
        Files.write(dir.resolve("before/bin.dat"), binary(4096, 0));
        Files.write(dir.resolve("after/bin.dat"), binary(4096, 1));
        Path plain = dir.resolve("plain.patch");
        require(GitDifferContractTestMain.child(dir, clean, "trees", GIT, "before", "after", plain.toString()).exit() == 0,
                "trees clean run");
        Path global = dir.resolve("hostile.patch");
        GitDifferContractTestMain.child(dir, hostile(ws).get("global"), "trees", GIT, "before", "after", global.toString());
        require(Arrays.equals(Files.readAllBytes(plain), Files.readAllBytes(global)), "global compression changes no byte");
        passed++;
    }

    /** Global, system and XDG/HOME configuration that would change compression, ignores and the function context. */
    private static Map<String, Map<String, String>> hostile(Path ws) throws IOException {
        Path ignore = write(ws.resolve("cfg/ignore"), "added.txt\n");
        String settings = "[core]\n\tcompression = 0\n\texcludesFile = " + slash(ignore) + "\n[diff \"probe\"]\n"
                + "\txfuncname = ^ZZZ\n";
        Path file = write(ws.resolve("cfg/hostile"), settings);
        Path xdg = ws.resolve("cfg/xdg");
        write(xdg.resolve("git/config"), settings);
        write(xdg.resolve("git/ignore"), "added.txt\n");
        write(xdg.resolve(".gitconfig"), settings);
        return Map.of("global", Map.of("GIT_CONFIG_GLOBAL", file.toString()),
                "system", Map.of("GIT_CONFIG_SYSTEM", file.toString(), "GIT_CONFIG_GLOBAL", ws.resolve("cfg/empty").toString()),
                "xdg", Map.of("XDG_CONFIG_HOME", xdg.toString(), "HOME", xdg.toString()));
    }

    private static byte[] bytes(Path repo, Map<String, String> env, Path out) {
        try {
            return Files.readAllBytes(repoPatch(repo, env, out));
        } catch (Exception failure) {
            throw new AssertionError("hostile run failed: " + env, failure);
        }
    }

    private static Path repoPatch(Path repo, Map<String, String> env, Path out) throws Exception {
        GitDifferContractTestMain.Child run = repo(repo, env, out);
        require(run.exit() == 0, "repository diff: " + run);
        return out;
    }

    private static GitDifferContractTestMain.Child repo(Path repo, Map<String, String> env, Path out) throws Exception {
        return GitDifferContractTestMain.child(repo, env, "repo", GIT, repo.toString(), "HEAD", out.toString());
    }

    /** A repository with one committed file; any other files already present are committed with it. */
    private static Path committed(Path repo, String file, String text) throws Exception {
        write(repo.resolve(file), text);
        GitDifferContractTestMain.git(repo, "init", "--quiet");
        GitDifferContractTestMain.git(repo, "add", "--all");
        GitDifferContractTestMain.git(repo, "-c", "user.name=tester", "-c", "user.email=tester@example.invalid", "-c",
                "commit.gpgsign=false", "commit", "--quiet", "-m", "before");
        return repo;
    }

    private static Path script(Path file, String text) throws IOException {
        write(file, text);
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        }
        return file;
    }

    private static byte[] binary(int size, int seed) {
        byte[] data = new byte[size];
        IntStream.range(0, size).forEach(i -> data[i] = (byte) ((i * 131 + i / 3 + (i == 100 ? seed : 0)) & 0xff));
        return data;
    }

    private static String lines(String format, int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format(java.util.Locale.ROOT, format, i))
                .reduce("", String::concat);
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path write(Path file, String text) throws IOException {
        return GitDifferContractTestMain.write(file, text);
    }

    private static void require(boolean condition, String message) {
        GitDifferContractTestMain.require(condition, message);
    }
}
