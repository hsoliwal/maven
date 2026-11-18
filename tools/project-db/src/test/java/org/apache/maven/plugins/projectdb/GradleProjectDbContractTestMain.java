/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** A Gradle-only Java project: native Gradle test XML, no POM and no Maven goal execution. */
public final class GradleProjectDbContractTestMain {
    private static int checks;

    private GradleProjectDbContractTestMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "target/gradle-project-db" : args[0])
                .toAbsolutePath().normalize();
        reset(root);
        fixture(root);
        List<Path> modules = Modules.discover(root);
        check(modules.equals(List.of(root, root.resolve("app"))), "Gradle modules without POMs: " + modules);
        try (ProjectDb db = ProjectDb.open(root.resolve("build/m3-project-db"))) {
            verifyIngest(db, root, modules);
            verifyQuery(db);
            verifyReingest(db, root, modules);
        }
        verifySymlinkPrunedCli(root);
        System.out.println("PASS: " + checks + " native Gradle module and JUnit evidence checks");
    }

    private static void fixture(Path root) throws Exception {
        write(root.resolve("settings.gradle.kts"), "rootProject.name = \"source-qualifier\"\ninclude(\"app\")\n");
        write(root.resolve("build.gradle.kts"), "plugins { java }\n");
        write(root.resolve("app/build.gradle"), "plugins { id 'java' }\n");
        write(root.resolve("build/generated/build.gradle"), "ignored = true\n");
        write(root.resolve("src/test/java/org/example/FooTest.java"),
                "package org.example;\n" + "\n".repeat(9)
                        + "class FooTest { void reportsFailure() {} }\n");
        write(root.resolve("build/test-results/test/TEST-org.example.FooTest.xml"), failure());
        write(root.resolve("app/build/test-results/integrationTest/TEST-org.example.BarTest.xml"),
                "<testsuite>\n  <testcase classname=\"org.example.BarTest\" name=\"passes\"/>\n</testsuite>\n");
    }

    private static String failure() {
        return "<testsuite name=\"org.example.FooTest\">\n"
                + "  <testcase classname=\"org.example.FooTest\" name=\"reportsFailure\">\n"
                + "    <failure message=\"expected failure\">at org.example.FooTest.reportsFailure(FooTest.java:11)\n"
                + "    </failure>\n  </testcase>\n</testsuite>\n";
    }

    private static void verifyIngest(ProjectDb db, Path root, List<Path> modules) throws Exception {
        List<Source> main = Sources.discover(root, root);
        check(main.size() == 1 && main.get(0).kind().equals("surefire"), "one native Gradle test source");
        check(main.get(0).path().equals("build/test-results/test/TEST-org.example.FooTest.xml"),
                "source-owned Gradle test report path");
        List<Source> app = Sources.discover(root, root.resolve("app"));
        check(app.size() == 1 && app.get(0).kind().equals("surefire"), "submodule integration tests");
        ProjectDb.Ingest first = db.ingest(root, modules, null);
        check(first.build().status().equals("red") && first.build().parsed() == 2,
                "failed Gradle test is red, both JUnit reports read exactly once: " + first.build());
    }

    private static void verifyQuery(ProjectDb db) throws Exception {
        Answer failures = db.query("which tests failed", 10);
        check(failures.hits().stream().anyMatch(hit -> hit.chunk().kind().equals("test")
                && hit.chunk().facet("status").equals("failed")
                && hit.chunk().text().contains("FooTest#reportsFailure")), "real failed Gradle test retrieved");
        check(failures.hits().stream().anyMatch(hit -> hit.chunk().file()
                .equals("src/test/java/org/example/FooTest.java")
                && hit.chunk().line() == 11), "stack frame maps to original Gradle source provenance");
        check(failures.json().contains("\"provenance\""), "Maven project DB retains provenance JSON");
    }

    private static void verifyReingest(ProjectDb db, Path root, List<Path> modules) throws Exception {
        ProjectDb.Ingest second = db.ingest(root, modules, null);
        check(second.build().parsed() == 0 && second.build().reused() == 2,
                "unchanged Gradle JUnit reports are not reparsed");
        write(root.resolve("app/build/test-results/integrationTest/TEST-org.example.BarTest.xml"),
                "<testsuite>\n  <testcase classname=\"org.example.BarTest\" name=\"alsoPasses\"/>\n</testsuite>\n");
        ProjectDb.Ingest modified = db.ingest(root, modules, null);
        check(modified.build().parsed() == 1 && modified.build().reused() == 1,
                "only changed native Gradle report is reparsed");
    }

    /**
     * The actual standalone Maven CLI must keep a symlinked regular Gradle
     * descriptor, reject module candidates in vendor/output subtrees, retain
     * file:line provenance, and reuse the exact original content generation.
     */
    private static void verifySymlinkPrunedCli(Path sibling) throws Exception {
        Path root = sibling.resolveSibling("gradle-pruned-symlink-cli-test");
        reset(root);
        write(root.resolve("settings.gradle"), "rootProject.name='m3-linked'\n");
        write(root.resolve("build.gradle"), "// Gradle root\n");
        Path descriptor = root.resolve("descriptor.gradle");
        write(descriptor, "// canonical build descriptor\n");
        Path link = root.resolve("linked/build.gradle");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, descriptor);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                throw new AssertionError("Linux CI must support the symlink fixture", unavailable);
            }
            System.out.println("SKIP: linked descriptor unavailable on this filesystem: " + unavailable);
            return;
        }
        check(Files.isSymbolicLink(link) && Files.isRegularFile(link),
                "the linked module descriptor must remain a real symlink to a regular file");
        failedReport(root.resolve("linked"), "LinkedTest");
        for (String excluded : List.of("node_modules/ignored", "target/ignored", "build/ignored")) {
            Path module = root.resolve(excluded);
            write(module.resolve("build.gradle"), "// generated or vendor output\n");
            failedReport(module, "ExcludedTest");
        }
        List<Path> modules = Modules.discover(root);
        check(modules.equals(List.of(root, root.resolve("linked"))),
                "source-owned Gradle modules exclude vendor/output and keep linked descriptor: " + modules);
        Path store = root.resolve("build/m3/project-db");
        List<String> ingest = cli("ingest", root.toString(), "--store", store.toString());
        check(ingest.size() == 1 && ingest.get(0).contains("\"sources\":1")
                && ingest.get(0).contains("\"parsed\":1")
                && ingest.get(0).contains("\"status\":\"red\""),
                "real Maven CLI must ingest only one linked test source: " + ingest);
        List<String> answer = cli("query", root.toString(), "LinkedTest failed in linked module",
                "--store", store.toString(), "--k", "5", "--json");
        check(answer.size() == 1 && answer.get(0).contains("\"status\":\"failed\"")
                && answer.get(0).contains("\"file\":\"linked/src/test/java/org/example/LinkedTest.java\"")
                && answer.get(0).contains("\"line\":11"), "source-line provenance from real Maven CLI: " + answer);
        check(!answer.get(0).contains("ExcludedTest"), "vendor/output sources never enter retrieval");
        List<String> warm = cli("ingest", root.toString(), "--store", store.toString());
        check(warm.size() == 1 && warm.get(0).contains("\"parsed\":0")
                && warm.get(0).contains("\"reused\":1"), "unchanged linked report must be reused: " + warm);
        write(root.resolve("node_modules/ignored/build.gradle"), "updated vendor build\n");
        List<String> unrelated = cli("ingest", root.toString(), "--store", store.toString());
        check(unrelated.size() == 1 && unrelated.get(0).contains("\"parsed\":0")
                && unrelated.get(0).contains("\"reused\":1"),
                "mutating ignored vendor descriptors must not invalidate a linked module: " + unrelated);
    }

    private static List<String> cli(String... args) throws Exception {
        List<String> lines = new ArrayList<>();
        ProjectDbCli.execute(args, lines::add);
        return lines;
    }

    private static void failedReport(Path module, String test) throws Exception {
        Path java = module.resolve("src/test/java/org/example/" + test + ".java");
        write(java, "package org.example;\n" + "\n".repeat(9)
                + "class " + test + " { void fails() {} }\n");
        Path report = module.resolve("build/test-results/test/TEST-org.example." + test + ".xml");
        write(report, "<testsuite name=\"org.example." + test + "\">\n"
                + "  <testcase classname=\"org.example." + test + "\" name=\"fails\">\n"
                + "    <failure message=\"failed\">at org.example." + test + ".fails(" + test + ".java:11)\n"
                + "    </failure>\n  </testcase>\n</testsuite>\n");
    }

    private static void write(Path file, String data) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, data);
    }

    private static void reset(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
