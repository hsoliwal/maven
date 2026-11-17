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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.maven.agent.events.MavenCommands;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

/**
 * Builds the {@link ProjectDescription} of the session's reactor: each module's goals are Maven's own execution plan up
 * to the phase ({@link LifecycleExecutor#calculateExecutionPlan(MavenSession, boolean, String...)}, nothing run), its
 * dependencies the reactor's dependency graph, its roots the declared source roots that exist.
 */
final class ProjectDescriber {
    private final MavenSession session;
    private final LifecycleExecutor lifecycle;
    private final Log log;
    private final Path root;

    ProjectDescriber(MavenSession session, LifecycleExecutor lifecycle, Log log) {
        this.session = session;
        this.lifecycle = lifecycle;
        this.log = log;
        this.root = session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    /** The description with goals up to {@code phase}. */
    ProjectDescription describe(String phase) {
        List<MavenProject> projects = session.getProjects();
        List<ProjectDescription.Module> modules = IntStream.range(0, projects.size())
                .mapToObj(i -> module(i + 1, projects.get(i), phase)).collect(Collectors.toList());
        ProjectDescription.Commands commands = commands(MavenCommands.launcher(root), phase);
        MavenProject top = session.getTopLevelProject();
        return new ProjectDescription(Optional.ofNullable(top.getName()).orElse(top.getArtifactId()),
                Optional.ofNullable(top.getDescription()).orElse("").strip(), modules, commands);
    }

    /** The commands of a description, spelled by the extension's {@link MavenCommands}, their one owner. */
    static ProjectDescription.Commands commands(String launcher, String phase) {
        return new ProjectDescription.Commands(MavenCommands.build(launcher), MavenCommands.agent(launcher),
                MavenCommands.module(launcher, "<path>", phase), MavenCommands.test(launcher, "<path>", "<Class>#<method>"));
    }

    private ProjectDescription.Module module(int index, MavenProject project, String phase) {
        List<String> dependsOn = session.getProjectDependencyGraph().getUpstreamProjects(project, false).stream()
                .map(ProjectDescriber::id).collect(Collectors.toList());
        return new ProjectDescription.Module(index, id(project), relative(project.getBasedir().toPath()),
                project.getPackaging(), project.getVersion(), dependsOn, roots(project.getCompileSourceRoots()),
                roots(project.getTestCompileSourceRoots()), goals(project, phase));
    }

    /** Maven's execution plan of {@code project} up to {@code phase}; empty, with a warning, when it cannot be had. */
    private List<ProjectDescription.Goal> goals(MavenProject project, String phase) {
        MavenProject current = session.getCurrentProject();
        try {
            session.setCurrentProject(project);
            return lifecycle.calculateExecutionPlan(session, false, phase).getMojoExecutions().stream()
                    .map(ProjectDescriber::goal).collect(Collectors.toList());
        } catch (Exception unavailable) { // the plan declares ten checked exceptions; any of them means no goals
            log.warn("No execution plan for " + id(project) + ": " + unavailable.getMessage());
            return List.of();
        } finally {
            session.setCurrentProject(current);
        }
    }

    private static ProjectDescription.Goal goal(MojoExecution mojo) {
        return new ProjectDescription.Goal(Optional.ofNullable(mojo.getLifecyclePhase()).orElse(""),
                mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + mojo.getVersion(), mojo.getGoal(),
                mojo.getExecutionId());
    }

    private List<String> roots(List<String> roots) {
        return roots.stream().map(Path::of).filter(Files::isDirectory).map(this::relative).collect(Collectors.toList());
    }

    /** Root-relative with '/' separators, "." for the root itself. */
    String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String text = absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
        return text.isEmpty() ? "." : text.replace('\\', '/');
    }

    static String id(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId();
    }
}
