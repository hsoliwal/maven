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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The static-analyzer process: ONE {@code javax.tools} compilation with the given javac arguments, so every javac
 * plug-in ({@code -Xplugin:...}) and javac's own lint run on that compilation's AST, and every diagnostic is kept.
 * Usage: {@code JavacPluginHost <root> <out.jsonl> <out.sarif> -- <javac-arg>...}; source files are the arguments
 * that end in {@code .java} and do not start with '-'. Exit 0 when the compilation succeeds, 1 when it fails.
 */
public final class JavacPluginHost {
    static final String TOOL = "javac";
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
        List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
        List<String> incomplete = new ArrayList<>();
        boolean compiled = attempt(root, args.subList(4, args.size()), findings, incomplete);
        FindingReports.Tool tool = new FindingReports.Tool(TOOL, Runtime.version().toString(),
                "https://openjdk.org/groups/compiler/", "compiler");
        FindingReports.write(Path.of(args.get(1)), Path.of(args.get(2)), tool, root, List.copyOf(findings), incomplete);
        LOG.log(System.Logger.Level.INFO, "javac plug-in host: compiled={0}, findings={1}, incomplete={2}",
                compiled, findings.size(), incomplete);
        return !incomplete.isEmpty() ? 2 : compiled ? 0 : 1;
    }

    /** A plug-in that refuses its options or cannot load ends the compilation: reported, not lost. */
    private static boolean attempt(Path root, List<String> javac, List<Finding> findings, List<String> incomplete)
            throws IOException {
        try {
            boolean compiled = compile(root, javac, findings);
            abnormal(compiled, findings, incomplete);
            return compiled;
        } catch (RuntimeException | LinkageError | ServiceConfigurationError failure) {
            // ServiceConfigurationError/LinkageError: a plug-in jar built for a newer JDK than this one.
            incomplete.add("the compilation did not complete: " + failure);
            return false;
        }
    }

    /**
     * javac catches a crashing plug-in itself (JDK 17 loading Java 21 Error Prone classes: "An exception has occurred
     * in the compiler"), prints it on standard error and fails without an ERROR diagnostic; that is no clean result.
     */
    private static void abnormal(boolean compiled, List<Finding> findings, List<String> incomplete) {
        if (!compiled && findings.stream().noneMatch(f -> f.level() == Finding.Level.ERROR)) {
            incomplete.add("javac ended abnormally without an error diagnostic (its standard error has the cause)");
        }
    }

    static boolean compile(Path root, List<String> javac, List<Finding> findings) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "no system Java compiler");
        List<String> sources = javac.stream().filter(JavacPluginHost::isSource).toList();
        List<String> options = javac.stream().filter(a -> !isSource(a)).toList();
        DiagnosticListener<JavaFileObject> listener = d -> findings.add(finding(root, d));
        try (StandardJavaFileManager files = compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            return compiler.getTask(null, files, listener, options, null, files.getJavaFileObjectsFromStrings(sources))
                    .call();
        }
    }

    private static boolean isSource(String argument) {
        return argument.endsWith(".java") && !argument.startsWith("-");
    }

    /** Plug-in messages start with {@code [CheckName]}: that is the rule; otherwise javac's diagnostic code is. */
    static Finding finding(Path root, Diagnostic<? extends JavaFileObject> d) {
        String message = d.getMessage(Locale.ROOT);
        int close = message.indexOf(']');
        boolean plugin = message.startsWith("[") && close > 1;
        String rule = plugin ? message.substring(1, close) : Objects.requireNonNullElse(d.getCode(), TOOL);
        String text = plugin ? message.substring(close + 1).strip() : message;
        String file = d.getSource() == null ? "" : FindingReports.relative(root, Path.of(d.getSource().toUri()));
        return new Finding(file, d.getLineNumber(), d.getColumnNumber(), level(d.getKind()), rule, text);
    }

    static Finding.Level level(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Finding.Level.ERROR;
            case WARNING, MANDATORY_WARNING -> Finding.Level.WARNING;
            default -> Finding.Level.NOTE;
        };
    }
}
