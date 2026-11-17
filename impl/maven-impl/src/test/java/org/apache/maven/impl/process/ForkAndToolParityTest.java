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
package org.apache.maven.impl.process;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.maven.api.Toolchain;
import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchRequest.Shell;
import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.impl.model.DefaultOsService;
import org.apache.maven.shared.utils.Os;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.apache.maven.shared.utils.cli.Commandline;
import org.apache.maven.shared.utils.cli.javatool.AbstractJavaTool;
import org.apache.maven.shared.utils.cli.javatool.JavaToolRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.apache.maven.impl.process.ProcessLauncherTesting.cell;
import static org.apache.maven.impl.process.ProcessLauncherTesting.request;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity with the forked JVM command line surefire 3.5 builds in {@code DefaultForkConfiguration.createCommandLine}
 * on maven-shared-utils {@code Commandline}, and with the Java tool lookup of maven-shared-utils 3.5.0
 * {@code AbstractJavaTool}. Writes {@code target/process-launcher-parity/FORK_TOOL_PARITY.tsv}.
 */
class ForkAndToolParityTest {

    private static final ProcessLauncher LAUNCHER = new DefaultProcessLauncher(new DefaultOsService());
    private static final List<String> ROWS = new ArrayList<>(List.of("reference\tcase\tequal\tours\ttheirs"));
    private static final String ARG_LINE = "-Xmx512m\n  -Dfile.encoding=UTF-8\t-Dsecret=\"two words\" -ea";
    private static final String DEBUG_LINE = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005";
    private static final String JVM = "C:/Program Files/Java/jdk-21/bin/java";

    @AfterAll
    static void writeTable() {
        ProcessLauncherTesting.writeTable("FORK_TOOL_PARITY.tsv", ROWS);
    }

    @Test
    void forkArgumentsMatchSurefireOnSharedUtils() throws CommandLineException {
        Commandline surefire = surefireCommandline();
        ProcessLaunchRequest ours = forkRequest();
        record("surefire-3.5", "arguments", ours.getArguments(), Arrays.asList(surefire.getArguments()));
        record(
                "surefire-3.5",
                "shell-command-line",
                LAUNCHER.commandLine(ours),
                surefire.getShell().getShellCommandLine(surefire.getArguments()));
        assertEquals(Arrays.asList(surefire.getArguments()), ours.getArguments());
        assertEquals(surefire.getShell().getShellCommandLine(surefire.getArguments()), LAUNCHER.commandLine(ours));
    }

    /** Surefire sends a variable without a value as an empty string; a launcher request says so explicitly. */
    @Test
    void forkEnvironmentMatchesSurefireOnSharedUtils() throws CommandLineException {
        Map<String, String> theirs = environment(surefireCommandline().getEnvironmentVariables());
        Map<String, String> ours = new HashMap<>();
        DefaultProcessLauncher.applyEnvironment(ours, forkRequest());
        for (String name : List.of("SUREFIRE_FORK", "EMPTY_VALUE")) {
            record("surefire-3.5", "environment " + name, ours.get(name), theirs.get(name));
            assertEquals(theirs.get(name), ours.get(name), name);
        }
    }

    @Test
    void toolLookupMatchesSharedUtilsJavaTool() {
        Toolchain toolchain = new ProcessLauncherTesting.StubToolchain(Map.of("javadoc", "/opt/jdk/bin/javadoc"));
        Stream.of("java", "javac", "javadoc", "jar", "no-such-tool").forEach(tool -> {
            assertToolParity(tool, null);
            assertToolParity(tool, toolchain);
        });
    }

    @Test
    void requestToolResolvesLikeSharedUtilsJavaTool() {
        String theirs = new ReferenceJavaTool("javac", null).find();
        List<String> commandLine = LAUNCHER.commandLine(
                request().tool("javac").argument("-version").build());
        record("maven-shared-utils", "request tool javac", commandLine.get(0), theirs);
        assertEquals(List.of(theirs, "-version"), commandLine);
    }

    private static void assertToolParity(String tool, Toolchain toolchain) {
        String ours = JavaToolLocator.locate(
                tool,
                Optional.ofNullable(toolchain),
                Os.isFamily(Os.FAMILY_WINDOWS),
                System.getProperty("java.home"),
                System::getenv);
        String theirs = new ReferenceJavaTool(tool, toolchain).find();
        record("maven-shared-utils", "tool " + tool + (toolchain == null ? "" : " +toolchain"), ours, theirs);
        assertEquals(theirs, ours, tool);
    }

    /** The command line of {@code DefaultForkConfiguration.createCommandLine} in surefire 3.5. */
    private static Commandline surefireCommandline() throws CommandLineException {
        Commandline cli = new Commandline();
        cli.setWorkingDirectory(Path.of("target").toAbsolutePath().toString());
        surefireEnvironment().forEach((name, value) -> cli.addEnvironment(name, value == null ? "" : value));
        cli.setExecutable(JVM);
        cli.createArg().setLine(ARG_LINE.replaceAll("\\s", " "));
        cli.createArg().setLine(DEBUG_LINE);
        cli.addArguments("-cp", "target/test-classes", "org.apache.maven.surefire.booter.ForkedBooter");
        return cli;
    }

    private static ProcessLaunchRequest forkRequest() {
        ProcessLaunchRequest.ProcessLaunchRequestBuilder builder = request()
                .shell(Shell.DEFAULT)
                .workingDirectory(Path.of("target"))
                .executable(JVM)
                .arguments(LAUNCHER.tokenize(ARG_LINE.replaceAll("\\s", " ")))
                .arguments(LAUNCHER.tokenize(DEBUG_LINE))
                .arguments("-cp", "target/test-classes", "org.apache.maven.surefire.booter.ForkedBooter");
        surefireEnvironment().forEach((name, value) -> builder.environment(name, value == null ? "" : value));
        return builder.build();
    }

    private static Map<String, String> surefireEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SUREFIRE_FORK", "1");
        environment.put("EMPTY_VALUE", null);
        return environment;
    }

    private static Map<String, String> environment(String[] variables) {
        Map<String, String> environment = new HashMap<>();
        Stream.of(variables).map(variable -> variable.split("=", 2)).forEach(kv -> environment.put(kv[0], kv[1]));
        return environment;
    }

    private static void record(String reference, String name, Object ours, Object theirs) {
        ROWS.add(String.join("\t", reference, name, String.valueOf(ours.equals(theirs)), cell(ours), cell(theirs)));
    }

    /** maven-shared-utils {@code AbstractJavaTool} with its protected tool lookup opened for comparison. */
    static final class ReferenceJavaTool extends AbstractJavaTool<JavaToolRequest> {
        ReferenceJavaTool(String tool, Toolchain toolchain) {
            super(tool);
            setToolchain(toolchain);
        }

        @Override
        protected Commandline createCommandLine(JavaToolRequest request, String javaToolFileLocation) {
            throw new UnsupportedOperationException();
        }

        String find() {
            return findJavaToolExecutable();
        }
    }
}
