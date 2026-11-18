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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Answers a question from the project database of the execution root, for example
 * {@code mvn project-db:query -Dproject-db.question="which tests are flaky"}. Prints one line per answer with its
 * provenance (file:line @build); {@code -Dproject-db.output=answer.json} also writes the JSON form.
 */
@Mojo(name = "query", aggregator = true, threadSafe = true, requiresProject = false)
public final class QueryMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "project-db.question", required = true)
    private String question;

    @Parameter(property = "project-db.k", defaultValue = "5")
    private int k;

    @Parameter(property = "project-db.directory")
    private String directory;

    @Parameter(property = "project-db.persistent", defaultValue = "false")
    private boolean persistent;

    /** Optional JSON answer file. */
    @Parameter(property = "project-db.output")
    private File output;

    @Override
    public void execute() throws MojoExecutionException {
        Path root = Path.of(session.getExecutionRootDirectory()).toAbsolutePath().normalize();
        try (ProjectDb db = ProjectDb.open(StoreLocation.resolve(root, directory, persistent))) {
            Answer answer = db.query(question, Math.max(1, Math.min(50, k)));
            answer.lines().forEach(line -> getLog().info(line));
            if (output != null) {
                Files.createDirectories(output.toPath().toAbsolutePath().getParent());
                Files.writeString(output.toPath(), answer.json() + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException failure) {
            throw new MojoExecutionException("project-db query failed: " + failure.getMessage(), failure);
        }
    }
}
