/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Native test results as surefire reports, for language {@code trx} (Visual Studio TRX, as written by
 * {@code dotnet test --logger trx}) and {@code libtest} (the output of {@code cargo test}). Every form runs
 * {@link SurefireReportMain} from this plugin's own jar on the running JDK:
 * <ul>
 *   <li>{@code @m3:surefire:transpiler:trx} with arguments {@code <results> <reports>} converts the TRX files of
 *       the results folder (or one TRX file) into {@code TEST-<class>.xml} files in the reports folder;</li>
 *   <li>{@code @m3:surefire:interpreter:trx} with {@code <results> <reports> <tool> <argument>...} first removes
 *       old TRX files from the results folder, runs the test tool, converts what it wrote, and ends with the
 *       tool's exit status, so failing tests still get their reports, as with surefire;</li>
 *   <li>{@code @m3:surefire:transpiler:junit} and {@code @m3:surefire:interpreter:junit} do the same for the
 *       JUnit XML that CTest writes, normalised ({@link JUnitReport});</li>
 *   <li>{@code @m3:surefire:interpreter:libtest} with {@code <reports> <tool> <argument>...} runs the tool (cargo
 *       test), echoes its output and writes one report per test binary ({@link CargoTestReport}); libtest output
 *       is no file, so it has no transpiler form.</li>
 * </ul>
 * Both paths are relative to the task's working folder and may not leave it.
 */
public final class SurefireReportProvider implements M3ToolProvider {
    @Override
    public String id() {
        return "surefire";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.TRANSPILER, M3ToolCapability.INTERPRETER);
    }

    @Override
    public Set<String> languages() {
        return Set.of(SurefireReportMain.TRX, SurefireReportMain.JUNIT, SurefireReportMain.LIBTEST);
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        List<String> args = task.arguments();
        boolean run = capability == M3ToolCapability.INTERPRETER;
        int paths = SurefireReportMain.LIBTEST.equals(language) ? 1 : 2;
        if ((!run && paths == 1) || (run ? args.size() < paths + 1 : args.size() != paths)) {
            throw new IOException(paths == 1 ? "libtest reports need the interpreter form <reports> <tool> <argument>..."
                    : "surefire reports need <results> <reports>" + (run ? " <tool> <argument>..." : ""));
        }
        for (String path : args.subList(0, paths)) relative(path);
        return launch(language, args);
    }

    /** The command that runs {@link SurefireReportMain} in a JVM of the running JDK: the format, then its arguments. */
    static List<String> launch(String format, List<String> args) throws IOException {
        List<String> argv = new ArrayList<>(List.of(EcjCompilerProvider.java(), "-cp", codeSource(),
                SurefireReportMain.class.getName(), format));
        argv.addAll(args);
        return List.copyOf(argv);
    }

    /** A results or reports path: relative, without '..', and not the working folder itself. */
    static String relative(String path) throws IOException {
        Path value = Path.of(path).normalize();
        if (value.isAbsolute() || value.toString().isEmpty() || value.startsWith("..")) {
            throw new IOException("surefire report path must stay inside the working folder: " + path);
        }
        return path;
    }

    /** This plugin's jar (or class folder), which holds the converter and needs nothing else. */
    static String codeSource() throws IOException {
        CodeSource source = SurefireReportProvider.class.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("cannot locate the polyglot plugin jar");
        try {
            return Path.of(source.getLocation().toURI()).toString();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("cannot locate the polyglot plugin jar: " + source.getLocation(), failure);
        }
    }
}
