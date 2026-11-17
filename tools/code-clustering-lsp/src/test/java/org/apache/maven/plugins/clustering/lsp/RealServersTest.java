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
package org.apache.maven.plugins.clustering.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.AtomPlanes;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.lsp.ServerSpec.Unresolved;

/**
 * Each profile server end to end against the real program, when it is installed: the server is found the way the
 * profile finds it (system properties, environment, PATH), a fixture file is atomized through a session with it, and
 * the check asserts that the session answered (not the built-in fallback) and that the expected atoms came out. A
 * server that cannot be found prints {@code SKIP}. {@code -Dclustering.test.servers=<dir>} adds homes for a directory
 * laid out as {@code jdtls/}, {@code py/} (a pylsp virtual environment), {@code npm/} (pyright,
 * vscode-langservers-extracted, typescript-language-server and typescript), {@code lemminx/}, {@code rust-analyzer/},
 * {@code go/} (gopls in {@code bin/}), {@code dotnet/} (a {@code dotnet tool install --tool-path} directory with
 * csharp-ls) and {@code swift/} (a toolchain's {@code usr} directory, sourcekit-lsp in {@code bin/}); a
 * {@code clustering.lsp.*} system property given on the command line wins over these defaults (for example
 * {@code clustering.lsp.jdt.java}).
 * Argument: a scratch directory.
 */
public final class RealServersTest {
    private static final Checks CHECKS = new Checks("real language servers");
    private static final Logger LSP4J = Logger.getLogger("org.eclipse.lsp4j");
    private static final AtomicInteger UNSUPPORTED = new AtomicInteger();

    private RealServersTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/real-servers").toAbsolutePath();
        String root = System.getProperty("clustering.test.servers", "");
        if (root.isEmpty() && !Boolean.getBoolean("clustering.test.realServers")) {
            optedOut();
            return;
        }
        LSP4J.addHandler(new UnsupportedNotifications());
        Path cdt = run(dir, "cdt", "cdt", Map.of(), "geo.cpp", Fixtures.CPP, List.of("TYPE#Point", "METHOD#Point.norm(0)",
                "METHOD#dist(2)", "METHOD#distance(2)"), List.of());
        if (cdt != null) quoted(dir, cdt);
        int unsupported = UNSUPPORTED.get();
        Path jdt = run(dir, "jdt", "jdt", homes(root, Map.of("clustering.lsp.jdt.home", "jdtls",
                "clustering.lsp.jdt.data", dir.resolve("jdtls-data").toString())), "src/demo/Calc.java", Fixtures.JAVA,
                List.of("TYPE#Calc", "METHOD#Calc.Calc(1)", "METHOD#Calc.sum(1)", "METHOD#Calc.add(1)", "TYPE#Calc.Op",
                        "TYPE#Calc.Mode"), List.of());
        if (jdt != null) CHECKS.check("jdt: no 'Unsupported notification' warnings", UNSUPPORTED.get() == unsupported);
        List<String> shapes = List.of("TYPE#Shape", "METHOD#Shape.__init__(3)", "METHOD#Shape.area(1)", "METHOD#total(1)",
                "METHOD#sum_all(1)");
        run(dir, "pydev (pylsp)", "pydev", homes(root, Map.of("clustering.lsp.pydev.home", "py")), "shapes.py", Fixtures.PYTHON,
                shapes, List.of());
        run(dir, "pydev (pyright)", "pydev", homes(root, Map.of("clustering.lsp.pydev.home", "npm")), "shapes.py",
                Fixtures.PYTHON, shapes, List.of());
        web(dir, root);
        run(dir, "rust", "rust", homes(root, Map.of("clustering.lsp.rust.home", "rust-analyzer")), "src/lib.rs", Fixtures.RUST,
                List.of("TYPE#Point", "METHOD#dist(2)", "METHOD#distance(2)", "TYPE#Shape", "METHOD#Shape.area(1)"), List.of());
        languages(dir, root);
        CHECKS.finish();
    }

    /** Go, TypeScript, C# and Swift: each fixture with the files its server needs to load it as a project. */
    private static void languages(Path dir, String root) throws IOException {
        run(dir, "go", "go", homes(root, Map.of("clustering.lsp.go.home", "go")), Map.of("go.mod", Fixtures.GO_MOD,
                "geo.go", Fixtures.GO), List.of("TYPE#Point", "METHOD#Point.Norm(0)", "METHOD#Dist(2)", "METHOD#Distance(2)",
                "TYPE#Shape"), List.of());
        int unsupported = UNSUPPORTED.get();
        Path ts = run(dir, "typescript", "typescript", homes(root, Map.of("clustering.lsp.typescript.home", "npm")), Map.of(
                "geo.ts", Fixtures.TYPESCRIPT), List.of("TYPE#Point", "METHOD#Point.norm(0)", "METHOD#dist(2)",
                "METHOD#distance(2)", "TYPE#Shape", "TYPE#Mode"), List.of());
        if (ts != null) CHECKS.check("typescript: no 'Unsupported notification' warnings", UNSUPPORTED.get() == unsupported);
        run(dir, "csharp", "csharp", homes(root, Map.of("clustering.lsp.csharp.home", "dotnet")), Map.of("Demo.csproj",
                Fixtures.CSPROJ, "Calc.cs", Fixtures.CSHARP), List.of("TYPE#Calc", "METHOD#Calc.Sum(1)", "METHOD#Calc.Add(1)",
                "TYPE#Calc.IOp", "TYPE#Calc.Mode"), List.of());
        run(dir, "swift", "swift", homes(root, Map.of("clustering.lsp.swift.home", "swift")), Map.of("Geo.swift",
                Fixtures.SWIFT), List.of("TYPE#Point", "METHOD#dist(2)", "METHOD#distance(2)", "TYPE#Shape"), List.of());
    }

    /**
     * Real-server checks are opt-in: whatever language servers a machine happens to have on its PATH (a CI runner's
     * rust-analyzer proxy, for example) must not decide the build. Without -Dclustering.test.servers=&lt;dir&gt; or
     * -Dclustering.test.realServers=true every server is skipped with the reason.
     */
    private static void optedOut() {
        String why = "opt-in: set -Dclustering.test.servers=<dir> or -Dclustering.test.realServers=true";
        List.of("cdt", "jdt", "pydev (pylsp)", "pydev (pyright)", "wtp.xml", "wtp.html", "wtp.css", "wtp.json", "rust", "go",
                "typescript", "csharp", "swift").forEach(label -> CHECKS.skip(label, why));
        CHECKS.finish();
    }

    private static void web(Path dir, String root) throws IOException {
        run(dir, "wtp.xml", "wtp.xml", homes(root, Map.of("clustering.lsp.wtp.xml.home", "lemminx")), "project.xml",
                Fixtures.XML, List.of("TYPE#project", "TYPE#project.dependencies.dependency", "TYPE#project.build.plugins"),
                List.of("TYPE#project.name", "TYPE#project.dependencies.dependency.groupId", "TYPE#project.build.plugins.plugin"));
        Map<String, String> npm = homes(root, Map.of("clustering.lsp.wtp.home", "npm"));
        run(dir, "wtp.html", "wtp.html", npm, "page.html", Fixtures.HTML, List.of("METHOD#greet(1)", "TYPE#html",
                "TYPE#body.div#main.card", "TYPE#body.script"), List.of("TYPE#html.head.title", "TYPE#ul.li"));
        run(dir, "wtp.css", "wtp.css", npm, "site.css", Fixtures.CSS, List.of("TYPE#body", "TYPE#.card", "TYPE#.card .title"),
                List.of("TYPE#card"));
        run(dir, "wtp.json", "wtp.json", npm, "package.json", Fixtures.JSON, List.of("TYPE#scripts", "TYPE#dependencies"),
                List.of("TYPE#files"));
    }

    /**
     * One server, one file: resolve the server as its profile does, atomize, check the session and the atoms.
     * Returns the server's executable, or {@code null} when it was skipped.
     */
    private static Path run(Path dir, String label, String id, Map<String, String> defaults, String file, String text,
            List<String> expected, List<String> absent) throws IOException {
        return run(dir, label, id, defaults, Map.of(file, text), expected, absent);
    }

    /** As above, writing several files (relative path to text); exactly one of them has an extension the server serves. */
    private static Path run(Path dir, String label, String id, Map<String, String> defaults, Map<String, String> files,
            List<String> expected, List<String> absent) throws IOException {
        LspServer server;
        try {
            server = ServerProfiles.server(id).resolve(lookup(defaults));
        } catch (Unresolved e) {
            CHECKS.skip(label, e.getMessage());
            return null;
        }
        Path src = writeFixtures(clean(dir.resolve(label.replaceAll("[^A-Za-z0-9.]+", "-"))), files);
        return atomize(label, server, src, expected, absent) ? Path.of(server.command().get(0)) : null;
    }

    /** Writes each file (relative path to text) under {@code src}; returns {@code src}. */
    private static Path writeFixtures(Path src, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path source = src.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue(), StandardCharsets.UTF_8);
        }
        return src;
    }

    /** Counts LSP4J's 'Unsupported notification' warnings, which a server's unknown notifications cause. */
    private static final class UnsupportedNotifications extends Handler {
        @Override public void publish(LogRecord record) {
            if (String.valueOf(record.getMessage()).contains("Unsupported notification")) UNSUPPORTED.incrementAndGet();
        }

        @Override public void flush() { }

        @Override public void close() { }
    }

    private static boolean atomize(String label, LspServer server, Path src, List<String> expected, List<String> absent)
            throws IOException {
        List<LspFrontEnd.Outcome> outcomes = new ArrayList<>();
        Map<String, LspServer> table = new HashMap<>();
        server.languageIds().keySet().forEach(ext -> table.put(ext, server));
        long start = System.nanoTime();
        List<Atom> atoms = AtomPlanes.scan(List.of(src), List.of(new LspFrontEnd(table, LspSession::start, outcomes::add)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        List<String> names = Checks.names(atoms);
        boolean ran = CHECKS.check(label + ": the server session answered (" + ms + " ms) " + server.command() + " "
                + outcomes, outcomes.size() == 1 && outcomes.get(0).ok() && outcomes.get(0).files() == 1
                && outcomes.get(0).atoms() == atoms.size());
        CHECKS.check(label + ": atoms " + names, names.containsAll(expected) && absent.stream().noneMatch(names::contains));
        return ran;
    }

    /** clangd from a quoted {@code clustering.lsp.servers} entry: its detected path, which may hold spaces. */
    private static void quoted(Path dir, Path clangd) throws IOException {
        String setting = "cpp=\"" + clangd + "\" --log=error";
        Map<String, LspServer> table = LspConfiguration.explicit(LspConfiguration.parse(setting, m -> { }),
                java.time.Duration.ofSeconds(60));
        List<LspFrontEnd.Outcome> outcomes = new ArrayList<>();
        List<Atom> atoms = AtomPlanes.scan(List.of(dir.resolve("cdt")), List.of(new LspFrontEnd(table, LspSession::start,
                outcomes::add)));
        CHECKS.check("cdt from a quoted servers entry " + setting + (clangd.toString().contains(" ") ? " (path with a space)"
                : "") + ": " + outcomes, outcomes.size() == 1 && outcomes.get(0).ok() && !atoms.isEmpty()
                && table.get("cpp").command().equals(List.of(clangd.toString(), "--log=error")));
    }

    /**
     * The fixture directory emptied first: a server may write files there (csharp-ls's project load leaves
     * {@code obj/*.cs}), which a second run would otherwise atomize too.
     */
    private static Path clean(Path src) throws IOException {
        if (Files.isDirectory(src)) {
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        return src;
    }

    /** Defaults relative to the servers directory, used only when the system property is not set. */
    private static Map<String, String> homes(String root, Map<String, String> relative) {
        Map<String, String> defaults = new HashMap<>();
        relative.forEach((key, value) -> {
            if (key.endsWith(".home") && !root.isEmpty()) defaults.put(key, Path.of(root, value).toString());
            else if (!key.endsWith(".home")) defaults.put(key, value);
        });
        return defaults;
    }

    private static Lookup lookup(Map<String, String> defaults) {
        return new Lookup(k -> System.getProperty(k) != null ? System.getProperty(k) : defaults.get(k), System::getenv);
    }
}
