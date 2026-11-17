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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code llms.txt} (llmstxt.org layout) of a build: an H1 name, a blockquote summary, then Build, Test, Results,
 * Modules and Plugin goals sections of link lists. Rendered only from the {@link ProjectDescription}: no timestamps,
 * so an unchanged build renders the same bytes and a check can tell a stale file.
 */
final class LlmsTxtRenderer {
    /** Where agent mode writes its files, relative to the root. */
    static final String AGENT_DIR = "target/agent";

    private LlmsTxtRenderer() {}

    static String render(ProjectDescription project) {
        StringBuilder text = new StringBuilder("# ").append(project.name()).append("\n\n> ").append(summary(project))
                .append("\n");
        build(text, project.commands());
        test(text, project);
        results(text);
        modules(text, project.modules());
        goals(text, project.modules());
        return text.toString();
    }

    private static String summary(ProjectDescription project) {
        Map<String, Long> packagings = project.modules().stream().collect(Collectors.groupingBy(
                ProjectDescription.Module::packaging, TreeMap::new, Collectors.counting()));
        String counts = packagings.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        String own = project.description().isEmpty() ? "" : project.description().replaceAll("\\s+", " ") + " ";
        return own + "A Maven build of " + project.modules().size() + " module(s) (" + counts + "). The commands below"
                + " build it, run one module or one test, and leave results an agent can read without the console.";
    }

    private static void build(StringBuilder text, ProjectDescription.Commands commands) {
        text.append("\n## Build\n\n");
        text.append("- Whole build: `").append(commands.build()).append("`\n");
        text.append("- Agent mode: `").append(commands.agent()).append("` prints a short summary (every line starts with")
                .append(" `[agent]`) and writes the files under Results; it needs the build events extension in")
                .append(" `lib/ext` of the Maven distribution, in `.mvn/extensions.xml` or as")
                .append(" `-Dmaven.ext.class.path=<smart-maven-extension.jar>`\n");
        text.append("- One module and the modules it needs: `").append(commands.module()).append("`\n");
    }

    private static void test(StringBuilder text, ProjectDescription project) {
        text.append("\n## Test\n\n");
        text.append("- One test: `").append(project.commands().test()).append("`\n");
        project.modules().stream().filter(m -> !m.testRoots().isEmpty()).forEach(m -> text.append("- Tests of ")
                .append(m.id()).append(": ").append(String.join(", ", m.testRoots())).append('\n'));
    }

    private static void results(StringBuilder text) {
        text.append("\n## Results\n\n");
        link(text, "summary.txt", "the summary agent mode printed");
        link(text, "failure.md", "each failed module with its errors and their source lines, failed tests with trimmed"
                + " stacks, and the command that reruns just that failure; failure.json holds the same data");
        link(text, "build-events.jsonl", "one JSON event per line: session, project, mojo, diagnostic, test,"
                + " test-suite, repeat, session-end");
        link(text, "project.json", "this description as JSON, from `mvn agent:describe-project`");
    }

    private static void link(StringBuilder text, String file, String what) {
        text.append("- [").append(file).append("](").append(AGENT_DIR).append('/').append(file).append("): ")
                .append(what).append('\n');
    }

    private static void modules(StringBuilder text, List<ProjectDescription.Module> modules) {
        text.append("\n## Modules\n\n");
        modules.forEach(m -> text.append("- [").append(m.id()).append("](")
                .append(".".equals(m.path()) ? "" : m.path() + "/").append("pom.xml): ").append(m.packaging())
                .append(m.dependsOn().isEmpty() ? "" : "; depends on " + String.join(", ", m.dependsOn()))
                .append(m.sourceRoots().isEmpty() ? "" : "; sources " + String.join(", ", m.sourceRoots()))
                .append(m.testRoots().isEmpty() ? "" : "; tests " + String.join(", ", m.testRoots())).append('\n'));
    }

    /** Modules with the same bound goals share one line. */
    private static void goals(StringBuilder text, List<ProjectDescription.Module> modules) {
        Map<String, List<String>> byGoals = new LinkedHashMap<>();
        modules.forEach(m -> byGoals.computeIfAbsent(m.goals().stream().map(g -> g.phase() + " " + g.shortName())
                .collect(Collectors.joining(", ")), key -> new java.util.ArrayList<>()).add(m.id()));
        text.append("\n## Plugin goals\n\n");
        byGoals.forEach((goals, ids) -> text.append("- ").append(String.join(", ", ids)).append(": ")
                .append(goals.isEmpty() ? "none" : goals).append('\n'));
    }
}
