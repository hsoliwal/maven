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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

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
 * Executions the plugin cannot join safely are left alone ({@link #blocked}): another compiler, a
 * maven-compiler-plugin other than 3.1+ (4.x), a forked or toolchain javac (its version is unknown), Maven below
 * Java 17, a processor path in {@code compilerArgs}, {@code compilerArgument} or {@code compilerArguments}, the plugin
 * already configured, annotationProcessorPaths that cannot name it, modular sources whose processors come from the
 * class path, or sources too large for this compilation's share of the heap ({@link #HEAP_PER_SOURCE_BYTE}).
 */
final class CompilerWiring {
    private static final String COMPILER = "maven-compiler-plugin";
    private static final String NAME = "CodeClustering";
    private static final int PLUGIN_JAVA = 17;
    private static final int PROCESSOR_PATH_ENABLES = 23;
    /**
     * Heap a compilation with the plugin may need per byte of Java source, with margin: on 4,000 small classes
     * (1.1 MB of source, 12,000 atoms) javac's heap floor rose from 40-48 MB to 56-64 MB with the plugin (2026-11-18,
     * JDK 17).
     */
    static final long HEAP_PER_SOURCE_BYTE = 64;
    private static final long MB = 1024 * 1024;
    private final MavenSession session;
    private final MavenProject project;
    private final MojoExecution mojo;
    private final CompilerConfig config;
    private final ToolchainManager toolchains;

    CompilerWiring(MavenSession session, MavenProject project, MojoExecution mojo, ToolchainManager toolchains) {
        this.session = session;
        this.project = project;
        this.mojo = mojo;
        this.config = new CompilerConfig(session, mojo);
        this.toolchains = toolchains;
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
        if (!config.present()) return Optional.of("no configuration");
        return Stream.<Supplier<Optional<String>>>of(this::compilerBlocked, this::argumentsBlocked,
                () -> config.processorPaths() != null ? coordinates : modularBlocked(), this::heapBlocked)
                .map(Supplier::get).flatMap(Optional::stream).findFirst();
    }

    /**
     * Another compiler or a maven-compiler-plugin other than 3.1+ (4.x resolves its class path through the Maven 4 API,
     * not yet at MojoStarted for this spy), or a javac whose version is unknown: forked, or a JDK toolchain, whichever
     * plugin selected it ({@code jdkToolchain}, or a toolchain in the build context).
     */
    private Optional<String> compilerBlocked() {
        String compilerId = config.value("compilerId");
        if (Runtime.version().feature() < PLUGIN_JAVA) return Optional.of("Maven runs on Java " + Runtime.version().feature());
        if (!threeOne()) return Optional.of(COMPILER + " " + mojo.getVersion() + " (only 3.1 and newer 3.x are wired)");
        if (compilerId != null && !"javac".equals(compilerId)) return Optional.of("compilerId " + compilerId);
        if ("true".equals(config.value("fork")) || config.value("executable") != null) return Optional.of("forked javac");
        if (config.has("jdkToolchain") || toolchain()) return Optional.of("toolchain javac");
        return Optional.empty();
    }

    /** maven-compiler-plugin 3.1 or a newer 3.x (compilerArgs, useIncrementalCompilation); true when unknown. */
    private boolean threeOne() {
        String version = mojo.getVersion();
        return version == null || version.startsWith("3.") && !version.equals("3.0");
    }

    private boolean toolchain() {
        return toolchains != null && toolchains.getToolchainFromBuildContext("jdk", session) != null;
    }

    /** The plugin configured already, or a processor path in the compiler arguments (javac uses the last one). */
    private Optional<String> argumentsBlocked() {
        if (config.arguments().stream().anyMatch(a -> a.startsWith("-Xplugin:" + NAME))) {
            return Optional.of("plugin already configured");
        }
        return config.processorPathSet() ? Optional.of("processor path set in the compiler arguments") : Optional.empty();
    }

    /**
     * A module whose Java sources need more heap with the plugin ({@link #HEAP_PER_SOURCE_BYTE}) than this
     * compilation's share of Maven's ({@code maxMemory} over the build threads): a tight {@code -Xmx} that the build
     * alone fits must not fail.
     */
    private Optional<String> heapBlocked() {
        long share = Runtime.getRuntime().maxMemory() / Math.max(1, session.getRequest().getDegreeOfConcurrency());
        long bytes = sourceRoots().stream().mapToLong(CompilerWiring::javaBytes).sum();
        if (bytes * HEAP_PER_SOURCE_BYTE <= share) return Optional.empty();
        return Optional.of(String.format("%d KB of Java sources need about %d MB of heap with the plugin, this"
                + " compilation's share is %d MB (raise -Xmx in MAVEN_OPTS)", bytes / 1024,
                bytes * HEAP_PER_SOURCE_BYTE / MB, share / MB));
    }

    /** The bytes of the {@code .java} files under a source root; 0 when it cannot be read. */
    private static long javaBytes(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    /**
     * Modular sources without annotationProcessorPaths: javac would find processors on the class path, which here holds
     * module path entries too, so the plugin jar goes alone and only when no processor is named or registered there.
     */
    private Optional<String> modularBlocked() {
        if (!modular()) return Optional.empty();
        boolean processors = config.namedProcessors()
                || classpath().stream().map(Path::of).anyMatch(PluginJar::registersProcessors);
        return processors ? Optional.of("modular sources with annotation processors on the class path")
                : Optional.empty();
    }

    /** Adds the plugin to the execution's configuration. */
    void wire(Path jar, String version, String options) {
        List<String> added = new ArrayList<>(List.of(pluginArgument(options)));
        if (config.processorPaths() != null) {
            config.addProcessorPath(InlineClusteringSpy.GROUP, InlineClusteringSpy.ARTIFACT, version);
        } else {
            added.addAll(processorPathArguments(jar));
        }
        config.addArguments(added);
    }

    /**
     * The plugin's arguments for {@code InlineClustering.prune} when the execution compiles stale sources only (it
     * may find nothing stale after a file was deleted, so javac would not drop that file's rows); empty otherwise.
     */
    Optional<String[]> pruneArguments(String options) {
        if (full()) return Optional.empty();
        String argument = pluginArgument(options);
        return Optional.of(argument.substring(argument.indexOf(' ') + 1).split(" "));
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
        if (Runtime.version().feature() >= PROCESSOR_PATH_ENABLES && !config.processingConfigured()) added.add("-proc:none");
        return added;
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
        List<String> configured = config.compileSourceRoots();
        List<String> given = !configured.isEmpty() ? configured
                : test() ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots();
        return given.stream().map(r -> project.getBasedir().toPath().resolve(r).toAbsolutePath().normalize())
                .distinct().collect(Collectors.toList());
    }

    private Optional<Path> generatedSources() {
        return Optional.ofNullable(config.value(test() ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory"))
                .map(d -> project.getBasedir().toPath().resolve(d).toAbsolutePath().normalize());
    }

    /**
     * maven-compiler-plugin 3.1+ compiles every source or none unless useIncrementalCompilation is false (4.x, never
     * wired, compiles single sources in its incremental modes).
     */
    private boolean full() {
        boolean incremental = Optional.ofNullable(config.value("useIncrementalCompilation"))
                .map(v -> !"false".equalsIgnoreCase(v.trim())).orElse(true);
        return incremental && threeOne();
    }

    private boolean test() {
        return "testCompile".equals(mojo.getGoal());
    }

    private boolean modular() {
        List<String> roots = test() ? project.getTestCompileSourceRoots() : List.of();
        return Stream.concat(project.getCompileSourceRoots().stream(), roots.stream())
                .anyMatch(r -> Files.isRegularFile(Path.of(r, "module-info.java")));
    }
}
