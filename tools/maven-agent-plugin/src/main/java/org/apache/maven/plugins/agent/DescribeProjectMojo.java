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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Writes {@code project.json} for coding agents, {@code mvn agent:describe-project}: every module of the reactor
 * with its module dependencies, source and test roots and the goals Maven would run up to {@code agent.phase}, and the
 * commands to build and test. Nothing of the build runs.
 */
@Mojo(name = "describe-project", aggregator = true, threadSafe = true)
public final class DescribeProjectMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    private final LifecycleExecutor lifecycleExecutor;

    /** Directory of the agent files; default {@code target/agent} of the top-level project. */
    @Parameter(property = "maven.agent.dir")
    private File agentDir;

    /** Last phase whose bound goals are listed. */
    @Parameter(property = "agent.phase", defaultValue = "verify")
    private String phase;

    @Inject
    public DescribeProjectMojo(LifecycleExecutor lifecycleExecutor) {
        this.lifecycleExecutor = lifecycleExecutor;
    }

    @Override
    public void execute() throws MojoExecutionException {
        ProjectDescriber describer = new ProjectDescriber(session, lifecycleExecutor, getLog());
        Path directory = agentDir != null ? agentDir.toPath() : describer.root().resolve("target").resolve("agent");
        Path file = directory.resolve("project.json");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, describer.describe(phase).json(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new MojoExecutionException("Cannot write " + file, failed);
        }
        getLog().info("Project description: " + describer.relative(file));
    }
}
