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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Ingests the build data of every project in the reactor as one new build of the project database. Runs once, at the
 * execution root, and needs no dependency resolution, so it also works after a failed build:
 * {@code mvn verify; mvn project-db:ingest -Dproject-db.buildLog=build.log}. Each module's effective POM is written
 * from its model to {@code target/project-db/effective-pom.xml} unless {@code target/effective-pom.xml} exists.
 */
@Mojo(name = "ingest", aggregator = true, threadSafe = true)
public final class IngestMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Store directory; default {@code target/project-db} of the execution root. */
    @Parameter(property = "project-db.directory")
    private String directory;

    /** Keep the store in {@code .mvn/project-db} so that it survives {@code clean} (never committed). */
    @Parameter(property = "project-db.persistent", defaultValue = "false")
    private boolean persistent;

    /** A Maven console log of the build to read errors from. */
    @Parameter(property = "project-db.buildLog")
    private File buildLog;

    @Parameter(property = "project-db.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) return;
        Path root = Path.of(session.getExecutionRootDirectory()).toAbsolutePath().normalize();
        try (ProjectDb db = ProjectDb.open(StoreLocation.resolve(root, directory, persistent))) {
            List<Path> modules = new ArrayList<>();
            for (MavenProject project : session.getProjects()) modules.add(effectivePom(project));
            ProjectDb.Ingest ingest = db.ingest(root, modules, buildLog == null ? null : buildLog.toPath());
            getLog().info("project-db: build " + ingest.build().id() + " " + ingest.build().status() + ", "
                    + ingest.build().sources() + " sources (" + ingest.build().parsed() + " parsed, "
                    + ingest.build().reused() + " unchanged), " + ingest.chunks() + " chunks, storage "
                    + db.binding());
        } catch (IOException | RuntimeException failure) {
            throw new MojoExecutionException("project-db ingest failed: " + failure.getMessage(), failure);
        }
    }

    /** Writes the module's effective model unless the help plugin already wrote one; returns the module directory. */
    private static Path effectivePom(MavenProject project) throws IOException {
        Path module = project.getBasedir().toPath().toAbsolutePath().normalize();
        if (Files.isRegularFile(module.resolve("target/effective-pom.xml"))) return module;
        Path file = module.resolve("target/project-db/effective-pom.xml");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new MavenXpp3Writer().write(out, project.getModel());
        }
        return module;
    }
}
