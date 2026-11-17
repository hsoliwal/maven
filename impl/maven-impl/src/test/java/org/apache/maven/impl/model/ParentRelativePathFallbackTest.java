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
package org.apache.maven.impl.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.maven.api.Session;
import org.apache.maven.api.services.BuilderProblem;
import org.apache.maven.api.services.ModelBuilder;
import org.apache.maven.api.services.ModelBuilderException;
import org.apache.maven.api.services.ModelBuilderRequest;
import org.apache.maven.api.services.ModelBuilderResult;
import org.apache.maven.api.services.Sources;
import org.apache.maven.impl.standalone.ApiRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An explicit {@code parent.relativePath} that points at no POM: the layout of every Eclipse platform
 * repository built standalone ({@code <relativePath>../eclipse-platform-parent</relativePath>}, a sibling that
 * only exists inside the aggregator checkout). Maven 3 warned and resolved the parent from the repositories.
 */
class ParentRelativePathFallbackTest {

    private static final String MISSING = "no POM could be found";

    private static final String CHILD = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.example.platform</groupId>
                    <artifactId>platform-parent</artifactId>
                    <version>1</version>
                    <relativePath>../platform-parent</relativePath>
                </parent>
                <artifactId>platform-child</artifactId>
                <packaging>pom</packaging>
            </project>
            """;

    private static final String PARENT = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.example.platform</groupId>
                <artifactId>platform-parent</artifactId>
                <version>1</version>
                <packaging>pom</packaging>
                <properties>
                    <from.repository.parent>yes</from.repository.parent>
                </properties>
            </project>
            """;

    @Test
    void missingRelativePathTargetWarnsAndResolvesTheParentFromTheRepository(@TempDir Path tempDir) throws IOException {
        ModelBuilderResult result = build(tempDir, Map.of());

        assertEquals(
                "yes", result.getEffectiveModel().getProperties().get("from.repository.parent"), "parent from repo");
        List<BuilderProblem> missing = relativePathProblems(result);
        assertEquals(1, missing.size(), "one relativePath problem: " + missing);
        assertEquals(BuilderProblem.Severity.WARNING, missing.get(0).getSeverity());
        assertTrue(missing.get(0).getMessage().contains("resolving the parent from the repositories"));
    }

    @Test
    void strictPropertyKeepsTheFatalProblem(@TempDir Path tempDir) throws IOException {
        Map<String, String> strict = Map.of(DefaultModelBuilder.STRICT_PARENT_RELATIVE_PATH, "true");

        ModelBuilderException failure = assertThrows(ModelBuilderException.class, () -> build(tempDir, strict));

        assertTrue(relativePathProblems(failure.getResult()).stream()
                .anyMatch(p -> p.getSeverity() == BuilderProblem.Severity.FATAL));
    }

    private static ModelBuilderResult build(Path tempDir, Map<String, String> userProperties) throws IOException {
        Path repo = tempDir.resolve("repo");
        Path parentPom = repo.resolve("org/example/platform/platform-parent/1/platform-parent-1.pom");
        Files.createDirectories(parentPom.getParent());
        Files.writeString(parentPom, PARENT);
        Path childPom = tempDir.resolve("work/platform-child/pom.xml");
        Files.createDirectories(childPom.getParent().resolve(".mvn"));
        Files.writeString(childPom, CHILD);
        Session session = ApiRunner.createSession(null, repo);
        ModelBuilderRequest request = ModelBuilderRequest.builder()
                .session(session)
                .source(Sources.buildSource(childPom))
                .requestType(ModelBuilderRequest.RequestType.BUILD_PROJECT)
                .userProperties(userProperties)
                .build();
        return session.getService(ModelBuilder.class).newSession().build(request);
    }

    private static List<BuilderProblem> relativePathProblems(ModelBuilderResult result) {
        return Stream.concat(Stream.of(result), result.getChildren().stream())
                .flatMap(r -> r.getProblemCollector().problems())
                .filter(p -> p.getMessage().contains(MISSING))
                .map(BuilderProblem.class::cast)
                .toList();
    }
}
