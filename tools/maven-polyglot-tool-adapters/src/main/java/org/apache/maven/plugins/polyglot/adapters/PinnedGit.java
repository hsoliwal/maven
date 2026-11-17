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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The git program pinned, so the patch bytes depend on the trees, or on the repository (its content,
 * {@code .gitattributes}, {@code .git/config} and {@code .git/info} files), and on the git build (its zlib for binary
 * patches), never on the user's or the machine's configuration or environment.
 * <ul>
 *   <li>System and global configuration are never read: {@code GIT_CONFIG_NOSYSTEM=1} and {@code GIT_CONFIG_GLOBAL}
 *   naming an empty file (which also replaces the XDG {@code git/config}); so {@code safe.directory} from those files
 *   no longer applies either, and git refuses a repository owned by another user, as it should.</li>
 *   <li>On the command line (which wins over the repository's configuration): line endings, path quoting, the user
 *   attributes and excludes files (an empty file each, instead of the XDG defaults; {@code GIT_ATTR_NOSYSTEM} drops the
 *   system attributes), compression, the big-file threshold, every diff setting the output format reads; and every
 *   setting that would make git run a configured program or fetch: hooks (an empty hooks folder), fsmonitor, the
 *   untracked cache, automatic gc and maintenance, transports ({@code protocol.allow=never}). External diff and
 *   textconv are off by option; a clean filter is refused by {@link GitDiffMain} before git could run it.</li>
 *   <li>Environment variables git reads for diff output, configuration or repository location are removed, lazy
 *   fetches of a partial clone are off ({@code GIT_NO_LAZY_FETCH=1}), and messages are in the C locale so
 *   {@code error:} and {@code fatal:} can be recognised.</li>
 * </ul>
 * Standard input, output and error are files; nothing goes through a shell, and the git launcher is checked like
 * every launcher ({@link CmdLauncher}). JDK only.
 */
final class PinnedGit {
    /** Diff options; together with {@link #config} they fix the format. */
    static final List<String> DIFF_OPTIONS = List.of("--no-color", "--no-ext-diff", "--no-textconv", "--binary",
            "--full-index", "--no-renames", "--unified=3", "--inter-hunk-context=0", "--diff-algorithm=myers",
            "--indent-heuristic", "--src-prefix=a/", "--dst-prefix=b/", "--no-relative", "--ignore-submodules=none",
            "--submodule=short");
    /** Removed from the environment: diff output, configuration injection and repository location variables. */
    static final Set<String> REMOVED = Set.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR", "GIT_NAMESPACE", "GIT_DIFF_OPTS", "GIT_EXTERNAL_DIFF",
            "GIT_DIFF_PATH_COUNTER", "GIT_DIFF_PATH_TOTAL", "GIT_ATTR_SOURCE", "GIT_LITERAL_PATHSPECS",
            "GIT_GLOB_PATHSPECS", "GIT_NOGLOB_PATHSPECS", "GIT_ICASE_PATHSPECS", "GIT_REPLACE_REF_BASE",
            "GIT_NO_REPLACE_OBJECTS", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "GIT_CONFIG", "GIT_PAGER", "LANGUAGE",
            "GIT_CONFIG_SYSTEM", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM");

    /** One finished git call: exit code, standard output bytes and standard error text. */
    record Result(int exit, byte[] stdout, String stderr) {
        /** git reports failures as {@code error:} or {@code fatal:} lines (C locale), also when it exits 1. */
        boolean failed() {
            return stderr.lines().anyMatch(line -> line.startsWith("error:") || line.startsWith("fatal:"));
        }
    }

    private final String git;
    private final Path scratch;

    /** {@code scratch} is a private, empty folder this instance may write (the empty file and hooks folder, streams). */
    PinnedGit(String git, Path scratch) throws IOException {
        this.git = git;
        this.scratch = scratch;
        Files.writeString(scratch.resolve("empty"), "", StandardCharsets.UTF_8);
        Files.createDirectories(scratch.resolve("hooks"));
    }

    /** The configuration given on the command line, before the git subcommand. */
    List<String> config() {
        String empty = slash(scratch.resolve("empty"));
        return Stream.of("core.autocrlf=false", "core.safecrlf=false", "core.quotePath=true", "core.attributesFile="
                + empty, "core.excludesFile=" + empty, "core.compression=-1", "core.bigFileThreshold=512m",
                "core.hooksPath=" + slash(scratch.resolve("hooks")), "core.fsmonitor=false", "core.untrackedCache=false",
                "gc.auto=0", "maintenance.auto=false", "protocol.allow=never", "diff.noprefix=false",
                "diff.mnemonicPrefix=false", "diff.relative=false", "diff.suppressBlankEmpty=false",
                "diff.indentHeuristic=true", "diff.orderFile=" + empty, "diff.ignoreSubmodules=none", "diff.submodule=short",
                "diff.renames=false").flatMap(setting -> Stream.of("-c", setting)).toList();
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * The environment of every git call: {@link #REMOVED} and GIT_CONFIG_KEY_n/VALUE_n dropped; no system
     * configuration, {@code emptyGlobal} as the global configuration, no lazy fetch, no prompt, locale C.
     */
    static Map<String, String> pin(Map<String, String> environment, Path emptyGlobal) {
        environment.keySet().removeIf(key -> REMOVED.contains(key) || key.startsWith("GIT_CONFIG_KEY_")
                || key.startsWith("GIT_CONFIG_VALUE_"));
        environment.putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", emptyGlobal.toString(),
                "GIT_NO_LAZY_FETCH", "1", "GIT_ATTR_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C"));
        return environment;
    }

    /** Runs {@code git --no-replace-objects <config> <args>} in the folder, with {@code extra} environment variables. */
    Result run(Path directory, Map<String, String> extra, List<String> args) throws IOException, InterruptedException {
        return run(directory, extra, args, null);
    }

    /** The same with {@code input} as standard input (none when null). */
    Result run(Path directory, Map<String, String> extra, List<String> args, Path input)
            throws IOException, InterruptedException {
        List<String> command = Stream.of(List.of(git, "--no-replace-objects"), config(), args).flatMap(List::stream)
                .toList();
        Path out = Files.createTempFile(scratch, "git-", ".out");
        Path err = Files.createTempFile(scratch, "git-", ".err");
        ProcessBuilder builder = new ProcessBuilder(CmdLauncher.shellSafe(command)).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(input == null
                        ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.from(input.toFile()));
        pin(builder.environment(), scratch.resolve("empty")).putAll(extra);
        Process process = builder.start();
        process.getOutputStream().close();
        return new Result(process.waitFor(), Files.readAllBytes(out), Files.readString(err, StandardCharsets.UTF_8));
    }
}
