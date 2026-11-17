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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The git program with every setting that changes diff bytes pinned, so the bytes depend on the trees and the
 * repository only, never on the user's or the machine's configuration or environment. Pinned on the command line
 * (which wins over system, global and repository configuration): line endings, path quoting, the user attributes file
 * (an empty file; {@code GIT_ATTR_NOSYSTEM} drops the system one), the big-file threshold, and every diff setting the
 * output format reads; the diff options repeat them where git has one. Environment variables git reads for diff
 * output, configuration or repository location are removed, and messages are in the C locale so {@code error:} and
 * {@code fatal:} can be recognised. System and global configuration files are still read for what does not change
 * bytes ({@code safe.directory}, long paths). Repository {@code .gitattributes} stay in effect: they are versioned
 * content, identical on every machine. Standard output and error are captured in files; nothing goes through a
 * shell, and a .cmd/.bat git is checked like every launcher ({@link CmdLauncher}). JDK only.
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
            "GIT_NO_REPLACE_OBJECTS", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT", "GIT_CONFIG", "GIT_PAGER", "LANGUAGE");

    /** One finished git call: exit code, standard output bytes and standard error text. */
    record Result(int exit, byte[] stdout, String stderr) {
        /** git reports failures as {@code error:} or {@code fatal:} lines (C locale), also when it exits 1. */
        boolean failed() {
            return stderr.lines().anyMatch(line -> line.startsWith("error:") || line.startsWith("fatal:"));
        }
    }

    private final String git;
    private final Path scratch;

    /** {@code scratch} is a private, empty folder this instance may write (the empty file, captured streams). */
    PinnedGit(String git, Path scratch) throws IOException {
        this.git = git;
        this.scratch = scratch;
        Files.writeString(scratch.resolve("empty"), "", StandardCharsets.UTF_8);
    }

    /** The configuration given on the command line, before the git subcommand. */
    List<String> config() {
        String empty = scratch.resolve("empty").toString().replace('\\', '/');
        return Stream.of("core.autocrlf=false", "core.safecrlf=false", "core.quotePath=true", "core.attributesFile="
                + empty, "core.bigFileThreshold=512m", "diff.noprefix=false", "diff.mnemonicPrefix=false",
                "diff.relative=false", "diff.suppressBlankEmpty=false", "diff.indentHeuristic=true",
                "diff.orderFile=" + empty, "diff.ignoreSubmodules=none", "diff.submodule=short", "diff.renames=false")
                .flatMap(setting -> Stream.of("-c", setting)).toList();
    }

    /** The environment of every git call: {@link #REMOVED} and GIT_CONFIG_KEY_n/VALUE_n dropped, locale C. */
    static Map<String, String> pin(Map<String, String> environment) {
        environment.keySet().removeIf(key -> REMOVED.contains(key) || key.startsWith("GIT_CONFIG_KEY_")
                || key.startsWith("GIT_CONFIG_VALUE_"));
        environment.put("GIT_ATTR_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("LC_ALL", "C");
        return environment;
    }

    /** Runs {@code git [-C dir] --no-replace-objects <config> <args>} with {@code extra} environment variables. */
    Result run(Path directory, Map<String, String> extra, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(git, "--no-replace-objects"));
        command.addAll(config());
        command.addAll(args);
        Path out = Files.createTempFile(scratch, "git-", ".out");
        Path err = Files.createTempFile(scratch, "git-", ".err");
        ProcessBuilder builder = new ProcessBuilder(CmdLauncher.shellSafe(command)).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
        pin(builder.environment()).putAll(extra);
        Process process = builder.start();
        process.getOutputStream().close();
        int exit = process.waitFor();
        return new Result(exit, Files.readAllBytes(out), Files.readString(err, StandardCharsets.UTF_8));
    }
}
