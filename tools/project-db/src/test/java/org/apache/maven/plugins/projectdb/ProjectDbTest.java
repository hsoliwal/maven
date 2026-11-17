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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.synexia.maven.polyglot.IndexStores;
import io.synexia.maven.polyglot.TaskManifest;
import io.synexia.maven.polyglot.TaskRunner;

/**
 * Module contract of the project database, run by {@code mvn verify} (exec) without a test framework: a two-module
 * fixture with real polyglot task state (TaskRunner, embedded backend) and the build data files of Surefire, the
 * dependency plugin, the code-clustering plugin, a build-events writer and a Maven log; three builds (green,
 * unchanged, red), incremental reuse, the five questions with provenance, determinism, facets, store locations,
 * the storage binding, the MCP adapter and the flip rule for flaky tests.
 */
public final class ProjectDbTest {
    private static final Pattern BUILD = Pattern.compile("b\\d{4}-[0-9a-f]{8}");
    private static int checks;

    private ProjectDbTest() {}

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args.length > 0 ? args[0] : "target/project-db-test").toAbsolutePath().normalize();
        erase(work);
        Path root = Fixture.project(work.resolve("project"));
        Path store = StoreLocation.resolve(root, null, false);
        try (ProjectDb db = ProjectDb.open(store)) {
            ingests(db, root);
            questions(db);
            determinismAndFacets(db);
            flipRule(db, root);
            removedSource(db, root);
        }
        locations(root);
        mcp(root, store);
        System.out.println("PASS: " + checks + " project database contract checks");
    }

    private static void ingests(ProjectDb db, Path root) throws Exception {
        check(db.binding().equals(IndexStores.binding(ModuleLayer.boot())), "storage binding is the IndexStore owner's");
        ProjectDb.Ingest first = db.ingest(root, Modules.discover(root), root.resolve("build1.log"));
        check(first.build().status().equals("green") && first.build().parsed() == first.build().sources(),
                "first build is green and parses every source: " + first.build());
        check(BUILD.matcher(first.build().id()).matches(), "build id format " + first.build().id());
        ProjectDb.Ingest same = db.ingest(root, Modules.discover(root), root.resolve("build1.log"));
        check(same.build().parsed() == 0 && same.build().reused() == first.build().sources(),
                "unchanged sources are never parsed again: " + same.build());
        Fixture.breakBuild(root);
        ProjectDb.Ingest red = db.ingest(root, Modules.discover(root), root.resolve("build2.log"));
        check(red.build().status().equals("red") && red.build().parsed() == 3,
                "red build re-parses only the 3 changed sources: " + red.build());
        check(db.builds().size() == 3, "three builds recorded");
    }

    private static void questions(ProjectDb db) throws IOException {
        expect(db, "why did the build fail", "test", "CalcTest#adds", "core/src/test/java/org/example/CalcTest.java");
        expect(db, "which code is duplicated", "duplicate", "copyOf", "app/src/main/java/org/example/Lists.java");
        expect(db, "what changed since the last green build", "change", "Calc.java", "core/");
        expect(db, "which dependency brings hamcrest-core", "dependency", "junit:junit", "app/target/dependency-tree.txt");
        expect(db, "which tests are flaky", "flaky", "FlakyTest#sometimes", "app/src/test/java/org/example/FlakyTest.java");
        expect(db, "polyglot task recorded by the embedded backend", "polyglot", "embedded", "core/.m3/module.m3db");
        expect(db, "event surefire test phase", "event", "phase=test", "core/target/build-events.jsonl");
    }

    /** Some of the five answers has the kind, mentions the text and comes from the file; every hit has full provenance. */
    private static void expect(ProjectDb db, String question, String kind, String mention, String file)
            throws IOException {
        Answer answer = db.query(question, 5);
        answer.lines().forEach(System.out::println);
        check(!answer.hits().isEmpty(), "answers for: " + question);
        for (Retriever.Hit hit : answer.hits()) {
            check(!hit.chunk().file().isEmpty() && hit.chunk().line() >= 1
                    && BUILD.matcher(hit.chunk().build()).matches(), "provenance of " + hit.chunk());
        }
        check(answer.hits().stream().anyMatch(hit -> hit.chunk().kind().equals(kind)
                && hit.chunk().text().contains(mention) && hit.chunk().file().startsWith(file)),
                "top answer for '" + question + "' is a " + kind + " about " + mention + " in " + file);
    }

    private static void determinismAndFacets(ProjectDb db) throws IOException {
        check(db.query("why did the build fail", 5).json().equals(db.query("why did the build fail", 5).json()),
                "the same question gives the same answer");
        Answer filtered = db.query("kind:test status:failed why did it fail", 5);
        check(!filtered.hits().isEmpty() && filtered.hits().stream().allMatch(hit -> hit.chunk().kind().equals("test")
                && hit.chunk().facet("status").equals("failed")), "exact facet filters");
        check(db.query("module:nowhere flaky", 5).hits().isEmpty(), "a facet that matches nothing gives no answer");
        check(db.query("why did the build fail", 5).json().contains("\"provenance\":{\"file\""), "JSON provenance");
        facetOnly(db);
    }

    /** A question of exact facets alone returns what the facets accept, in provenance order. */
    private static void facetOnly(ProjectDb db) throws IOException {
        Answer flaky = db.query("kind:flaky", 5);
        check(!flaky.hits().isEmpty() && flaky.hits().stream().allMatch(hit -> hit.chunk().kind().equals("flaky")),
                "kind:flaky alone answers with flaky chunks: " + flaky.lines());
        Answer core = db.query("module:core", 50);
        check(!core.hits().isEmpty() && core.hits().stream().allMatch(hit -> hit.chunk().module().equals("core")),
                "module:core alone answers with chunks of module core");
        check(!db.query("kind:dependency", 5).hits().isEmpty(), "kind:dependency alone answers");
        check(core.json().equals(db.query("module:core", 50).json()), "a facet-only answer is deterministic");
    }

    /** A test that flips between builds while its module's code atoms are identical is flaky. */
    private static void flipRule(ProjectDb db, Path root) throws Exception {
        Fixture.passAgain(root);
        ProjectDb.Ingest fourth = db.ingest(root, Modules.discover(root), root.resolve("build1.log"));
        check(fourth.build().status().equals("green"), "fourth build is green");
        Answer flaky = db.query("which tests are flaky", 10);
        check(flaky.hits().stream().anyMatch(hit -> hit.chunk().kind().equals("flaky")
                && hit.chunk().text().contains("CalcTest#adds") && hit.chunk().facet("evidence").equals("flip")),
                "an outcome flip with identical code is flaky");
    }

    /** A source that disappears leaves the catalog, re-parses nothing else and is a change since the green build. */
    private static void removedSource(ProjectDb db, Path root) throws Exception {
        int before = db.builds().get(db.builds().size() - 1).sources();
        Files.delete(root.resolve("app/target/dependency-tree.txt"));
        ProjectDb.Ingest gone = db.ingest(root, Modules.discover(root), root.resolve("build1.log"));
        check(gone.build().sources() == before - 1 && gone.build().parsed() == 0
                && gone.build().reused() == before - 1, "a removed source is dropped, nothing re-parsed: " + gone.build());
        check(db.query("which dependency brings hamcrest-core", 5).hits().stream().noneMatch(hit -> hit.chunk().kind()
                .equals("dependency") && hit.chunk().file().equals("app/target/dependency-tree.txt")),
                "a removed source answers no more dependency paths");
        check(db.query("what changed since the last green build", 10).hits().stream().anyMatch(hit -> hit.chunk().kind()
                .equals("change") && hit.chunk().text().contains("dependency-tree.txt of module app is gone")),
                "the removal is reported as gone since the last green build");
    }

    private static void locations(Path root) throws IOException {
        check(StoreLocation.resolve(root, null, false).equals(root.resolve("target/project-db")), "default store");
        check(StoreLocation.resolve(root, null, true).equals(root.resolve(".mvn/project-db")), "persistent store");
        check(StoreLocation.resolve(root, "x/db", true).equals(root.resolve("x/db")), "explicit store");
        try (ProjectDb db = ProjectDb.open(StoreLocation.resolve(root, null, true))) {
            check(db.builds().isEmpty(), "a separate store starts empty");
        }
        check(Files.readString(root.resolve(".mvn/project-db/.gitignore")).contains("*"), "store is never committed");
    }

    private static void mcp(Path root, Path store) throws Exception {
        McpServer server = new McpServer(root, store);
        check(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .contains("\"protocolVersion\":\"" + McpServer.PROTOCOL + "\""), "MCP initialize");
        check(server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").contains("project_db_query"),
                "MCP tools/list");
        String call = server.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"project_db_query\",\"arguments\":{\"question\":\"which tests are flaky\",\"k\":3}}}");
        check(call.contains("\"isError\":false") && call.contains("provenance"), "MCP tools/call: " + call);
        check(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}") == null,
                "notifications get no response");
        cli(root);
    }

    private static void cli(Path root) throws Exception {
        List<String> out = new ArrayList<>();
        ProjectDbCli.execute(new String[] {"query", root.toString(), "which dependency brings hamcrest-core"}, out::add);
        check(out.size() > 1 && out.get(1).contains("junit:junit"), "CLI query: " + out);
        check(Map.class.isInstance(Json.parse(McpTools.definitions())), "tool definitions are JSON");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    static void erase(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** The two-module fixture and its build data, written as the real producers write it. */
    static final class Fixture {
        private Fixture() {}

        static Path project(Path root) throws Exception {
            write(root, "pom.xml", pom("parent", "pom", "<modules><module>core</module><module>app</module></modules>"));
            write(root, "core/pom.xml", pom("core", "jar", ""));
            write(root, "app/pom.xml", pom("app", "jar", ""));
            sources(root);
            reports(root, "passed");
            buildData(root);
            polyglot(root.resolve("core"));
            write(root, "build1.log", "[INFO] BUILD SUCCESS\n");
            return root;
        }

        private static void sources(Path root) throws IOException {
            write(root, "core/src/main/java/org/example/Calc.java", "package org.example;\npublic class Calc {\n"
                    + "    public int add(int a, int b) {\n        return a + b;\n    }\n}\n");
            write(root, "core/src/test/java/org/example/CalcTest.java", "package org.example;\n// test\n"
                    + "public class CalcTest {\n    public void adds() {\n        check(new Calc().add(2, 2) == 4);\n    }\n}\n");
            write(root, "app/src/main/java/org/example/Lists.java", "package org.example;\nclass Lists {}\n");
            write(root, "app/src/main/java/org/example/Arrays2.java", "package org.example;\nclass Arrays2 {}\n");
            write(root, "app/src/test/java/org/example/FlakyTest.java", "package org.example;\nclass FlakyTest {}\n");
        }

        private static void buildData(Path root) throws IOException {
            write(root, "app/target/dependency-tree.txt", "org.example:app:jar:1.0\n"
                    + "+- org.example:core:jar:1.0:compile\n" + "\\- junit:junit:jar:4.13.2:test\n"
                    + "   \\- org.hamcrest:hamcrest-core:jar:1.3:test\n");
            write(root, "core/target/effective-pom.xml", "<project>\n  <dependencies>\n    <dependency>\n"
                    + "      <groupId>junit</groupId>\n      <artifactId>junit</artifactId>\n      <version>4.13.2</version>\n"
                    + "      <scope>test</scope>\n    </dependency>\n  </dependencies>\n</project>\n");
            atoms(root, "1111");
            write(root, "app/target/code-clustering/CLUSTERS.tsv", "plane\tcluster\tkind\tmembers\ttokens\tatoms\n"
                    + "EXACT\t1\tMETHOD\t2\t40\torg/example/Lists.java#copyOf(int[])|org/example/Arrays2.java#copyOf(int[])\n");
            write(root, "core/target/build-events.jsonl", "{\"type\":\"mojo\",\"phase\":\"test\",\"goal\":\"surefire:test\","
                    + "\"status\":\"ok\"}\n");
        }

        private static void atoms(Path root, String calcHash) throws IOException {
            write(root, "core/target/code-clustering/ATOM_HASHES.tsv", PLANE_HEADER
                    + "org/example/Calc.java\tMETHOD\tadd(int,int)\t20\t" + calcHash + "\tl\ts\th\t3\t5\n");
            write(root, "app/target/code-clustering/ATOM_HASHES.tsv", PLANE_HEADER
                    + "org/example/Lists.java\tTYPE\tLists\t5\te1\tl\ts\th\t2\t2\n"
                    + "org/example/Lists.java\tMETHOD\tcopyOf(int[])\t40\te2\tl\ts\th\t2\t2\n"
                    + "org/example/Arrays2.java\tMETHOD\tcopyOf(int[])\t40\te2\tl\ts\th\t2\t2\n");
        }

        private static final String PLANE_HEADER = "path\tkind\tsymbol\ttokens\texact\tlogic\tstructure\tsimhash\tline"
                + "\tend_line\n";

        /** Build 2: Calc changed (its atom), CalcTest failed, Maven reported BUILD FAILURE. */
        static void breakBuild(Path root) throws IOException {
            write(root, "core/src/main/java/org/example/Calc.java", "package org.example;\npublic class Calc {\n"
                    + "    public int add(int a, int b) {\n        return a - b;\n    }\n}\n");
            atoms(root, "2222");
            reports(root, "failed");
            write(root, "build2.log", "[INFO] BUILD FAILURE\n[ERROR] Failures: \n[ERROR]   CalcTest.adds:5 expected:<4> "
                    + "but was:<0>\n[ERROR] Tests run: 1, Failures: 1\n[ERROR] -> [Help 1]\n");
        }

        /** Build 4: the same code as build 3, and CalcTest passes again. */
        static void passAgain(Path root) throws IOException {
            reports(root, "passed");
        }

        private static void reports(Path root, String calc) throws IOException {
            String failure = "failed".equals(calc) ? "\n      <failure message=\"expected:&lt;4&gt; but was:&lt;0&gt;\" "
                    + "type=\"java.lang.AssertionError\">java.lang.AssertionError: expected:&lt;4&gt; but was:&lt;0&gt;\n"
                    + "\tat org.example.CalcTest.adds(CalcTest.java:5)\n</failure>\n    " : "";
            write(root, "core/target/surefire-reports/TEST-org.example.CalcTest.xml", "<?xml version=\"1.0\"?>\n"
                    + "<testsuite name=\"org.example.CalcTest\" tests=\"1\">\n  <testcase name=\"adds\" "
                    + "classname=\"org.example.CalcTest\" time=\"0.01\">" + failure + "</testcase>\n</testsuite>\n");
            write(root, "app/target/surefire-reports/TEST-org.example.FlakyTest.xml", "<?xml version=\"1.0\"?>\n"
                    + "<testsuite name=\"org.example.FlakyTest\" tests=\"1\" flakes=\"1\">\n  <testcase name=\"sometimes\" "
                    + "classname=\"org.example.FlakyTest\" time=\"0.01\">\n    <flakyFailure message=\"first attempt\" "
                    + "type=\"java.lang.AssertionError\">\n      <stackTrace>java.lang.AssertionError: first attempt\n"
                    + "\tat org.example.FlakyTest.sometimes(FlakyTest.java:2)\n</stackTrace>\n    </flakyFailure>\n"
                    + "  </testcase>\n</testsuite>\n");
        }

        /** Real polyglot task state: one task run through TaskRunner with the default embedded backend. */
        private static void polyglot(Path module) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            write(module, "in.txt", "input");
            write(module, "Copy.java", "import java.nio.file.*;\npublic class Copy { public static void main(String[] a)"
                    + " throws Exception { Files.copy(Path.of(a[0]), Path.of(a[1]), StandardCopyOption.REPLACE_EXISTING); } }\n");
            Path manifest = write(module, "m3-build.tsv", TaskManifest.MAGIC + "\ntask\tcopy\t" + java + "\t.\n"
                    + "arg\tcopy\tCopy.java\narg\tcopy\tin.txt\narg\tcopy\tout.txt\n"
                    + "input\tcopy\tCopy.java\ninput\tcopy\tin.txt\noutput\tcopy\tout.txt\n");
            TaskRunner.run(TaskManifest.load(manifest), module, "copy", false, Duration.ofSeconds(120), false, true,
                    "embedded", line -> { });
            check(Files.isRegularFile(module.resolve(".m3/module.m3db")), "real polyglot module database written");
        }

        private static String pom(String artifact, String packaging, String extra) {
            return "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>" + artifact
                    + "</artifactId><version>1.0</version><packaging>" + packaging + "</packaging>" + extra + "</project>\n";
        }

        private static Path write(Path root, String path, String text) throws IOException {
            Path file = root.resolve(path);
            Files.createDirectories(file.getParent());
            return Files.writeString(file, text, StandardCharsets.UTF_8);
        }
    }
}
