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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchRequest.Shell;
import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.api.services.ProcessLauncherException;
import org.apache.maven.impl.process.ProcessLauncherTesting.StubOs;
import org.apache.maven.impl.process.ProcessLauncherTesting.StubToolchain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.impl.process.ProcessLauncherTesting.SESSION;
import static org.apache.maven.impl.process.ProcessLauncherTesting.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the request, the command line rendering, the environment and the argument checks. */
class DefaultProcessLauncherTest {

    private static final ProcessLauncher WINDOWS = new DefaultProcessLauncher(new StubOs(true));
    private static final ProcessLauncher POSIX = new DefaultProcessLauncher(new StubOs(false));

    @Test
    void requestDefaults() {
        ProcessLaunchRequest request = request().executable("x").build();
        assertEquals(Shell.NONE, request.getShell());
        assertTrue(request.isInheritEnvironment());
        assertTrue(request.isDestroyOnShutdown());
        assertFalse(request.isRedirectErrorStream());
        assertEquals(ProcessLaunchRequest.DEFAULT_DESTROY_GRACE_PERIOD, request.getDestroyGracePeriod());
        assertTrue(request.getTimeout().isEmpty());
        assertTrue(request.getCharset().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getArguments().add("y"));
    }

    @Test
    void requestNeedsSession() {
        assertThrows(
                NullPointerException.class,
                () -> ProcessLaunchRequest.builder().executable("x").build());
    }

    @Test
    void requestIsImmutableAfterBuild() {
        ProcessLaunchRequest.ProcessLaunchRequestBuilder builder =
                request().executable("x").argument("a");
        ProcessLaunchRequest first = builder.build();
        builder.argument("b").environment("K", "V");
        assertEquals(List.of("a"), first.getArguments());
        assertTrue(first.getEnvironment().isEmpty());
        assertNotEquals(first, builder.build());
        assertEquals(first, request().executable("x").argument("a").build());
        assertEquals(
                first.hashCode(),
                request().executable("x").argument("a").build().hashCode());
    }

    @Test
    void requestToStringHidesMaskedArgumentsAndEnvironmentValues() {
        String text = request()
                .executable("gpg")
                .maskedArgument("s3cret")
                .environment("TOKEN", "t0ken")
                .build()
                .toString();
        assertTrue(text.contains(ProcessLaunchRequest.MASK), text);
        assertTrue(text.contains("TOKEN"), text);
        assertFalse(text.contains("s3cret") || text.contains("t0ken"), text);
    }

    @Test
    void zeroOrNegativeTimeoutMeansNone() {
        assertTrue(request().timeout(Duration.ZERO).build().getTimeout().isEmpty());
        assertTrue(
                request().timeout(Duration.ofSeconds(-1)).build().getTimeout().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> request().destroyGracePeriod(Duration.ofSeconds(-1)));
    }

    @Test
    void buildShorthandRunsWithoutShell() {
        ProcessLaunchRequest request = ProcessLaunchRequest.build(SESSION, "git", "status", "--short");
        assertEquals(List.of("git", "status", "--short"), POSIX.commandLine(request));
    }

    @Test
    void noShellPassesTheCommandLineAsGiven() {
        ProcessLaunchRequest request = request()
                .executable("C:/tools/x.exe")
                .arguments("a b", "", "say \"hi\"")
                .build();
        assertEquals(List.of("C:/tools/x.exe", "a b", "", "say \"hi\""), WINDOWS.commandLine(request));
    }

    @Test
    void firstArgumentIsTheProgramWithoutExecutableOrTool() {
        assertEquals(
                List.of("git", "log"),
                POSIX.commandLine(request().arguments("git", "log").build()));
    }

    @Test
    void cmdQuotesSpecialCharactersAndWrapsTheLine() {
        ProcessLaunchRequest request = request()
                .shell(Shell.CMD)
                .executable("C:/Program Files/x.exe")
                .arguments("a b", "plain", "x&y", "\"kept\"")
                .build();
        assertEquals(
                List.of("cmd.exe", "/X", "/C", "\"\"C:\\Program Files\\x.exe\" \"a b\" plain \"x&y\" \"kept\"\""),
                WINDOWS.commandLine(request));
    }

    @Test
    void shQuotesEveryItemAndChangesDirectoryFirst(@TempDir Path directory) {
        ProcessLaunchRequest request = request()
                .shell(Shell.SH)
                .executable("/usr/bin/printf")
                .arguments("it's", "$HOME")
                .workingDirectory(directory)
                .build();
        String dir = directory.toAbsolutePath().toString();
        assertEquals(
                List.of("/bin/sh", "-c", "cd '" + dir + "' && '/usr/bin/printf' 'it'\"'\"'s' '$HOME'"),
                POSIX.commandLine(request));
    }

    @Test
    void bashUsesBourneQuoting() {
        ProcessLaunchRequest request =
                request().shell(Shell.BASH).executable("ls").argument("a b").build();
        assertEquals(List.of("/bin/bash", "-c", "'ls' 'a b'"), POSIX.commandLine(request));
    }

    @Test
    void powerShellDoublesSingleQuotes() {
        ProcessLaunchRequest request = request()
                .shell(Shell.POWERSHELL)
                .executable("/usr/bin/echo")
                .arguments("it's", "say \"hi\"", "$x")
                .build();
        assertEquals(
                List.of(
                        "pwsh",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "& '/usr/bin/echo' 'it''s' 'say \"hi\"' '$x'"),
                POSIX.commandLine(request));
    }

    @Test
    void powerShellOnWindowsEscapesDoubleQuotesForTheCommandLineParser() {
        ProcessLaunchRequest request = request()
                .shell(Shell.POWERSHELL)
                .executable("C:/x.exe")
                .arguments("say \"hi\"", "a\\\"b")
                .build();
        assertEquals(
                List.of(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "& 'C:\\x.exe' 'say \\\"hi\\\"' 'a\\\\\\\"b'"),
                WINDOWS.commandLine(request));
    }

    @Test
    void defaultShellFollowsTheOs() {
        ProcessLaunchRequest request =
                request().shell(Shell.DEFAULT).executable("x").build();
        assertEquals("cmd.exe", WINDOWS.commandLine(request).get(0));
        assertEquals("/bin/sh", POSIX.commandLine(request).get(0));
    }

    @Test
    void describeMasksArguments() {
        ProcessLaunchRequest request = request()
                .executable("gpg")
                .argument("--passphrase")
                .maskedArgument("s3cret")
                .build();
        assertEquals("gpg --passphrase *****", POSIX.describe(request));
        assertEquals(List.of("gpg", "--passphrase", "s3cret"), POSIX.commandLine(request));
    }

    @Test
    void toolchainAnswersFirst() {
        ProcessLaunchRequest request = request()
                .tool("javadoc")
                .toolchain(new StubToolchain(Map.of("javadoc", "/opt/jdk/bin/javadoc")))
                .shell(Shell.SH)
                .build();
        assertEquals(List.of("/bin/sh", "-c", "'/opt/jdk/bin/javadoc'"), POSIX.commandLine(request));
    }

    @Test
    void unknownToolFallsBackToTheCommandName() {
        ProcessLaunchRequest request = request().tool("no-such-tool-4711").build();
        assertEquals(List.of("no-such-tool-4711.exe"), WINDOWS.commandLine(request));
        assertEquals(List.of("no-such-tool-4711"), POSIX.commandLine(request));
    }

    @Test
    void environmentOverridesAndRemovesOnTopOfTheInheritedOne() {
        Map<String, String> environment = new HashMap<>(Map.of("KEEP", "1", "DROP", "2", "SET", "old"));
        DefaultProcessLauncher.applyEnvironment(
                environment,
                request().environment("SET", "new").environment("DROP", null).build());
        assertEquals(Map.of("KEEP", "1", "SET", "new"), environment);
    }

    @Test
    void clearedEnvironmentHoldsOnlyTheRequestVariables() {
        Map<String, String> environment = new HashMap<>(Map.of("KEEP", "1"));
        DefaultProcessLauncher.applyEnvironment(
                environment,
                request().inheritEnvironment(false).environment("ONLY", "x").build());
        assertEquals(Map.of("ONLY", "x"), environment);
    }

    @Test
    void missingWorkingDirectoryFailsWithTheCommandlineMessage(@TempDir Path directory) {
        Path missing = directory.resolve("missing");
        ProcessLaunchRequest request =
                request().executable("x").workingDirectory(missing).build();
        ProcessLauncherException e = assertThrows(ProcessLauncherException.class, () -> POSIX.execute(request));
        assertEquals("Working directory \"" + missing + "\" does not exist!", e.getMessage());
    }

    @Test
    void fileAsWorkingDirectoryFailsWithTheCommandlineMessage(@TempDir Path directory) throws Exception {
        Path file = Files.createFile(directory.resolve("file"));
        ProcessLaunchRequest request =
                request().executable("x").workingDirectory(file).build();
        ProcessLauncherException e = assertThrows(ProcessLauncherException.class, () -> POSIX.execute(request));
        assertEquals("Path \"" + file + "\" does not specify a directory.", e.getMessage());
    }

    @Test
    void emptyRequestHasNothingToExecute() {
        ProcessLauncherException e = assertThrows(
                ProcessLauncherException.class, () -> POSIX.execute(request().build()));
        assertTrue(e.getMessage().startsWith("Nothing to execute"), e.getMessage());
    }

    @Test
    void tokenizeKeepsEscapesAndRejectsUnbalancedQuotes() {
        assertEquals(List.of(), POSIX.tokenize(null));
        assertEquals(List.of("a b", "c\\\"d"), POSIX.tokenize("'a b' c\\\"d"));
        assertThrows(ProcessLauncherException.class, () -> POSIX.tokenize("'open"));
    }

    @Test
    void streamPumpKeepsDrainingAfterTheConsumerFails() throws Exception {
        List<String> seen = ProcessLauncherTesting.lines();
        StreamThread pump = StreamThread.pump(
                "stdout",
                new ByteArrayInputStream("one\ntwo\nthree\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                line -> {
                    seen.add(line);
                    throw new IllegalStateException("consumer failed");
                },
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(pump.awaitEnd(Duration.ofSeconds(10)));
        assertEquals(List.of("one"), seen);
        assertEquals("consumer failed", pump.failure().orElseThrow().getMessage());
    }

    @Test
    void streamPumpDecodesWithTheRequestCharset() throws Exception {
        List<String> seen = ProcessLauncherTesting.lines();
        byte[] latin1 = "caf\u00e9".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        StreamThread pump = StreamThread.pump(
                "stdout", new ByteArrayInputStream(latin1), seen::add, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(pump.awaitEnd(Duration.ofSeconds(10)));
        assertEquals(List.of("caf\u00e9"), seen);
        assertTrue(pump.failure().isEmpty());
    }
}
