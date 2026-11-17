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
package org.apache.maven.agent.events;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Test results of Surefire {@code test} and Failsafe {@code integration-test} runs: after each run its fresh reports
 * become {@code test} events (failed and errored tests, or all with {@code maven.agent.tests=all}) and one
 * {@code test-suite} event per class; failed tests are kept for the digest.
 */
final class TestSink {
    private final AgentSettings settings;
    private final EventWriter writer;
    private final List<TestResult> failed = new ArrayList<>();
    private int run;
    private int failures;
    private int errors;
    private int skipped;

    TestSink(AgentSettings settings, EventWriter writer) {
        this.settings = settings;
        this.writer = writer;
    }

    /** Whether {@code mojo} runs tests whose reports this sink reads. */
    static boolean testMojo(MojoExecution mojo) {
        return "maven-surefire-plugin".equals(mojo.getArtifactId()) && "test".equals(mojo.getGoal())
                || "maven-failsafe-plugin".equals(mojo.getArtifactId()) && "integration-test".equals(mojo.getGoal());
    }

    /** Reads the reports {@code mojo} wrote since {@code startedMillis}. */
    void collect(MavenSession session, MavenProject project, MojoExecution mojo, long startedMillis) {
        String id = ReactorIndex.id(project);
        SurefireReports.read(reportsDirectory(session, project, mojo), startedMillis)
                .forEach(suite -> suite(id, project, suite));
    }

    private void suite(String id, MavenProject project, SurefireReports.Suite suite) {
        run += suite.tests();
        failures += suite.failures();
        errors += suite.errors();
        skipped += suite.skipped();
        suite.cases().stream().filter(c -> settings.allTests() || c.failed()).map(c -> result(id, project, c))
                .forEach(result -> {
                    writer.emit("test", result.json());
                    if (result.test().failed()) {
                        failed.add(result);
                    }
                });
        writer.emit("test-suite", new JsonText.Obj().put("project", id).put("class", suite.className())
                .put("tests", suite.tests()).put("failures", suite.failures()).put("errors", suite.errors())
                .put("skipped", suite.skipped()).put("durationMs", suite.durationMs()));
    }

    private TestResult result(String id, MavenProject project, SurefireReports.TestCase test) {
        List<String> stack = StackTrimmer.trim(test.trace(), StackTrimmer.appPackage(test.className()),
                settings.stackLines()).stream().map(line -> Redact.clean(line, settings.root(), settings.messageChars()))
                .collect(Collectors.toList());
        Optional<Frame> frame = frame(test);
        String file = frame.flatMap(f -> source(project, test.className(), f.file())).map(settings::relative).orElse("");
        return new TestResult(id, test, Redact.clean(test.message(), settings.root(), settings.messageChars()), stack,
                file, frame.map(Frame::line).orElse(0));
    }

    private record Frame(String file, int line) {}

    /** The first frame of the test class itself ({@code at a.BTest.adds(BTest.java:12)}). */
    private static Optional<Frame> frame(SurefireReports.TestCase test) {
        Matcher m = Pattern.compile("at " + Pattern.quote(test.className())
                + "(?:\\$[\\w$]+)?\\.[^(\\s]+\\(([^:()]+):(\\d{1,9})\\)").matcher(test.trace());
        return m.find() ? Optional.of(new Frame(m.group(1), Integer.parseInt(m.group(2)))) : Optional.empty();
    }

    /** The source file of {@code fileName} in the class's package under the test, then main, source roots. */
    private static Optional<Path> source(MavenProject project, String className, String fileName) {
        int dot = className.lastIndexOf('.');
        String packagePath = dot < 0 ? "" : className.substring(0, dot).replace('.', '/') + "/";
        return Stream.concat(project.getTestCompileSourceRoots().stream(), project.getCompileSourceRoots().stream())
                .map(root -> Path.of(root, packagePath + fileName)).filter(Files::isRegularFile).findFirst();
    }

    /** The mojo's {@code reportsDirectory}, evaluated as Maven would; the plugin default when unset. */
    static Path reportsDirectory(MavenSession session, MavenProject project, MojoExecution mojo) {
        String fallback = "maven-failsafe-plugin".equals(mojo.getArtifactId()) ? "failsafe-reports" : "surefire-reports";
        Path standard = Path.of(project.getBuild().getDirectory(), fallback);
        Xpp3Dom configuration = mojo.getConfiguration();
        Xpp3Dom child = configuration == null ? null : configuration.getChild("reportsDirectory");
        if (child == null) {
            return standard;
        }
        PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, mojo);
        return evaluate(evaluator, child.getValue()).or(() -> evaluate(evaluator, child.getAttribute("default-value")))
                .map(path -> project.getBasedir().toPath().resolve(path)).orElse(standard);
    }

    private static Optional<Path> evaluate(PluginParameterExpressionEvaluator evaluator, String expression) {
        try {
            Object value = expression == null ? null : evaluator.evaluate(expression);
            String text = value instanceof File ? ((File) value).getPath() : value == null ? "" : value.toString();
            return text.isEmpty() || text.contains("${") ? Optional.empty() : Optional.of(Path.of(text));
        } catch (ExpressionEvaluationException | RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /** Failed and errored tests of {@code project}, in report order. */
    List<TestResult> failed(String project) {
        return failed.stream().filter(t -> t.project().equals(project)).collect(Collectors.toList());
    }

    int run() {
        return run;
    }

    int failures() {
        return failures;
    }

    int errors() {
        return errors;
    }

    int skipped() {
        return skipped;
    }

    /** The {@code tests} counts of the session-end event. */
    JsonText.Obj counts() {
        return new JsonText.Obj().put("run", run).put("failed", failures).put("errors", errors).put("skipped", skipped);
    }
}
