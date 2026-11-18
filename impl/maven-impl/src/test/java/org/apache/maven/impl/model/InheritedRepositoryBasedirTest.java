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
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Repository;
import org.apache.maven.api.services.ModelBuilder;
import org.apache.maven.api.services.ModelBuilderRequest;
import org.apache.maven.api.services.Sources;
import org.apache.maven.impl.standalone.ApiRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A repository declared in a parent with a project-directory expression is resolved against each subproject, as in
 * Maven 3. Eclipse egit declares {@code <url>${jgit-site}</url>} with
 * {@code jgit-site=file:${project.basedir}/../../jgit/...}: every bundle points at the sibling jgit checkout. Resolving
 * the expression while the parent file was read made every bundle point one directory too high.
 */
class InheritedRepositoryBasedirTest {

    private static final String PARENT = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.example.egit</groupId>
                <artifactId>egit-parent</artifactId>
                <version>1</version>
                <packaging>pom</packaging>
                <properties>
                    <sibling-site>file:${project.basedir}/../../sibling/repository</sibling-site>
                </properties>
                <repositories>
                    <repository><id>direct</id><url>file:${project.basedir}/../direct</url></repository>
                    <repository><id>indirect</id><url>${sibling-site}</url></repository>
                </repositories>
            </project>
            """;

    private static final String CHILD = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.example.egit</groupId>
                    <artifactId>egit-parent</artifactId>
                    <version>1</version>
                </parent>
                <artifactId>egit-bundle</artifactId>
            </project>
            """;

    @Test
    void inheritedRepositoryUrlsUseTheSubprojectDirectory(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("egit/.mvn")).getParent();
        Files.writeString(root.resolve("pom.xml"), PARENT);
        Path childPom = Files.createDirectories(root.resolve("bundle")).resolve("pom.xml");
        Files.writeString(childPom, CHILD);

        Map<String, String> urls = urls(build(childPom));
        Map<String, String> rootUrls = urls(build(root.resolve("pom.xml")));

        String bundle = childPom.getParent().toString();
        assertEquals("file:" + bundle + "/../direct", urls.get("direct"));
        assertEquals("file:" + bundle + "/../../sibling/repository", urls.get("indirect"));
        assertEquals("file:" + root + "/../direct", rootUrls.get("direct"), "the parent keeps its own directory");
    }

    private static Model build(Path pom) {
        Session session = ApiRunner.createSession();
        ModelBuilderRequest request = ModelBuilderRequest.builder()
                .session(session)
                .source(Sources.buildSource(pom))
                .requestType(ModelBuilderRequest.RequestType.BUILD_PROJECT)
                .build();
        return session.getService(ModelBuilder.class)
                .newSession()
                .build(request)
                .getEffectiveModel();
    }

    private static Map<String, String> urls(Model model) {
        return model.getRepositories().stream().collect(Collectors.toMap(Repository::getId, Repository::getUrl));
    }
}
