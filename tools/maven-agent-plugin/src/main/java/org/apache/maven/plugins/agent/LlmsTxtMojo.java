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
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Writes {@code llms.txt} for coding agents, {@code mvn agent:llms-txt}: how to build the project, run one module
 * or one test, where the results are, its modules and their goals, all from the same description as
 * {@code agent:describe-project}. With {@code -Dagent.check=true} it writes nothing and fails when the file is
 * missing or differs, so a build can keep it current.
 */
@Mojo(name = "llms-txt", aggregator = true, threadSafe = true)
public final class LlmsTxtMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    private final LifecycleExecutor lifecycleExecutor;

    /** The file; default {@code llms.txt} in the top-level project's directory. */
    @Parameter(property = "agent.llmsTxt")
    private File output;

    /** Last phase whose bound goals are listed. */
    @Parameter(property = "agent.phase", defaultValue = "verify")
    private String phase;

    /** Fail when the file is not what this build renders, instead of writing it. */
    @Parameter(property = "agent.check", defaultValue = "false")
    private boolean check;

    @Inject
    public LlmsTxtMojo(LifecycleExecutor lifecycleExecutor) {
        this.lifecycleExecutor = lifecycleExecutor;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ProjectDescriber describer = new ProjectDescriber(session, lifecycleExecutor, getLog());
        Path file = output != null ? output.toPath() : describer.root().resolve("llms.txt");
        String text = LlmsTxtRenderer.render(describer.describe(phase));
        try {
            boolean current = Files.isRegularFile(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8));
            if (check && !current) {
                throw new MojoFailureException(describer.relative(file) + " is missing or stale; run mvn agent:llms-txt");
            }
            if (!current) {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
            getLog().info((current ? "Up to date: " : "Written: ") + describer.relative(file));
        } catch (IOException failed) {
            throw new MojoExecutionException("Cannot read or write " + file, failed);
        }
    }
}
