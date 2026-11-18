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
package org.apache.maven.agent.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Maven command lines agents are told to run: the one owner of their spelling, used by the failure digest's
 * reproduce commands, by the project description and {@code llms.txt} of maven-agent-plugin, and by the MCP server's
 * test tool ({@link #testArgs}, {@link #integrationTestArgs}). Every {@code -D} and {@code -P} argument is written in
 * double quotes, which bash, cmd.exe and PowerShell all accept (Windows PowerShell 5.1 splits an unquoted
 * {@code -Dkey.with.dots=value} at the first dot and an unquoted {@code -Pa,b} at the comma); any other argument is
 * written bare only when it holds nothing but {@code A-Z a-z 0-9 _ . / : + = -}, plus {@code @} and {@code #} after its
 * first character, and is quoted otherwise (white space, redirections, globs, braces, a leading {@code ~}, {@code #} or
 * {@code @}, commas). Callers pass only {@link #quotable} arguments, which every shell keeps unchanged inside double
 * quotes. Only the launcher differs per shell ({@link Launchers}).
 */
public final class MavenCommands {
    /** An argument every shell takes unquoted (declared first: {@link #AGENT_OPTIONS} uses it). */
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_./:+=-][A-Za-z0-9_./:+=@#-]*+");
    /**
     * Characters some shell changes inside double quotes: quote, backtick, $, %, !, backslash, control, and the
     * typographic dashes and quotes U+2013 to U+201E, which PowerShell reads as '-' and as quotes.
     */
    private static final Pattern UNQUOTABLE = Pattern.compile("[\"`$%!\\\\\\p{Cntrl}\u2013-\u201E]");
    /** The Maven Wrapper as bash (and PowerShell) runs it from the root. */
    public static final String WRAPPER = "./mvnw";
    /** The Maven Wrapper as PowerShell and cmd.exe run it from the root. */
    public static final String WINDOWS_WRAPPER = ".\\mvnw.cmd";
    /** Arguments of an agent build: batch, no transfer progress, quiet, no colours, agent files on. */
    public static final List<String> AGENT_ARGS =
            List.of("-B", "-ntp", "-q", "-Dstyle.color=never", "-Dmaven.agent=true");
    /** {@link #AGENT_ARGS} as typed on a command line. */
    public static final String AGENT_OPTIONS = quoted(AGENT_ARGS);
    /** Selects no unit test, so a Failsafe run of the integration-test tool skips Surefire's tests. */
    public static final String NO_UNIT_TEST = "NoUnitTestSelected";

    /**
     * The launcher as each shell starts it: {@code ./mvnw} in bash, {@code .\mvnw.cmd} in PowerShell and in cmd.exe
     * ({@code ./mvnw} is no command in cmd.exe, and a bare {@code mvnw.cmd} is not found there when
     * {@code NoDefaultCurrentDirectoryInExePath} is set); {@code mvn} is the same everywhere.
     */
    public record Launchers(String sh, String powershell, String cmd) {
        /** The spellings of {@code launcher}, as {@link #launcher(Path)} gives it. */
        public static Launchers of(String launcher) {
            return WRAPPER.equals(launcher) ? new Launchers(WRAPPER, WINDOWS_WRAPPER, WINDOWS_WRAPPER)
                    : new Launchers(launcher, launcher, launcher);
        }

        /** Whether some shell needs another spelling. */
        public boolean differ() {
            return !sh.equals(powershell) || !sh.equals(cmd);
        }

        /** The other shells' spelling as a clause: {@code PowerShell and cmd.exe start it as `.\mvnw.cmd`}. */
        public String otherShells() {
            return powershell.equals(cmd) ? "PowerShell and cmd.exe start it as `" + cmd + "`"
                    : "PowerShell starts it as `" + powershell + "`, cmd.exe as `" + cmd + "`";
        }

        /** Each shell's spelling in plain text: {@code ./mvnw in bash; .\mvnw.cmd in PowerShell and cmd.exe}. */
        public String perShell() {
            return sh + " in bash; " + (powershell.equals(cmd) ? powershell + " in PowerShell and cmd.exe"
                    : powershell + " in PowerShell; " + cmd + " in cmd.exe");
        }

        /** {@code {"sh":..., "powershell":..., "cmd":...}} as failure.json and project.json carry it. */
        public JsonText.Obj json() {
            return new JsonText.Obj().put("sh", sh).put("powershell", powershell).put("cmd", cmd);
        }
    }

    private MavenCommands() {}

    /** {@code ./mvnw} when {@code dir} has the Maven Wrapper, else {@code mvn}. */
    public static String launcher(Path dir) {
        return Files.isRegularFile(dir.resolve("mvnw")) ? WRAPPER : "mvn";
    }

    /** Whether every shell keeps {@code arg} unchanged inside double quotes. */
    public static boolean quotable(String arg) {
        return !UNQUOTABLE.matcher(arg).find();
    }

    /** The whole build. */
    public static String build(String launcher) {
        return launcher + " verify";
    }

    /** The whole build in agent mode: the summary on standard output, the files in {@code target/agent}. */
    public static String agent(String launcher) {
        return launcher + " " + AGENT_OPTIONS + " verify";
    }

    /** One module with the modules it needs, up to {@code phase}. */
    public static String module(String launcher, String path, String phase) {
        return line(launcher, inModule(path, List.of(phase)));
    }

    /** Selected tests ({@code Class#method}, comma-separated) of one module with Surefire. */
    public static String test(String launcher, String path, String selector) {
        return line(launcher, inModule(path, testArgs(selector)));
    }

    /** Selected integration tests of one module with Failsafe; Surefire's unit tests are not run. */
    public static String integrationTest(String launcher, String path, String selector) {
        return line(launcher, inModule(path, integrationTestArgs(selector)));
    }

    /** The arguments that run the {@code selector} tests with Surefire. */
    public static List<String> testArgs(String selector) {
        return List.of("-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false", "test");
    }

    /** The arguments that run the {@code selector} integration tests with Failsafe and no unit test. */
    public static List<String> integrationTestArgs(String selector) {
        return List.of("-Dit.test=" + selector, "-Dfailsafe.failIfNoSpecifiedTests=false", "-Dtest=" + NO_UNIT_TEST,
                "-Dsurefire.failIfNoSpecifiedTests=false", "verify");
    }

    /** {@code -pl <path> -am} followed by {@code args}: one module and what it needs. */
    public static List<String> inModule(String path, List<String> args) {
        List<String> all = new ArrayList<>(List.of("-pl", path, "-am"));
        all.addAll(args);
        return all;
    }

    /** {@code launcher} and {@code args} as one command line, quoted as described above. */
    public static String line(String launcher, List<String> args) {
        return args.isEmpty() ? launcher : launcher + " " + quoted(args);
    }

    private static String quoted(List<String> args) {
        return args.stream().map(MavenCommands::quote).collect(Collectors.joining(" "));
    }

    private static String quote(String arg) {
        boolean quote = arg.startsWith("-D") || arg.startsWith("-P") || !BARE.matcher(arg).matches();
        return quote ? '"' + arg.replace("\"", "\\\"") + '"' : arg;
    }
}
