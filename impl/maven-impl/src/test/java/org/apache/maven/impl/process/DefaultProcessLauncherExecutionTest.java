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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.maven.api.services.LaunchedProcess;
import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchRequest.Shell;
import org.apache.maven.api.services.ProcessLaunchResult;
import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.api.services.ProcessLauncherException;
import org.apache.maven.impl.model.DefaultOsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.impl.process.ProcessLauncherTesting.fixture;
import static org.apache.maven.impl.process.ProcessLauncherTesting.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link ProcessLauncherFixture} on the java of the running JVM: the arguments arrive as sent through each
 * shell the host has, streams are pumped, timeouts destroy the whole process tree and hooks are removed.
 */
class DefaultProcessLauncherExecutionTest {

    private static final ProcessLauncher LAUNCHER = new DefaultProcessLauncher(new DefaultOsService());

    /** The charset a JVM child writes to a pipe on any supported JDK: the platform encoding. */
    private static final Charset NATIVE = Charset.forName(
            System.getProperty("native.encoding", Charset.defaultCharset().name()));

    /** Arguments every shell must deliver unchanged. */
    private static final List<String> SAFE = List.of("plain", "a b", "x&y", "p|q", "(paren)", "caret^", "it's");

    @Test
    void noShellDeliversArgumentsUnchanged() {
        assertEquals(bracketed(SAFE), outputOf(fixture("args").arguments(SAFE)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cmdDeliversArgumentsUnchanged() {
        assertEquals(bracketed(SAFE), outputOf(fixture("args").arguments(SAFE).shell(Shell.CMD)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellDeliversArgumentsUnchanged() {
        List<String> arguments = List.of(
                "plain",
                "a b",
                "it's",
                "$HOME",
                "x&y",
                "semi;colon",
                "x\u2019; Write-Output INJECTED; #",
                "y\u2018; Write-Output INJECTED; #");
        assertEquals(
                bracketed(arguments),
                outputOf(fixture("args")
                        .arguments(arguments)
                        .shell(Shell.POWERSHELL)
                        .charset(NATIVE)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shDeliversArgumentsUnchanged() {
        List<String> arguments = List.of("plain", "a b", "it's", "$HOME", "`cmd`", "x&y", "*.java", "say \"hi\"");
        assertEquals(
                bracketed(arguments),
                outputOf(fixture("args").arguments(arguments).shell(Shell.SH)));
    }

    @Test
    void standardInputIsFedAndClosed() {
        ProcessLaunchRequest.ProcessLaunchRequestBuilder request =
                fixture("cat").input(new ByteArrayInputStream("alpha\nbeta\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("alpha", "beta"), outputOf(request));
    }

    @Test
    void errorLinesGoToTheErrorConsumerOrMergeIntoOutput() {
        List<String> errors = lines();
        ProcessLaunchResult result = LAUNCHER.execute(
                fixture("err", "one", "two").errorConsumer(errors::add).build());
        assertTrue(result.isSuccess());
        assertEquals(List.of("one", "two"), errors);
        assertEquals(
                List.of("one", "two"), outputOf(fixture("err", "one", "two").redirectErrorStream(true)));
    }

    @Test
    void exitCodeIsReported() {
        ProcessLaunchResult result = LAUNCHER.execute(fixture("exit", "3").build());
        assertEquals(3, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertFalse(result.isSuccess());
        assertEquals(LAUNCHER.commandLine(result.getRequest()), result.getCommandLine());
    }

    @Test
    void environmentCanBeClearedSetAndRemoved() {
        ProcessLaunchRequest.ProcessLaunchRequestBuilder cleared =
                fixture("env", "PL_PROBE", "PATH").inheritEnvironment(false).environment("PL_PROBE", "x");
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            cleared.environment("SystemRoot", systemRoot);
        }
        assertEquals(List.of("PL_PROBE=x", "PATH=null"), outputOf(cleared));
        assertEquals(List.of("PATH=null"), outputOf(fixture("env", "PATH").environment("PATH", null)));
    }

    /** On Windows an override of an inherited variable spelled in another case wins, as with maven-shared-utils. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void environmentOverrideOfANameInOtherCaseMatchesSharedUtils() throws Exception {
        assertEquals(List.of("Path=marker"), outputOf(fixture("env", "Path").environment("pATH", "marker")));
        assertEquals(List.of("Path=marker"), sharedUtilsEnvOutput("pATH", "marker"));
    }

    private static List<String> sharedUtilsEnvOutput(String name, String value) throws Exception {
        org.apache.maven.shared.utils.cli.Commandline commandline = new org.apache.maven.shared.utils.cli.Commandline();
        commandline.setExecutable(
                LAUNCHER.commandLine(fixture("env", "Path").build()).get(0));
        commandline.addArguments(fixture("env", "Path").build().getArguments().toArray(String[]::new));
        commandline.addEnvironment(name, value);
        List<String> output = lines();
        org.apache.maven.shared.utils.cli.CommandLineUtils.executeCommandLine(commandline, output::add, line -> {});
        return output;
    }

    @Test
    void workingDirectoryIsApplied(@TempDir Path directory) throws IOException {
        List<String> output = outputOf(fixture("cwd").workingDirectory(directory));
        assertEquals(directory.toRealPath(), Path.of(output.get(0)).toRealPath());
    }

    @Test
    void timeoutDestroysTheProcess() {
        try (LaunchedProcess launched = LAUNCHER.start(fixture("sleep", "60000")
                .timeout(Duration.ofSeconds(2))
                .destroyGracePeriod(Duration.ofSeconds(2))
                .build())) {
            ProcessLaunchResult result = launched.await();
            assertTrue(result.isTimedOut());
            assertFalse(result.isSuccess());
            assertTrue(
                    result.getDuration().compareTo(Duration.ofSeconds(30)) < 0,
                    result.getDuration().toString());
            assertFalse(launched.process().isAlive());
        }
    }

    /**
     * {@code CommandLineUtils} destroys only {@code cmd.exe}; the program behind it must die too. The pid line is
     * awaited first, so a slow JVM start cannot hide the program; the timeout is enforced by {@code await()}.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void timeoutDestroysTheProgramBehindTheShell() throws Exception {
        CompletableFuture<String> pid = new CompletableFuture<>();
        try (LaunchedProcess launched = LAUNCHER.start(fixture("sleep", "60000")
                .shell(Shell.CMD)
                .outputConsumer(pid::complete)
                .timeout(Duration.ofSeconds(1))
                .build())) {
            long program = Long.parseLong(pid.get(60, TimeUnit.SECONDS));
            assertTrue(launched.await().isTimedOut());
            assertFalse(isAlive(program), "java behind cmd.exe still alive");
        }
    }

    @Test
    void failingConsumerFailsTheExecution() {
        ProcessLauncherException e = assertThrows(
                ProcessLauncherException.class,
                () -> LAUNCHER.execute(fixture("args", "a", "b")
                        .outputConsumer(line -> {
                            throw new IllegalStateException("rejected " + line);
                        })
                        .build()));
        assertEquals("Failure processing stdout.", e.getMessage());
        assertEquals("rejected [a]", e.getCause().getMessage());
    }

    @Test
    void startLeavesUnconsumedStreamsToTheCallerAndCloseRemovesTheHook() throws IOException {
        try (LaunchedProcess launched = LAUNCHER.start(fixture("args", "a b").build())) {
            String output = new String(launched.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(List.of("[a b]"), output.lines().collect(Collectors.toList()));
            assertEquals(0, launched.await().getExitCode());
            Thread hook = ((DefaultLaunchedProcess) launched).shutdownHook().orElseThrow();
            assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "hook still registered");
        }
    }

    @Test
    void destroyOnShutdownCanBeDisabled() {
        try (LaunchedProcess launched =
                LAUNCHER.start(fixture("exit", "0").destroyOnShutdown(false).build())) {
            assertTrue(((DefaultLaunchedProcess) launched).shutdownHook().isEmpty());
            assertEquals(0, launched.await().getExitCode());
        }
    }

    @Test
    void closeDestroysARunningProcess() {
        LaunchedProcess launched = LAUNCHER.start(fixture("sleep", "60000").build());
        launched.close();
        assertFalse(launched.process().isAlive());
    }

    @Test
    void missingProgramFailsToStart() {
        ProcessLauncherException e = assertThrows(
                ProcessLauncherException.class,
                () -> LAUNCHER.execute(ProcessLauncherTesting.request()
                        .executable("no-such-program-4711")
                        .build()));
        assertEquals("Error while executing process.", e.getMessage());
    }

    /** Whether the process is still alive after waiting up to ten seconds for it to exit. */
    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid)
                .map(DefaultProcessLauncherExecutionTest::aliveAfterWait)
                .orElse(false);
    }

    private static boolean aliveAfterWait(ProcessHandle handle) {
        try {
            handle.onExit().get(10, TimeUnit.SECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> outputOf(ProcessLaunchRequest.ProcessLaunchRequestBuilder request) {
        List<String> output = lines();
        ProcessLaunchResult result = LAUNCHER.execute(request.outputConsumer(output::add)
                .timeout(Duration.ofSeconds(60))
                .build());
        assertEquals(0, result.getExitCode(), () -> "exit code of " + result.getCommandLine() + ": " + output);
        return output;
    }

    private static List<String> bracketed(List<String> arguments) {
        return arguments.stream().map(argument -> "[" + argument + "]").collect(Collectors.toList());
    }
}
