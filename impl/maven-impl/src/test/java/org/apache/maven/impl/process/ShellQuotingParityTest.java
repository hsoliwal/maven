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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.api.services.ProcessLaunchRequest.ProcessLaunchRequestBuilder;
import org.apache.maven.api.services.ProcessLaunchRequest.Shell;
import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.impl.model.DefaultOsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.apache.maven.impl.process.ProcessLauncherTesting.cell;
import static org.apache.maven.impl.process.ProcessLauncherTesting.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parity of the shell command lines with maven-shared-utils 3.5.0 and plexus-utils 4.2.0: the same executable,
 * arguments and working directory give the same command line on the host OS. Writes
 * {@code target/process-launcher-parity/SHELL_PARITY.tsv}.
 */
class ShellQuotingParityTest {

    private static final ProcessLauncher LAUNCHER = new DefaultProcessLauncher(new DefaultOsService());
    private static final List<String> ROWS =
            new ArrayList<>(List.of("reference\tshell\tcase\tshape\tequal\tours\ttheirs"));
    private static final String CMD_SPECIALS_BEYOND_SPACE = "\t&<>()@^|";

    /** A corpus entry. */
    record Case(String name, String value) {}

    /** The executable, arguments and working directory of one comparison. */
    record Shape(String name, String executable, List<String> arguments, Optional<Path> directory) {}

    static final List<Case> CORPUS = List.of(
            new Case("plain", "plain"),
            new Case("space", "with space"),
            new Case("tab", "tab\there"),
            new Case("amp", "a&b"),
            new Case("lt", "a<b"),
            new Case("gt", "a>b"),
            new Case("paren", "(paren)"),
            new Case("at", "@file"),
            new Case("caret", "a^b"),
            new Case("pipe", "a|b"),
            new Case("single-quote", "it's"),
            new Case("double-quote", "say \"hi\""),
            new Case("already-quoted", "\"already quoted\""),
            new Case("lone-quote", "\""),
            new Case("empty", ""),
            new Case("dollar", "$HOME"),
            new Case("percent", "%PATH%"),
            new Case("backslash", "back\\slash"),
            new Case("trailing-backslash", "dir\\"),
            new Case("glob", "*.java"),
            new Case("semicolon", "a;b"),
            new Case("backtick", "`cmd`"),
            new Case("bang", "wow!"),
            new Case("newline", "two\nlines"),
            new Case("unicode", "caf\u00e9"),
            new Case("property", "-Dkey=value with space"));

    @AfterAll
    static void writeTable() {
        ProcessLauncherTesting.writeTable("SHELL_PARITY.tsv", ROWS);
    }

    @Test
    void cmdShellMatchesSharedUtils() {
        assertEquals(List.of(), mismatches("maven-shared-utils", Shell.CMD, sharedUtils(CmdShellKind.SHARED)));
    }

    @Test
    void bourneShellMatchesSharedUtils() {
        assertEquals(List.of(), mismatches("maven-shared-utils", Shell.SH, sharedUtils(CmdShellKind.SHARED_BOURNE)));
    }

    @Test
    void defaultShellMatchesSharedUtilsDefaultCommandline() {
        assertEquals(
                List.of(), mismatches("maven-shared-utils", Shell.DEFAULT, sharedUtils(CmdShellKind.SHARED_DEFAULT)));
    }

    @Test
    void bourneShellMatchesPlexusUtils() {
        assertEquals(List.of(), mismatches("plexus-utils", Shell.SH, plexusUtils(CmdShellKind.PLEXUS_BOURNE)));
    }

    /**
     * plexus-utils {@code CmdShell} adds {@code /D} and quotes only on a space, where maven-shared-utils, and this
     * launcher, also quote on a tab and on {@code & < > ( ) @ ^ |}.
     */
    @Test
    void cmdShellDiffersFromPlexusUtilsOnlyInDocumentedWays() {
        List<String> plexus = plexusUtils(CmdShellKind.PLEXUS)
                .apply(shape(new Case("plain", "plain")).get(0));
        assertEquals(List.of("cmd.exe", "/X", "/D", "/C"), plexus.subList(0, 4));
        Function<Shape, List<String>> innerLine =
                shape -> lastElement(plexusUtils(CmdShellKind.PLEXUS).apply(shape));
        Set<String> differing =
                mismatches("plexus-utils", Shell.CMD, innerLine, ShellQuotingParityTest::lastElement).stream()
                        .map(mismatch -> mismatch.split("/")[0])
                        .collect(Collectors.toSet());
        assertEquals(expectedPlexusCmdDifferences(), differing);
    }

    @Test
    void describeMatchesSharedUtilsMaskedToString() {
        String ours = LAUNCHER.describe(request()
                .shell(Shell.SH)
                .executable("gpg")
                .argument("--passphrase")
                .maskedArgument("s3cret pass")
                .build());
        assertEquals(maskedSharedUtilsCommandline().toString(), ours);
        assertFalse(ours.contains("s3cret"));
    }

    private static org.apache.maven.shared.utils.cli.Commandline maskedSharedUtilsCommandline() {
        org.apache.maven.shared.utils.cli.Commandline commandline = new org.apache.maven.shared.utils.cli.Commandline(
                new org.apache.maven.shared.utils.cli.shell.BourneShell());
        commandline.setExecutable("gpg");
        commandline.createArg().setValue("--passphrase");
        org.apache.maven.shared.utils.cli.Arg secret = commandline.createArg();
        secret.setValue("s3cret pass");
        secret.setMask(true);
        return commandline;
    }

    private static Set<String> expectedPlexusCmdDifferences() {
        return CORPUS.stream()
                .filter(c -> c.value().chars().anyMatch(ch -> CMD_SPECIALS_BEYOND_SPACE.indexOf(ch) >= 0))
                .map(Case::name)
                .collect(Collectors.toSet());
    }

    private static List<String> mismatches(String reference, Shell shell, Function<Shape, List<String>> theirs) {
        return mismatches(reference, shell, theirs, commandLine -> commandLine);
    }

    private static List<String> mismatches(
            String reference, Shell shell, Function<Shape, List<String>> theirs, Function<List<String>, Object> view) {
        return CORPUS.stream()
                .flatMap(c -> shape(c).stream()
                        .filter(shape -> !compare(reference, shell, c, shape, theirs.andThen(Object.class::cast), view))
                        .map(shape -> c.name() + "/" + shape.name()))
                .toList();
    }

    private static boolean compare(
            String reference,
            Shell shell,
            Case c,
            Shape shape,
            Function<Shape, Object> theirs,
            Function<List<String>, Object> view) {
        Object ours = view.apply(ours(shell, shape));
        Object expected = theirs.apply(shape);
        boolean equal = ours.equals(expected);
        ROWS.add(String.join(
                "\t",
                reference,
                shell.name(),
                c.name(),
                shape.name(),
                String.valueOf(equal),
                cell(ours),
                cell(expected)));
        return equal;
    }

    private static List<Shape> shape(Case c) {
        Path directory = Path.of("target").toAbsolutePath();
        return List.of(
                new Shape("bare", "java", List.of(c.value()), Optional.empty()),
                new Shape(
                        "path+dir",
                        "C:/Program Files/Java/bin/java.exe",
                        List.of("-x", c.value()),
                        Optional.of(directory)));
    }

    private static List<String> ours(Shell shell, Shape shape) {
        ProcessLaunchRequestBuilder builder =
                request().shell(shell).executable(shape.executable()).arguments(shape.arguments());
        shape.directory().ifPresent(builder::workingDirectory);
        return LAUNCHER.commandLine(builder.build());
    }

    private static List<String> lastElement(List<String> commandLine) {
        return List.of(commandLine.get(commandLine.size() - 1));
    }

    /** The reference shells. */
    enum CmdShellKind {
        SHARED,
        SHARED_BOURNE,
        SHARED_DEFAULT,
        PLEXUS,
        PLEXUS_BOURNE
    }

    private static org.apache.maven.shared.utils.cli.Commandline sharedUtilsCommandline(CmdShellKind kind) {
        return switch (kind) {
            case SHARED ->
                new org.apache.maven.shared.utils.cli.Commandline(
                        new org.apache.maven.shared.utils.cli.shell.CmdShell());
            case SHARED_BOURNE ->
                new org.apache.maven.shared.utils.cli.Commandline(
                        new org.apache.maven.shared.utils.cli.shell.BourneShell());
            default -> new org.apache.maven.shared.utils.cli.Commandline();
        };
    }

    private static Function<Shape, List<String>> sharedUtils(CmdShellKind kind) {
        return shape -> {
            org.apache.maven.shared.utils.cli.Commandline commandline = sharedUtilsCommandline(kind);
            commandline.setExecutable(shape.executable());
            commandline.addArguments(shape.arguments().toArray(String[]::new));
            shape.directory().ifPresent(directory -> commandline.setWorkingDirectory(directory.toFile()));
            return commandline.getShell().getShellCommandLine(commandline.getArguments());
        };
    }

    private static Function<Shape, List<String>> plexusUtils(CmdShellKind kind) {
        return shape -> {
            org.codehaus.plexus.util.cli.Commandline commandline = new org.codehaus.plexus.util.cli.Commandline(
                    kind == CmdShellKind.PLEXUS
                            ? new org.codehaus.plexus.util.cli.shell.CmdShell()
                            : new org.codehaus.plexus.util.cli.shell.BourneShell());
            commandline.setExecutable(shape.executable());
            commandline.addArguments(shape.arguments().toArray(String[]::new));
            shape.directory().ifPresent(directory -> commandline.setWorkingDirectory(directory.toFile()));
            return Arrays.asList(commandline.getShellCommandline());
        };
    }
}
