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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The static-analyzer process: ONE {@code javax.tools} compilation with the given javac arguments, so every javac
 * plug-in ({@code -Xplugin:...}) and javac's own lint run on that compilation's AST, and every diagnostic is kept
 * ({@link JavacDiagnostics}). Usage: {@code JavacPluginHost <root> <out.jsonl> <out.sarif> -- <javac-arg>...}; the
 * arguments are read as the provider read them ({@link JavacArguments}). Patching options, {@code @argfiles} and a
 * repeated {@code -d}, {@code -s} or {@code -h} are refused before javac starts (exit 2), so a direct call cannot
 * rewrite sources either. Without {@code -d} the classes go to a temporary folder that is deleted afterwards, never
 * beside the sources. Exit 0 when the compilation succeeds, 1 when it fails with an ERROR finding, 2 when the run is
 * incomplete (refused arguments, a plug-in that is missing or crashed, an option javac refused, a diagnostic that
 * could not be recorded, reports that could not be written); both reports are written whenever the file system lets.
 */
public final class JavacPluginHost {
    static final String TOOL = "javac";
    static final String ABNORMAL = "javac ended abnormally without an error diagnostic (its standard error has the cause)";
    static final int INCOMPLETE = 2;
    private static final System.Logger LOG = System.getLogger(JavacPluginHost.class.getName());

    private JavacPluginHost() { }

    public static void main(String[] args) {
        System.exit(run(List.of(args)));
    }

    static int run(List<String> args) {
        if (args.size() < 5 || !"--".equals(args.get(3))) {
            throw new IllegalArgumentException("usage: JavacPluginHost <root> <out.jsonl> <out.sarif> -- <javac-arg>...");
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        JavacDiagnostics diagnostics = new JavacDiagnostics(root);
        List<String> incomplete = new ArrayList<>();
        boolean compiled = attempt(args.subList(4, args.size()), diagnostics, incomplete);
        boolean written = report(args, root, diagnostics.findings(), incomplete);
        LOG.log(System.Logger.Level.INFO, "javac plug-in host: compiled={0}, findings={1}, incomplete={2}",
                compiled, diagnostics.findings().size(), incomplete);
        return !written || !incomplete.isEmpty() ? INCOMPLETE : compiled ? 0 : 1;
    }

    /** Both reports; a write failure (disk full, a folder in the way) is logged and makes the run incomplete. */
    private static boolean report(List<String> args, Path root, List<Finding> findings, List<String> incomplete) {
        FindingReports.Tool tool = new FindingReports.Tool(TOOL, Runtime.version().toString(),
                "https://openjdk.org/groups/compiler/", "compiler");
        try {
            FindingReports.write(Path.of(args.get(1)), Path.of(args.get(2)), tool, root, findings, incomplete);
            return true;
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "javac plug-in host: cannot write the reports: {0}", failure.toString());
            return false;
        }
    }

    /** Refused arguments, and a plug-in that refuses its options or cannot load, end the run: reported, not lost. */
    private static boolean attempt(List<String> javac, JavacDiagnostics diagnostics, List<String> incomplete) {
        try {
            JavacArguments arguments = JavacArguments.parse(javac);
            Optional<String> refusal = arguments.refusal();
            refusal.ifPresent(incomplete::add);
            return refusal.isEmpty() && compiled(arguments, diagnostics, incomplete);
        } catch (IOException | RuntimeException | LinkageError | ServiceConfigurationError failure) {
            // ServiceConfigurationError/LinkageError: a plug-in jar built for a newer JDK than this one.
            incomplete.addAll(diagnostics.notes());
            incomplete.add("the compilation did not complete: " + failure);
            return false;
        }
    }

    private static boolean compiled(JavacArguments arguments, JavacDiagnostics diagnostics, List<String> incomplete)
            throws IOException {
        boolean compiled = compile(arguments, diagnostics);
        incomplete.addAll(diagnostics.notes());
        abnormal(compiled, diagnostics, incomplete);
        return compiled;
    }

    /**
     * javac catches a crashing plug-in itself (JDK 17 loading Java 21 Error Prone classes: "An exception has occurred
     * in the compiler"), prints it on standard error and fails without an ERROR diagnostic; that is no clean result.
     */
    private static void abnormal(boolean compiled, JavacDiagnostics diagnostics, List<String> incomplete) {
        if (!compiled && incomplete.isEmpty()
                && diagnostics.findings().stream().noneMatch(f -> f.level() == Finding.Level.ERROR)) {
            incomplete.add(ABNORMAL);
        }
    }

    /** The compilation; without a {@code -d} option into a temporary class folder, deleted afterwards. */
    static boolean compile(JavacArguments javac, JavacDiagnostics listener) throws IOException {
        if (!javac.values("-d").isEmpty()) {
            return compileOnce(javac.options(), javac, listener);
        }
        Path scratch = Files.createTempDirectory("javac-plugin-host-");
        try {
            return compileOnce(Stream.concat(Stream.of("-d", scratch.toString()), javac.options().stream()).toList(),
                    javac, listener);
        } finally {
            delete(scratch);
        }
    }

    private static boolean compileOnce(List<String> options, JavacArguments javac, JavacDiagnostics listener)
            throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "no system Java compiler");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            return compiler.getTask(null, files, listener, options, javac.classes().isEmpty() ? null : javac.classes(),
                    files.getJavaFileObjectsFromStrings(javac.sources())).call();
        }
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
