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
import java.util.ServiceConfigurationError;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The static-analyzer process: ONE {@code javax.tools} compilation with the given javac arguments, so every javac
 * plug-in ({@code -Xplugin:...}) and javac's own lint run on that compilation's AST, and every diagnostic is kept
 * ({@link JavacDiagnostics}). Usage: {@code JavacPluginHost <root> <out.jsonl> <out.sarif> -- <javac-arg>...}; source
 * files are the arguments that end in {@code .java} and do not start with '-'. Without {@code -d} the classes go to
 * a temporary folder that is deleted afterwards, never beside the sources. Exit 0 when the compilation succeeds, 1
 * when it fails with an ERROR finding, 2 when the run is incomplete (a plug-in that is missing or crashed, an option
 * javac refused, a diagnostic that could not be recorded); both reports are written in every case.
 */
public final class JavacPluginHost {
    static final String TOOL = "javac";
    static final String ABNORMAL = "javac ended abnormally without an error diagnostic (its standard error has the cause)";
    private static final System.Logger LOG = System.getLogger(JavacPluginHost.class.getName());

    private JavacPluginHost() { }

    public static void main(String[] args) throws IOException {
        System.exit(run(List.of(args)));
    }

    static int run(List<String> args) throws IOException {
        if (args.size() < 5 || !"--".equals(args.get(3))) {
            throw new IllegalArgumentException("usage: JavacPluginHost <root> <out.jsonl> <out.sarif> -- <javac-arg>...");
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        JavacDiagnostics diagnostics = new JavacDiagnostics(root);
        List<String> incomplete = new ArrayList<>();
        boolean compiled = attempt(args.subList(4, args.size()), diagnostics, incomplete);
        FindingReports.Tool tool = new FindingReports.Tool(TOOL, Runtime.version().toString(),
                "https://openjdk.org/groups/compiler/", "compiler");
        FindingReports.write(Path.of(args.get(1)), Path.of(args.get(2)), tool, root, diagnostics.findings(), incomplete);
        LOG.log(System.Logger.Level.INFO, "javac plug-in host: compiled={0}, findings={1}, incomplete={2}",
                compiled, diagnostics.findings().size(), incomplete);
        return !incomplete.isEmpty() ? 2 : compiled ? 0 : 1;
    }

    /** A plug-in that refuses its options or cannot load ends the compilation: reported, not lost. */
    private static boolean attempt(List<String> javac, JavacDiagnostics diagnostics, List<String> incomplete)
            throws IOException {
        try {
            boolean compiled = compile(javac, diagnostics);
            incomplete.addAll(diagnostics.notes());
            abnormal(compiled, diagnostics, incomplete);
            return compiled;
        } catch (RuntimeException | LinkageError | ServiceConfigurationError failure) {
            // ServiceConfigurationError/LinkageError: a plug-in jar built for a newer JDK than this one.
            incomplete.addAll(diagnostics.notes());
            incomplete.add("the compilation did not complete: " + failure);
            return false;
        }
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

    /** The compilation; without {@code -d} into a temporary class folder, deleted afterwards. */
    static boolean compile(List<String> javac, JavacDiagnostics listener) throws IOException {
        if (javac.contains("-d")) {
            return compileOnce(javac, listener);
        }
        Path scratch = Files.createTempDirectory("javac-plugin-host-");
        try {
            return compileOnce(Stream.concat(Stream.of("-d", scratch.toString()), javac.stream()).toList(), listener);
        } finally {
            delete(scratch);
        }
    }

    private static boolean compileOnce(List<String> javac, JavacDiagnostics listener) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "no system Java compiler");
        List<String> sources = javac.stream().filter(JavacPluginHost::isSource).toList();
        List<String> options = javac.stream().filter(a -> !isSource(a)).toList();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            return compiler.getTask(null, files, listener, options, null, files.getJavaFileObjectsFromStrings(sources))
                    .call();
        }
    }

    private static boolean isSource(String argument) {
        return argument.endsWith(".java") && !argument.startsWith("-");
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
