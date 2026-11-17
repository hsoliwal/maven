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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;

/**
 * A POM failure keeps its located model problems: each becomes a diagnostic (source model, file, line, column), and
 * the digest, its reproduce command and the summary name them instead of only the exception's first line.
 */
public final class ModelProblemsTestMain {
    private static final String MISSING = "'dependencies.dependency.version' for no:such:jar is missing.";

    private ModelProblemsTestMain() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("model-problems");
        Path pom = Files.createDirectories(root.resolve("b")).resolve("pom.xml");
        Files.writeString(pom, String.join("\n", Collections.nCopies(14, "<!-- line -->")) + "\n");
        ProjectBuildingException failure = new ProjectBuildingException(List.of(result(pom)));
        unwrapped(failure, pom);
        digest(root, failure);
        Check.done("ModelProblemsTestMain");
    }

    private static void unwrapped(ProjectBuildingException failure, Path pom) {
        List<DiagnosticLines.Raw> raws = ModelProblems.of(new IllegalStateException("wrapped", failure));
        Check.equal(2, raws.size(), "both problems");
        DiagnosticLines.Raw error = raws.get(0);
        Check.equal(List.of("ERROR", pom.toString(), 12, 17, MISSING),
                List.of(error.severity(), error.file(), error.line(), error.column(), error.message()), "located error");
        Check.equal("WARNING", raws.get(1).severity(), "warning kept as warning");
        Check.that(ModelProblems.of(new IOException("not a model failure")).isEmpty(), "other failures");
    }

    private static void digest(Path root, ProjectBuildingException failure) throws IOException {
        Properties user = new Properties();
        user.setProperty("maven.agent", "true");
        user.setProperty("maven.events.stdout", "none");
        MavenExecutionRequest request = new DefaultMavenExecutionRequest().setPom(root.resolve("pom.xml").toFile())
                .setGoals(List.of("verify")).setUserProperties(user).setSystemProperties(new Properties());
        AgentSettings settings = AgentSettings.of(user, new Properties(), root);
        new BuildRecorder(settings, EventWriter.open(line -> {}), request).finish(List.of(failure));
        String json = Files.readString(root.resolve("target/agent/failure.json"), StandardCharsets.UTF_8);
        Check.that(json.contains("\"kind\":\"model\"") && json.contains("\"file\":\"b/pom.xml\",\"line\":12,\"column\":17")
                && json.contains(MISSING) && json.contains("\"reproduce\":\"mvn validate\""), "failure.json:\n" + json);
        String summary = Files.readString(root.resolve("target/agent/summary.txt"), StandardCharsets.UTF_8);
        Check.that(summary.contains("ERROR b/pom.xml:12:17 " + MISSING), "summary:\n" + summary);
        String events = Files.readString(root.resolve("target/agent/build-events.jsonl"), StandardCharsets.UTF_8);
        Check.that(events.contains("\"source\":\"model\""), "model diagnostic event:\n" + events);
    }

    private static ProjectBuildingResult result(Path pom) {
        ModelProblem missing = new DefaultModelProblem(MISSING, ModelProblem.Severity.ERROR, ModelProblem.Version.V20,
                pom.toString(), 12, 17, "fix:b:0.1", null);
        ModelProblem version = new DefaultModelProblem("'build.plugins.plugin.version' for x:y is missing.",
                ModelProblem.Severity.WARNING, ModelProblem.Version.V20, pom.toString(), 13, 5, "fix:b:0.1", null);
        return new Result(pom.toFile(), List.of(missing, version));
    }

    /** A project that failed to build with {@code problems}. */
    private record Result(File pom, List<ModelProblem> problems) implements ProjectBuildingResult {
        @Override
        public String getProjectId() {
            return "fix:b:0.1";
        }

        @Override
        public File getPomFile() {
            return pom;
        }

        @Override
        public MavenProject getProject() {
            return new MavenProject();
        }

        @Override
        public List<ModelProblem> getProblems() {
            return problems;
        }

        @Override
        public DependencyResolutionResult getDependencyResolutionResult() {
            throw new UnsupportedOperationException("not resolved");
        }
    }
}
