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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The report writer (SARIF 2.1.0 and build-events lines: shape, URIs, order-independent bytes) and two in-process
 * javac plug-in host compilations: javac lint with classes written, and a crashing javac plug-in (JDK only).
 */
public final class FindingReportsContractTestMain {
    private static int passed;

    private FindingReportsContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path ws = Files.createTempDirectory("finding-reports-").toRealPath();
        try {
            reports(ws);
            surrogatesBecomeReplacementCharacters(ws);
            javacHostCompiles(ws);
            javacHostReportsCrashedPlugin(ws);
            System.out.println("PASS: " + passed + " report writer and javac host contract cases");
        } finally {
            delete(ws);
        }
    }

    private static void reports(Path ws) throws Exception {
        Path root = ws.resolve("root dir");
        List<Finding> findings = new ArrayList<>(List.of(
                new Finding("src/a b.c", 3, 7, Finding.Level.WARNING, "-Wunused-variable", "unused \"x\"\n"),
                new Finding(ws.resolve("elsewhere.c").toUri().toString(), 1, 0, Finding.Level.ERROR, "e1", "outside"),
                new Finding("", 0, 0, Finding.Level.NOTE, "", "no location")));
        var tool = new FindingReports.Tool("clangd", "22.1.0", "", "linter");
        FindingReports.write(ws.resolve("r1.jsonl"), ws.resolve("r1.sarif"), tool, root, findings, List.of("a.c: no answer"));
        Collections.reverse(findings);
        FindingReports.write(ws.resolve("r2.jsonl"), ws.resolve("r2.sarif"), tool, root, findings, List.of("a.c: no answer"));
        reportBytes(ws);
    }

    private static void reportBytes(Path ws) throws Exception {
        String sarif = Files.readString(ws.resolve("r1.sarif"), StandardCharsets.UTF_8);
        require(sarif.equals(Files.readString(ws.resolve("r2.sarif"), StandardCharsets.UTF_8)) && Files.readString(
                ws.resolve("r1.jsonl")).equals(Files.readString(ws.resolve("r2.jsonl"))), "order-independent bytes");
        require(sarif.contains("\"uri\":\"src/a%20b.c\",\"uriBaseId\":\"SRCROOT\"") && sarif.contains("\"executionSuccessful\":false")
                && sarif.contains("unused \\\"x\\\"\\n") && sarif.contains("\"ruleId\":\"unnamed\"")
                && sarif.contains("\"startLine\":1}"), "SARIF shape: " + sarif);
        require(Files.readAllLines(ws.resolve("r1.jsonl")).get(0).startsWith("{\"v\":1,\"seq\":1,\"tMs\":0,\"type\":\"diagnostic\""),
                "build-events diagnostic line");
        passed++;
    }

    /** Unpaired UTF-16 surrogates (a language server's message, a file name) become U+FFFD; valid pairs stay. */
    private static void surrogatesBecomeReplacementCharacters(Path ws) throws Exception {
        String r = String.valueOf((char) 0xFFFD);
        String pair = new String(Character.toChars(0x1F600));
        Finding finding = new Finding("src/s" + (char) 0xDC01 + ".c", 1, 1, Finding.Level.WARNING, "r" + (char) 0xD801,
                "bad " + (char) 0xD800 + " text " + (char) 0xDC00 + "x " + pair);
        var tool = new FindingReports.Tool("srv" + (char) 0xD800, "1", "", "linter");
        FindingReports.write(ws.resolve("s.jsonl"), ws.resolve("s.sarif"), tool, ws, List.of(finding),
                List.of("note " + (char) 0xDBFF));
        String sarif = Files.readString(ws.resolve("s.sarif"), StandardCharsets.UTF_8);
        require(sarif.contains("bad " + r + " text " + r + "x " + pair) && sarif.contains("note " + r)
                && Files.readString(ws.resolve("s.jsonl"), StandardCharsets.UTF_8).contains("bad " + r),
                "well-formed UTF-8 with U+FFFD for each lone surrogate: " + sarif);
        require(FindingReports.quote(String.valueOf((char) 0xD800)).equals("\"" + r + "\""), "quote replaces");
        passed++;
    }

    /** One real compilation inside this JVM: javac's own lint is the analyzer, classes are written by the same parse. */
    private static void javacHostCompiles(Path ws) throws Exception {
        Path source = ws.resolve("host/src/demo/Raw.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\nimport java.util.*;\npublic class Raw {\n"
                + "    public List names() { List list = new ArrayList(); list.add(\"x\"); return list; }\n}\n");
        int exit = JavacPluginHost.run(List.of(ws.resolve("host").toString(), ws.resolve("host/out/d.jsonl").toString(),
                ws.resolve("host/out/d.sarif").toString(), "--", "-Xlint:all", "-d", ws.resolve("host/classes").toString(),
                source.toString()));
        String sarif = Files.readString(ws.resolve("host/out/d.sarif"), StandardCharsets.UTF_8);
        require(exit == 0 && Files.isRegularFile(ws.resolve("host/classes/demo/Raw.class"))
                && sarif.contains("\"ruleId\":\"compiler.warn.raw.class.use\"") && sarif.contains("\"uri\":\"src/demo/Raw.java\""),
                "one compile wrote classes and javac lint findings: exit " + exit + " " + sarif);
        passed++;
    }

    /** A javac plug-in that throws in init: javac fails without an ERROR diagnostic; the host must not report clean. */
    private static void javacHostReportsCrashedPlugin(Path ws) throws Exception {
        Path plugin = crashPlugin(ws);
        int exit = JavacPluginHost.run(List.of(ws.resolve("host").toString(), ws.resolve("crash/d.jsonl").toString(),
                ws.resolve("crash/d.sarif").toString(), "--", "-processorpath", plugin.toString(), "-Xplugin:Crash", "-d",
                ws.resolve("crash/classes").toString(), ws.resolve("host/src/demo/Raw.java").toString()));
        require(exit == 2 && Files.readString(ws.resolve("crash/d.sarif")).contains("\"executionSuccessful\":false"),
                "a crashed plug-in is an unsuccessful invocation, exit 2: " + exit);
        passed++;
    }

    /** A processor path holding one javac plug-in, "Crash", whose init throws. */
    private static Path crashPlugin(Path ws) throws IOException {
        Path plugin = ws.resolve("crash/pp");
        Path source = ws.resolve("crash/src/crash/CrashPlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package crash;\npublic class CrashPlugin implements com.sun.source.util.Plugin {\n"
                + "    public String getName() { return \"Crash\"; }\n    public void init(com.sun.source.util.JavacTask t,"
                + " String... a) { throw new IllegalStateException(\"plug-in crash\"); }\n}\n");
        require(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", plugin.toString(),
                source.toString()) == 0, "plug-in compiles");
        Files.createDirectories(plugin.resolve("META-INF/services"));
        Files.writeString(plugin.resolve("META-INF/services/com.sun.source.util.Plugin"), "crash.CrashPlugin\n");
        return plugin;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
