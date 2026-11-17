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
package org.apache.maven.extensions.clustering;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Adds the javac plugin to one maven-compiler-plugin execution by editing that execution's configuration only (the
 * POM model and the project's dependencies stay as they are): a {@code -Xplugin:CodeClustering out=...} argument,
 * and the plugin jar where javac looks for plugins, which is the annotation processor path.
 * <ul>
 *   <li>With {@code annotationProcessorPaths} configured, the plugin's coordinates are added to them.</li>
 *   <li>Otherwise javac would discover processors on the class path; {@code --processor-path} becomes the plugin jar
 *       followed by the execution's own class path, so the same processors are found as before. On Java 23 and
 *       newer, where a processor path switches processing on, {@code -proc:none} keeps it off unless {@code proc}
 *       or {@code -proc:} is configured.</li>
 * </ul>
 * Executions the plugin cannot join safely are left alone ({@link #blocked}): another compiler, a forked or
 * toolchain javac (its version is unknown), Maven below Java 17, a processor path set in {@code compilerArgs},
 * modular sources (their processors come from the module path), or the plugin already configured.
 */
final class CompilerWiring {
    private static final String COMPILER = "maven-compiler-plugin";
    private static final String NAME = "CodeClustering";
    private static final int PLUGIN_JAVA = 17;
    private static final int PROCESSOR_PATH_ENABLES = 23;
    private final MavenProject project;
    private final MojoExecution mojo;
    private final Xpp3Dom config;
    private final PluginParameterExpressionEvaluator evaluator;

    CompilerWiring(MavenSession session, MavenProject project, MojoExecution mojo) {
        this.project = project;
        this.mojo = mojo;
        this.config = mojo.getConfiguration();
        this.evaluator = new PluginParameterExpressionEvaluator(session, mojo);
    }

    static boolean isCompile(MojoExecution mojo) {
        return InlineClusteringSpy.GROUP.equals(mojo.getGroupId()) && COMPILER.equals(mojo.getArtifactId())
                && ("compile".equals(mojo.getGoal()) || "testCompile".equals(mojo.getGoal()));
    }

    /**
     * Why the execution is left alone; empty when it can take the plugin. {@code coordinates}: whether the plugin is
     * in the local repository, which annotationProcessorPaths need.
     */
    Optional<String> blocked(boolean coordinates) {
        if (config == null) return Optional.of("no configuration");
        String compilerId = value("compilerId");
        if (Runtime.version().feature() < PLUGIN_JAVA) return Optional.of("Maven runs on Java " + Runtime.version().feature());
        if (compilerId != null && !"javac".equals(compilerId)) return Optional.of("compilerId " + compilerId);
        if ("true".equals(value("fork")) || value("executable") != null) return Optional.of("forked javac");
        if (config.getChild("jdkToolchain") != null || toolchains()) return Optional.of("toolchain javac");
        if (modular()) return Optional.of("modular sources (module-info.java)");
        if (processorPaths() != null && !coordinates) {
            return Optional.of("annotationProcessorPaths need the plugin in the local repository");
        }
        return argumentsBlocked();
    }

    /** The plugin configured already, or a processor path given as a compiler argument. */
    private Optional<String> argumentsBlocked() {
        List<String> args = arguments();
        if (args.stream().anyMatch(a -> a.startsWith("-Xplugin:" + NAME))) return Optional.of("plugin already configured");
        boolean path = args.stream().anyMatch(a -> a.matches("-processorpath|--processor-path|--processor-module-path"));
        return path ? Optional.of("processor path set in compilerArgs") : Optional.empty();
    }

    /** Adds the plugin to the execution's configuration. */
    void wire(Path jar, String version, String options) {
        boolean test = "testCompile".equals(mojo.getGoal());
        Path out = Path.of(project.getBuild().getDirectory(), "code-clustering");
        List<String> added = new ArrayList<>(List.of("-Xplugin:" + NAME + " out=" + (test ? out.resolve("test") : out) + options));
        Xpp3Dom paths = processorPaths();
        if (paths != null) {
            paths.addChild(coordinates(version));
        } else {
            added.add("--processor-path");
            added.add(processorPath(jar, test));
            if (Runtime.version().feature() >= PROCESSOR_PATH_ENABLES && value("proc") == null
                    && arguments().stream().noneMatch(a -> a.startsWith("-proc:"))) added.add("-proc:none");
        }
        Xpp3Dom args = child("compilerArgs");
        added.forEach(a -> args.addChild(element("arg", a)));
    }

    /** The plugin jar, then the elements of the execution's class path that exist. */
    private String processorPath(Path jar, boolean test) {
        try {
            List<String> classpath = test ? project.getTestClasspathElements() : project.getCompileClasspathElements();
            return Stream.concat(Stream.of(jar.toString()), classpath.stream().filter(e -> Files.exists(Path.of(e))))
                    .collect(Collectors.joining(File.pathSeparator));
        } catch (DependencyResolutionRequiredException e) {
            throw new IllegalStateException("class path not resolved: " + e.getMessage(), e);
        }
    }

    private Xpp3Dom coordinates(String version) {
        Xpp3Dom path = new Xpp3Dom("path");
        path.addChild(element("groupId", InlineClusteringSpy.GROUP));
        path.addChild(element("artifactId", InlineClusteringSpy.ARTIFACT));
        path.addChild(element("version", version));
        return path;
    }

    private Xpp3Dom processorPaths() {
        Xpp3Dom paths = config.getChild("annotationProcessorPaths");
        return paths != null && paths.getChildCount() > 0 ? paths : null;
    }

    /** The configured compilerArgs, evaluated, plus the words of compilerArgument. */
    private List<String> arguments() {
        List<String> args = new ArrayList<>();
        Xpp3Dom list = config.getChild("compilerArgs");
        if (list != null) Stream.of(list.getChildren()).map(c -> evaluate(c.getValue())).forEach(args::add);
        String single = value("compilerArgument");
        if (single != null) args.addAll(List.of(single.trim().split("\\s+")));
        args.removeIf(a -> a == null);
        return args;
    }

    private boolean modular() {
        List<String> roots = "testCompile".equals(mojo.getGoal()) ? project.getTestCompileSourceRoots() : List.of();
        return Stream.concat(project.getCompileSourceRoots().stream(), roots.stream())
                .anyMatch(r -> Files.isRegularFile(Path.of(r, "module-info.java")));
    }

    private boolean toolchains() {
        return project.getBuildPlugins().stream().anyMatch(p -> "maven-toolchains-plugin".equals(p.getArtifactId()));
    }

    /** A parameter's evaluated value: its text, else its default; null when neither gives one. */
    private String value(String name) {
        Xpp3Dom child = config == null ? null : config.getChild(name);
        if (child == null) return null;
        String raw = child.getValue() != null ? child.getValue() : child.getAttribute("default-value");
        return evaluate(raw);
    }

    private String evaluate(String raw) {
        try {
            Object value = raw == null ? null : evaluator.evaluate(raw);
            return value == null || value.toString().isBlank() ? null : value.toString();
        } catch (ExpressionEvaluationException e) {
            throw new IllegalStateException("cannot evaluate " + raw + ": " + e.getMessage(), e);
        }
    }

    private Xpp3Dom child(String name) {
        Xpp3Dom child = config.getChild(name);
        if (child == null) {
            child = new Xpp3Dom(name);
            config.addChild(child);
        }
        return child;
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }
}
