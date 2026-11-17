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
package org.apache.maven.plugins.agent;

import java.util.List;
import java.util.Objects;

/** llms.txt and project.json of a fixed description, byte for byte (golden). */
public final class LlmsTxtRendererTestMain {
    static final String PLUGINS = "org.apache.maven.plugins:maven-";
    static final List<ProjectDescription.Goal> JAR = List.of(
            new ProjectDescription.Goal("process-resources", PLUGINS + "resources-plugin:3.3.1", "resources", "default-resources"),
            new ProjectDescription.Goal("compile", PLUGINS + "compiler-plugin:3.13.0", "compile", "default-compile"));
    static final List<ProjectDescription.Goal> TESTED = List.of(JAR.get(0), JAR.get(1),
            new ProjectDescription.Goal("test", PLUGINS + "surefire-plugin:3.2.5", "test", "default-test"));

    static final String GOLDEN = "# fixture\n\n"
            + "> Three modules. A Maven build of 4 module(s) (3 jar, 1 pom). The commands below build it, run one module"
            + " or one test, and leave results an agent can read without the console.\n\n"
            + "## Build\n\n"
            + "- Whole build: `mvn verify`\n"
            + "- Agent mode: `mvn -B -ntp -q -Dstyle.color=never -Dmaven.agent=true verify` prints a short summary (every"
            + " line starts with `[agent]`) and writes the files under Results; it needs the build events extension in"
            + " `lib/ext` of the Maven distribution, in `.mvn/extensions.xml` or as"
            + " `-Dmaven.ext.class.path=<smart-maven-extension.jar>`\n"
            + "- One module and the modules it needs: `mvn -pl <path> -am verify`\n\n"
            + "## Test\n\n"
            + "- One test: `mvn -pl <path> -am -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false test`\n"
            + "- Tests of fix:b: b/src/test/java\n\n"
            + "## Results\n\n"
            + "- [summary.txt](target/agent/summary.txt): the summary agent mode printed\n"
            + "- [failure.md](target/agent/failure.md): each failed module with its errors and their source lines, failed"
            + " tests with trimmed stacks, and the command that reruns just that failure; failure.json holds the same data\n"
            + "- [build-events.jsonl](target/agent/build-events.jsonl): one JSON event per line: session, project, mojo,"
            + " diagnostic, test, test-suite, repeat, session-end\n"
            + "- [project.json](target/agent/project.json): this description as JSON, from `mvn agent:describe-project`\n\n"
            + "## Modules\n\n"
            + "- [fix:root](pom.xml): pom\n"
            + "- [fix:a](a/pom.xml): jar; sources a/src/main/java\n"
            + "- [fix:b](b/pom.xml): jar; depends on fix:a; sources b/src/main/java; tests b/src/test/java\n"
            + "- [fix:c](c/pom.xml): jar; sources c/src/main/java\n\n"
            + "## Plugin goals\n\n"
            + "- fix:root: none\n"
            + "- fix:a, fix:c: process-resources resources:resources, compile compiler:compile\n"
            + "- fix:b: process-resources resources:resources, compile compiler:compile, test surefire:test\n";

    private LlmsTxtRendererTestMain() {}

    static ProjectDescription fixture() {
        List<ProjectDescription.Module> modules = List.of(
                new ProjectDescription.Module(1, "fix:root", ".", "pom", "1.0-SNAPSHOT", List.of(), List.of(), List.of(), List.of()),
                new ProjectDescription.Module(2, "fix:a", "a", "jar", "1.0-SNAPSHOT", List.of(), List.of("a/src/main/java"),
                        List.of(), JAR),
                new ProjectDescription.Module(3, "fix:b", "b", "jar", "1.0-SNAPSHOT", List.of("fix:a"),
                        List.of("b/src/main/java"), List.of("b/src/test/java"), TESTED),
                new ProjectDescription.Module(4, "fix:c", "c", "jar", "1.0-SNAPSHOT", List.of(), List.of("c/src/main/java"),
                        List.of(), JAR));
        return new ProjectDescription("fixture", "Three modules.", modules, new ProjectDescription.Commands("mvn verify",
                "mvn -B -ntp -q -Dstyle.color=never -Dmaven.agent=true verify", "mvn -pl <path> -am verify",
                "mvn -pl <path> -am -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false test"));
    }

    public static void main(String[] args) {
        String text = LlmsTxtRenderer.render(fixture());
        equal(GOLDEN, text, "llms.txt golden");
        equal(text, LlmsTxtRenderer.render(fixture()), "the same description renders the same bytes");
        String json = fixture().json();
        equal(true, json.startsWith("{\"v\":1,\"name\":\"fixture\",\"modules\":[{\"index\":1,\"id\":\"fix:root\",\"path\":\".\","
                + "\"packaging\":\"pom\",\"version\":\"1.0-SNAPSHOT\",\"dependsOn\":[],\"sourceRoots\":[],\"testRoots\":[],"
                + "\"goals\":[]},{\"index\":2,\"id\":\"fix:a\""), "project.json field order: " + json);
        equal(true, json.contains("\"goals\":[{\"phase\":\"process-resources\",\"plugin\":\"org.apache.maven.plugins:"
                + "maven-resources-plugin:3.3.1\",\"goal\":\"resources\",\"execution\":\"default-resources\"}"), "goal fields");
        equal(true, json.endsWith("\"commands\":{\"build\":\"mvn verify\",\"agent\":\"mvn -B -ntp -q -Dstyle.color=never"
                + " -Dmaven.agent=true verify\",\"module\":\"mvn -pl <path> -am verify\",\"test\":\"mvn -pl <path> -am"
                + " -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false test\"}}\n"), "commands");
        equal(false, json.contains("Three modules."), "the POM description is not part of project.json");
        equal("exec:java", new ProjectDescription.Goal("x", "org.codehaus.mojo:exec-maven-plugin:3.5.0", "java", "e")
                .shortName(), "prefix of a *-maven-plugin");
        equal("odd:go", new ProjectDescription.Goal("x", "g:odd:1", "go", "e").shortName(), "an unconventional name");
        System.out.println("LlmsTxtRendererTestMain PASS");
    }

    private static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\n--- expected\n" + expected + "\n--- actual\n" + actual);
        }
    }
}
