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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** JDK-only native Gradle report source and parser compatibility regression. */
public final class GradleSourceDiscoveryContractMain {
    private GradleSourceDiscoveryContractMain() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("m3-gradle-sources-");
        try {
            fixture(root);
            List<Path> modules = Modules.discover(root);
            check(modules.equals(List.of(root, root.resolve("app"))), "Gradle module discovery: " + modules);
            List<Source> project = Sources.discover(root, root);
            check(project.size() == 1, "one original Gradle root test report");
            check(project.get(0).path().equals("build/test-results/test/TEST-org.example.FooTest.xml"),
                    "Gradle root report retains original relative path");
            verifyCases(root, project.get(0));
            List<Source> app = Sources.discover(root, root.resolve("app"));
            check(app.size() == 1, "one Gradle integration-test report in the submodule");
            check(SurefireParser.parse(root, app.get(0)).get(0).facet("status").equals("passed"),
                    "the existing Surefire parser accepts a native Gradle passing testcase");
            System.out.println("PASS: native Gradle modules and JUnit case evidence via original Maven parsers");
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void fixture(Path root) throws Exception {
        write(root.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\ninclude(\"app\")\n");
        write(root.resolve("app/build.gradle.kts"), "plugins { java }\n");
        write(root.resolve("build/out/build.gradle"), "ignored = true\n");
        write(root.resolve("src/test/java/org/example/FooTest.java"),
                "package org.example;\n" + "\n".repeat(9) + "class FooTest {}\n");
        write(root.resolve("build/test-results/test/TEST-org.example.FooTest.xml"),
                "<testsuite>\n  <testcase classname=\"org.example.FooTest\" name=\"fails\">\n"
                        + "    <failure message=\"expected\">at org.example.FooTest.fails(FooTest.java:11)\n"
                        + "    </failure>\n  </testcase>\n</testsuite>\n");
        write(root.resolve("app/build/test-results/integrationTest/TEST-org.example.BarTest.xml"),
                "<testsuite>\n  <testcase classname=\"org.example.BarTest\" name=\"passes\"/>\n</testsuite>\n");
    }

    private static void verifyCases(Path root, Source source) throws Exception {
        List<Chunk> cases = SurefireParser.parse(root, source);
        check(cases.size() == 1, "one failed native Gradle testcase parsed");
        Chunk failed = cases.get(0);
        check(failed.facet("status").equals("failed"), "Gradle test failure remains a failure");
        check(failed.file().equals("src/test/java/org/example/FooTest.java") && failed.line() == 11,
                "Gradle source-frame file and line retain original provenance");
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void check(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
    }
}
