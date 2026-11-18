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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.nativebuild.engine.BuildSystem;
import org.apache.maven.plugins.nativebuild.engine.Detection;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.MsvcLocator;
import org.apache.maven.plugins.nativebuild.engine.NativeDependencies;
import org.apache.maven.plugins.nativebuild.engine.NativeNames;
import org.apache.maven.plugins.nativebuild.engine.NativeOptions;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.PlanContext;
import org.apache.maven.plugins.nativebuild.engine.Platform;
import org.apache.maven.plugins.nativebuild.engine.StepPlanner;
import org.apache.maven.plugins.nativebuild.engine.ToolResolver;
import org.apache.maven.plugins.nativebuild.engine.ToolSet;
import org.apache.maven.plugins.nativebuild.engine.ToolchainFingerprint;
import org.apache.maven.plugins.nativebuild.engine.WindowsPaths;

/** Builds a goal's {@link PlanContext} from the mojo parameters: tools, layout, options and native dependencies. */
final class SessionSetup {
    private SessionSetup() {}

    /** The plan context; configure also writes the fingerprint and unpacks dependencies. */
    static PlanContext context(AbstractNativeMojo m, Detection detection, NativeSession.Use use) throws IOException {
        Platform platform = Platform.current();
        Layout.requireInside(m.project.getBasedir().toPath(), m.buildDirectory.toPath());
        ToolSet tools = tools(m, detection.system(), platform, use);
        NativeOptions options = options(m);
        Layout layout = layout(m, platform, options, tools);
        PlanContext context = new PlanContext(detection, layout, options, tools, platform, System.getenv(), true,
                dependencies(m, layout, platform, use));
        if (use == NativeSession.Use.CONFIGURE) {
            writeFingerprint(m.getLog(), context);
        }
        return context;
    }

    /** The Windows runtime guessed from the parameters, before a configured build tells the real one. */
    static String guessRuntime(AbstractNativeMojo m, PlanContext c) {
        String cc = c.tools().configured("cc").orElse("");
        String generator = c.options().generator();
        BuildSystem system = c.system();
        boolean asksVisualStudio = c.platform().windows() && cc.isEmpty() && generator.isEmpty()
                && (system == BuildSystem.CMAKE || system == BuildSystem.CMAKE_PRESETS || system == BuildSystem.MESON);
        boolean installed = asksVisualStudio && MsvcLocator.installation(c.platform(), c.environment()).isPresent();
        return c.platform().runtime(generator, cc, installed);
    }

    private static ToolSet tools(AbstractNativeMojo m, BuildSystem system, Platform platform, NativeSession.Use use)
            throws IOException {
        Map<String, String> explicit = new HashMap<>(m.tools == null ? Map.of() : m.tools);
        if (m.makeTool != null && !m.makeTool.isBlank()) {
            explicit.putIfAbsent("make", m.makeTool.trim());
        }
        boolean provisions = m.cmakeProvision && (use == NativeSession.Use.CONFIGURE || use == NativeSession.Use.RUN);
        if (m.cmakeProvision && use == NativeSession.Use.DETECT) {
            m.getLog().info("PROVISION " + CMakeProvisioner.coordinates(m, platform) + " (configure resolves, verifies"
                    + " and unpacks it; detect never does)");
        }
        Map<String, String> provisioned = provisions ? CMakeProvisioner.provision(m, platform) : Map.of();
        Map<String, String> requirements = m.toolchainRequirements == null ? Map.of() : m.toolchainRequirements;
        return new ToolResolver(explicit, MavenToolchains.entries(m), requirements, provisioned, platform,
                System.getenv()).resolveAll(StepPlanner.toolNames(system));
    }

    private static Layout layout(AbstractNativeMojo m, Platform platform, NativeOptions o, ToolSet tools) {
        String classifier = m.classifier == null || m.classifier.isBlank() ? "" : NativeNames.classifier(m.classifier
                .trim());
        String key = platform.buildKey(classifier, o.generator(), tools.configured("cc").orElse(""));
        Path build = m.buildDirectory.toPath();
        return Layout.of(m.project.getBasedir().toPath(), m.sourceDirectory.toPath(), build.resolve(key),
                build.resolve("stage").resolve(key), m.reportsDirectory.toPath());
    }

    private static List<String> dependencies(AbstractNativeMojo m, Layout layout, Platform platform,
            NativeSession.Use use) throws IOException {
        List<NativeDependencies.Dependency> deps = m.project.getArtifacts().stream()
                .filter(a -> "zip".equals(a.getType()) && a.getFile() != null
                        && NativeDependencies.forPlatform(a.getClassifier(), platform))
                .map(a -> new NativeDependencies.Dependency(a.getGroupId(), a.getArtifactId(), a.getClassifier(),
                        a.getFile().toPath())).toList();
        String root = WindowsPaths.relative(layout.basedir(), m.buildDirectory.toPath().resolve("deps"));
        if (use != NativeSession.Use.CONFIGURE) {
            return NativeDependencies.folders(root, deps);
        }
        deps.forEach(d -> m.getLog().info("DEPENDENCY " + d.groupId() + ":" + d.artifactId() + ":zip:" + d.classifier()
                + " in " + root));
        return NativeDependencies.unpack(layout, root, deps);
    }

    private static NativeOptions options(AbstractNativeMojo m) {
        int threads = m.session == null || m.session.getRequest() == null ? 1
                : m.session.getRequest().getDegreeOfConcurrency();
        int jobs = m.jobs > 0 ? m.jobs : NativeOptions.defaultJobs(Runtime.getRuntime().availableProcessors(), threads);
        return NativeOptions.builder().buildType(m.buildType).jobs(jobs).cmake(m.cmakeGenerator, m.cmakePreset)
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
