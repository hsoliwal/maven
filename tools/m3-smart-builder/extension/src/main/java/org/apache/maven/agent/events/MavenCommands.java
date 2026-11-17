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
 * test tool ({@link #testArgs}, {@link #integrationTestArgs}). Every {@code -D} argument is written in double quotes,
 * which bash, cmd.exe and PowerShell all accept (Windows PowerShell 5.1 splits an unquoted
 * {@code -Dkey.with.dots=value} at the first dot); other arguments are quoted when they hold white space or a shell
 * character.
 */
public final class MavenCommands {
    /** Characters that make an argument need quotes (declared first: {@link #AGENT_OPTIONS} uses it). */
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s\"'`$&|;()^%!]");
    /** Arguments of an agent build: batch, no transfer progress, quiet, no colours, agent files on. */
    public static final List<String> AGENT_ARGS =
            List.of("-B", "-ntp", "-q", "-Dstyle.color=never", "-Dmaven.agent=true");
    /** {@link #AGENT_ARGS} as typed on a command line. */
    public static final String AGENT_OPTIONS = quoted(AGENT_ARGS);
    /** Selects no unit test, so a Failsafe run of the integration-test tool skips Surefire's tests. */
    public static final String NO_UNIT_TEST = "NoUnitTestSelected";

    private MavenCommands() {}

    /** {@code ./mvnw} when the root has the Maven Wrapper, else {@code mvn}. */
    public static String launcher(Path root) {
        return Files.isRegularFile(root.resolve("mvnw")) ? "./mvnw" : "mvn";
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
        return inModule(launcher, path, List.of(phase));
    }

    /** Selected tests ({@code Class#method}, comma-separated) of one module with Surefire. */
    public static String test(String launcher, String path, String selector) {
        return inModule(launcher, path, testArgs(selector));
    }

    /** Selected integration tests of one module with Failsafe; Surefire's unit tests are not run. */
    public static String integrationTest(String launcher, String path, String selector) {
        return inModule(launcher, path, integrationTestArgs(selector));
    }

    /** A goal run from the command line ({@code goalArgs}: its properties, then {@code g:a:v:goal}) in one module. */
    public static String goal(String launcher, String path, List<String> goalArgs) {
        return inModule(launcher, path, goalArgs);
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

    /** {@code launcher} and {@code args} as one command line, quoted as described above. */
    public static String line(String launcher, List<String> args) {
        return args.isEmpty() ? launcher : launcher + " " + quoted(args);
    }

    private static String inModule(String launcher, String path, List<String> args) {
        List<String> all = new ArrayList<>(List.of("-pl", path, "-am"));
        all.addAll(args);
        return line(launcher, all);
    }

    private static String quoted(List<String> args) {
        return args.stream().map(MavenCommands::quote).collect(Collectors.joining(" "));
    }

    private static String quote(String arg) {
        boolean quote = arg.startsWith("-D") || arg.isEmpty() || NEEDS_QUOTES.matcher(arg).find();
        return quote ? '"' + arg.replace("\"", "\\\"") + '"' : arg;
    }
}
