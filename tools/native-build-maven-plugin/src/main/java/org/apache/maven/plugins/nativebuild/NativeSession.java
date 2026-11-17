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
package org.apache.maven.plugins.nativebuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.nativebuild.engine.BuildDetector;
import org.apache.maven.plugins.nativebuild.engine.BuildSystem;
import org.apache.maven.plugins.nativebuild.engine.Detection;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.MsvcLocator;
import org.apache.maven.plugins.nativebuild.engine.NativeOptions;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.NativeStep;
import org.apache.maven.plugins.nativebuild.engine.PlanContext;
import org.apache.maven.plugins.nativebuild.engine.Platform;
import org.apache.maven.plugins.nativebuild.engine.PolyglotEngine;
import org.apache.maven.plugins.nativebuild.engine.StepPlanner;
import org.apache.maven.plugins.nativebuild.engine.TaskFileWriter;
import org.apache.maven.plugins.nativebuild.engine.ToolResolver;
import org.apache.maven.plugins.nativebuild.engine.ToolSet;
import org.apache.maven.plugins.nativebuild.engine.ToolchainFingerprint;

/**
 * One goal's view of the native build: detection, resolved tools, classifier, layout and the planned steps, and
 * running steps through the polyglot engine from an isolated {@code target/native/<classifier>/tasks.tsv}.
 */
final class NativeSession {
    private final AbstractNativeMojo mojo;
    private final Detection detection;
    private final PlanContext context;
    private final String classifier;
    private final List<NativeStep> steps;

    private NativeSession(AbstractNativeMojo mojo, Detection detection, PlanContext context, String classifier,
            List<NativeStep> steps) {
        this.mojo = mojo;
        this.detection = detection;
        this.context = context;
        this.classifier = classifier;
        this.steps = steps;
    }

    /** Detects and plans; {@code chained} plans for a root manifest (no fingerprint input). */
    static NativeSession open(AbstractNativeMojo m, boolean fingerprint, boolean chained) throws IOException {
        if (m.timeoutSeconds < 1 || m.timeoutSeconds > 43_200) {
            throw new IllegalArgumentException("native.timeoutSeconds must be between 1 and 43200");
        }
        Detection detection = BuildDetector.detect(m.sourceDirectory.toPath(), m.buildSystem);
        if (fingerprint || !detection.found()) {
            m.getLog().info(detection.line());
        } else {
            m.getLog().debug(detection.line());
        }
        return detection.found() ? planned(m, detection, fingerprint, chained)
                : new NativeSession(m, detection, null, "", List.of());
    }

    private static NativeSession planned(AbstractNativeMojo m, Detection detection, boolean fingerprint,
            boolean chained) throws IOException {
        Platform platform = Platform.current();
        ToolSet tools = tools(m, detection.system(), platform);
        String classifier = classifier(m, detection.system(), platform, tools);
        PlanContext context = context(m, detection, tools, platform, classifier, !chained);
        if (fingerprint) {
            writeFingerprint(m.getLog(), context);
        }
        return new NativeSession(m, detection, context, classifier, StepPlanner.forSystem(detection.system()).plan(context));
    }

    boolean found() {
        return detection.found();
    }

    Detection detection() {
        return detection;
    }

    PlanContext context() {
        return context;
    }

    String classifier() {
        return classifier;
    }

    List<NativeStep> steps() {
        return steps;
    }

    StepPlanner planner() {
        return StepPlanner.forSystem(detection.system());
    }

    Log log() {
        return mojo.getLog();
    }

    Duration timeout() {
        return Duration.ofSeconds(mojo.timeoutSeconds);
    }

    Optional<NativeStep> step(NativePhase phase) {
        return steps.stream().filter(s -> s.phase() == phase).findFirst();
    }

    /** Runs the planned steps of the given phases, in plan order; true when there was any. */
    boolean run(NativePhase... phases) throws IOException, InterruptedException {
        Path tasks = prepare();
        List<NativeStep> selected = steps.stream().filter(s -> Arrays.asList(phases).contains(s.phase())).toList();
        for (NativeStep step : selected) {
            log().info(step.name() + ": " + step.display());
            PolyglotEngine.run(tasks, context.layout().basedir(), step.name(), mojo.incremental, timeout(),
                    line -> log().info(line));
        }
        return !selected.isEmpty();
    }

    /** Deletes a project-relative directory tree (the stage before a fresh install). */
    void clean(String relative) throws IOException {
        Path root = context.layout().path(relative);
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private Path prepare() throws IOException {
        Layout layout = context.layout();
        for (NativeStep step : steps) {
            Files.createDirectories(layout.path(step.workingDir()));
        }
        Files.createDirectories(layout.path(layout.build()));
        for (Map.Entry<String, String> file : planner().files(context).entrySet()) {
            TaskFileWriter.write(layout.path(file.getKey()), file.getValue());
        }
        Path tasks = layout.path(layout.inBuild("tasks.tsv"));
        TaskFileWriter.write(tasks, TaskFileWriter.render(steps, false));
        return tasks;
    }

    private static ToolSet tools(AbstractNativeMojo m, BuildSystem system, Platform platform) throws IOException {
        Map<String, String> explicit = new HashMap<>(m.tools == null ? Map.of() : m.tools);
        if (m.makeTool != null && !m.makeTool.isBlank()) {
            explicit.putIfAbsent("make", m.makeTool.trim());
        }
        Map<String, String> provisioned = m.cmakeProvision ? CMakeProvisioner.provision(m, platform) : Map.of();
        Map<String, String> requirements = m.toolchainRequirements == null ? Map.of() : m.toolchainRequirements;
        ToolSet tools = new ToolResolver(explicit, MavenToolchains.entries(m), requirements, provisioned, platform,
                System.getenv()).resolveAll(StepPlanner.toolNames(system));
        StepPlanner.toolNames(system).stream().filter(n -> explicit.containsKey(n) && !tools.has(n))
                .forEach(n -> m.getLog().warn("configured tool " + n + "=" + explicit.get(n) + " was not found"));
        return tools;
    }

    private static String classifier(AbstractNativeMojo m, BuildSystem system, Platform platform, ToolSet tools) {
        if (m.classifier != null && !m.classifier.isBlank()) {
            return m.classifier.trim();
        }
        String cc = tools.configured("cc").orElse("");
        String generator = m.cmakeGenerator == null ? "" : m.cmakeGenerator;
        boolean asksVisualStudio = platform.windows() && cc.isEmpty() && generator.isEmpty()
                && (system == BuildSystem.CMAKE || system == BuildSystem.CMAKE_PRESETS || system == BuildSystem.MESON);
        boolean installed = asksVisualStudio && MsvcLocator.installation(platform, System.getenv()).isPresent();
        return platform.classifier(platform.runtime(generator, cc, installed));
    }

    private static PlanContext context(AbstractNativeMojo m, Detection detection, ToolSet tools, Platform platform,
            String classifier, boolean fingerprintInput) {
        Path build = m.buildDirectory.toPath();
        Layout layout = Layout.of(m.project.getBasedir().toPath(), m.sourceDirectory.toPath(), build.resolve(classifier),
                build.resolve("stage").resolve(classifier), m.reportsDirectory.toPath());
        return new PlanContext(detection, layout, options(m), tools, platform, System.getenv(), fingerprintInput);
    }

    private static NativeOptions options(AbstractNativeMojo m) {
        return NativeOptions.builder().buildType(m.buildType).jobs(m.jobs).cmake(m.cmakeGenerator, m.cmakePreset)
                .make(m.makeTool, m.makeFile, m.makeTargets, m.makeTestTarget).autotools(m.autoreconf, m.prefix)
                .args(NativePhase.CONFIGURE, m.configureArgs).args(NativePhase.BUILD, m.buildArgs)
                .args(NativePhase.TEST, m.testArgs).args(NativePhase.INSTALL, m.installArgs).outputs(m.outputs).build();
    }

    private static void writeFingerprint(Log log, PlanContext context) throws IOException {
        Path file = context.layout().path(context.layout().inBuild(PlanContext.FINGERPRINT));
        Path directory = context.layout().basedir();
        String text = ToolchainFingerprint.render(context.platform(), context.tools(),
                tool -> ToolchainFingerprint.versionLine(tool, directory), context.environment());
        boolean wrote = ToolchainFingerprint.write(file, text);
        log.info((wrote ? "FINGERPRINT written " : "FINGERPRINT unchanged ") + file);
    }
}
