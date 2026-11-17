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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
 * POM model and the project's dependencies stay as they are): a {@code -Xplugin:CodeClustering} argument, and the
 * plugin jar where javac looks for plugins, which is the annotation processor path.
 * <ul>
 *   <li>With {@code annotationProcessorPaths} configured, the plugin's coordinates are added to them (the spy checks
 *       that they resolve to a jar that registers the plugin).</li>
 *   <li>Otherwise javac would discover processors on the class path; {@code --processor-path} becomes the plugin jar
 *       followed by the execution's own class path, so the same processors are found as before. Modular sources take
 *       the plugin jar alone, unless processors are named or a class path element registers one.</li>
 *   <li>On Java 23 and newer, where a processor path switches processing on, {@code -proc:none} keeps it off unless
 *       {@code proc} or {@code -proc:} is configured or processors are named ({@code annotationProcessors} or
 *       {@code -processor}), which switch it on themselves.</li>
 * </ul>
 * The plugin gets the execution's source roots ({@code roots}), its generated sources directory ({@code generated})
 * and {@code full=true} unless {@code useIncrementalCompilation} is {@code false} (maven-compiler-plugin then compiles
 * stale sources only; otherwise it compiles all of them or none), all paths as file URIs. Output:
 * {@code target/code-clustering/inline} for {@code default-compile}, {@code inline/test} for
 * {@code default-testCompile}, and an {@code execution-<id>} directory below those for other executions.
 * <p>
 * Executions the plugin cannot join safely are left alone ({@link #blocked}): another compiler, a forked or
 * toolchain javac (its version is unknown), Maven below Java 17, a processor path set in {@code compilerArgs}, the
 * plugin already configured, annotationProcessorPaths that cannot name it, or modular sources whose processors come
 * from the class path.
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
     * Why the execution is left alone; empty when it can take the plugin. {@code coordinates}: why
     * annotationProcessorPaths cannot name the plugin, empty when they can.
     */
    Optional<String> blocked(Optional<String> coordinates) {
        if (config == null) return Optional.of("no configuration");
        return Stream.<Supplier<Optional<String>>>of(this::compilerBlocked, this::argumentsBlocked,
                () -> processorPaths() != null ? coordinates : modularBlocked())
                .map(Supplier::get).flatMap(Optional::stream).findFirst();
    }

    /** Another compiler, or a javac whose version is unknown. */
    private Optional<String> compilerBlocked() {
        String compilerId = value("compilerId");
        if (Runtime.version().feature() < PLUGIN_JAVA) return Optional.of("Maven runs on Java " + Runtime.version().feature());
        if (compilerId != null && !"javac".equals(compilerId)) return Optional.of("compilerId " + compilerId);
        if ("true".equals(value("fork")) || value("executable") != null) return Optional.of("forked javac");
        if (config.getChild("jdkToolchain") != null || toolchains()) return Optional.of("toolchain javac");
        return Optional.empty();
    }

    /** The plugin configured already, or a processor path given as a compiler argument. */
    private Optional<String> argumentsBlocked() {
        List<String> args = arguments();
        if (args.stream().anyMatch(a -> a.startsWith("-Xplugin:" + NAME))) return Optional.of("plugin already configured");
        boolean path = args.stream().anyMatch(a -> a.matches("-processorpath|--processor-path|--processor-module-path"));
        return path ? Optional.of("processor path set in compilerArgs") : Optional.empty();
    }

    /**
     * Modular sources without annotationProcessorPaths: javac would find processors on the class path, which here holds
     * module path entries too, so the plugin jar goes alone and only when no processor is named or registered there.
     */
    private Optional<String> modularBlocked() {
        if (!modular()) return Optional.empty();
        boolean processors = namedProcessors()
                || classpath().stream().map(Path::of).anyMatch(PluginJar::registersProcessors);
        return processors ? Optional.of("modular sources with annotation processors on the class path")
                : Optional.empty();
    }

    /** Adds the plugin to the execution's configuration. */
    void wire(Path jar, String version, String options) {
        List<String> added = new ArrayList<>(List.of(pluginArgument(options)));
        Xpp3Dom paths = processorPaths();
        if (paths != null) paths.addChild(coordinates(version));
        else added.addAll(processorPathArguments(jar));
        Xpp3Dom args = child("compilerArgs");
        added.forEach(a -> args.addChild(element("arg", a)));
    }

    /** {@code -Xplugin:CodeClustering out=... roots=... generated=... [full=true]} with every path a file URI. */
    private String pluginArgument(String options) {
        String roots = sourceRoots().stream().map(r -> " roots=" + r.toUri()).collect(Collectors.joining());
        String generated = generatedSources().map(g -> " generated=" + g.toUri()).orElse("");
        String full = full() ? " full=true" : "";
        return "-Xplugin:" + NAME + " out=" + out().toUri() + roots + generated + full + options;
    }

    /** The processor path (the plugin jar, then the class path unless modular) and, if needed, {@code -proc:none}. */
    private List<String> processorPathArguments(Path jar) {
        String path = modular() ? jar.toString() : processorPath(jar);
        List<String> added = new ArrayList<>(List.of("--processor-path", path));
        if (Runtime.version().feature() >= PROCESSOR_PATH_ENABLES && !processingConfigured()) added.add("-proc:none");
        return added;
    }

    /** Whether the configuration decides about annotation processing itself: proc, -proc:, or named processors. */
    private boolean processingConfigured() {
        return value("proc") != null || namedProcessors() || arguments().stream().anyMatch(a -> a.startsWith("-proc:"));
    }

    /** Processors named by {@code annotationProcessors} or a {@code -processor} argument. */
    private boolean namedProcessors() {
        Xpp3Dom named = config.getChild("annotationProcessors");
        boolean configured = named != null && (named.getChildCount() > 0 || evaluate(named.getValue()) != null);
        return configured || arguments().contains("-processor");
    }

    /** The plugin jar, then the elements of the execution's class path that exist. */
    private String processorPath(Path jar) {
        return Stream.concat(Stream.of(jar.toString()), classpath().stream().filter(e -> Files.exists(Path.of(e))))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private List<String> classpath() {
        try {
            return test() ? project.getTestClasspathElements() : project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new IllegalStateException("class path not resolved: " + e.getMessage(), e);
        }
    }

    /** {@code inline} for the default executions ({@code inline/test} for tests), else a directory per execution. */
    private Path out() {
        Path inline = Path.of(project.getBuild().getDirectory(), "code-clustering", "inline");
        Path dir = test() ? inline.resolve("test") : inline;
        String id = mojo.getExecutionId();
        boolean usual = id == null || id.equals("default-" + mojo.getGoal()) || id.equals("default-cli");
        return usual ? dir : dir.resolve("execution-" + id.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** The execution's {@code compileSourceRoots} when configured, else the project's, as absolute paths. */
    private List<Path> sourceRoots() {
        Xpp3Dom roots = config.getChild("compileSourceRoots");
        List<String> given = roots != null && roots.getChildCount() > 0
                ? Stream.of(roots.getChildren()).map(c -> evaluate(c.getValue())).filter(Objects::nonNull)
                        .collect(Collectors.toList())
                : test() ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots();
        return given.stream().map(r -> project.getBasedir().toPath().resolve(r).toAbsolutePath().normalize())
                .distinct().collect(Collectors.toList());
    }

    private Optional<Path> generatedSources() {
        return Optional.ofNullable(value(test() ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory"))
                .map(d -> project.getBasedir().toPath().resolve(d).toAbsolutePath().normalize());
    }

    /** maven-compiler-plugin compiles every source or none unless useIncrementalCompilation is false. */
    private boolean full() {
        return Optional.ofNullable(value("useIncrementalCompilation")).map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }

    private boolean test() {
        return "testCompile".equals(mojo.getGoal());
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
        List<String> roots = test() ? project.getTestCompileSourceRoots() : List.of();
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
