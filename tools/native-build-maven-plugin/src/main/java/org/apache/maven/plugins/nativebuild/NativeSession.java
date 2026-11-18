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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.nativebuild.engine.BuildDetector;
import org.apache.maven.plugins.nativebuild.engine.BuildDigest;
import org.apache.maven.plugins.nativebuild.engine.Detection;
import org.apache.maven.plugins.nativebuild.engine.GeneratedFiles;
import org.apache.maven.plugins.nativebuild.engine.InstallManifest;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.NativeNames;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.NativeStep;
import org.apache.maven.plugins.nativebuild.engine.OwnerCheck;
import org.apache.maven.plugins.nativebuild.engine.PlanContext;
import org.apache.maven.plugins.nativebuild.engine.PolyglotEngine;
import org.apache.maven.plugins.nativebuild.engine.RuntimeDetector;
import org.apache.maven.plugins.nativebuild.engine.StepExecutor;
import org.apache.maven.plugins.nativebuild.engine.StepPlanner;

/**
 * One goal's view of the native build: detection, resolved tools, layout and the planned steps, and running steps
 * through the polyglot engine (see {@link StepExecutor}) with their output in Maven's log.
 */
final class NativeSession {
    /** What a goal does with the session. */
    enum Use {
        /** Prints the plan: provisions nothing, writes nothing, reports a competing root manifest. */
        DETECT,
        /** Configures: writes the toolchain fingerprint and unpacks native dependencies. */
        CONFIGURE,
        /** Runs planned steps. */
        RUN,
        /** Plans the clean goal after target is gone: provisions nothing, writes nothing. */
        CLEAN
    }

    private final AbstractNativeMojo mojo;
    private final Detection detection;
    private final PlanContext context;
    private final List<NativeStep> steps;

    private NativeSession(AbstractNativeMojo mojo, Detection detection, PlanContext context, List<NativeStep> steps) {
        this.mojo = mojo;
        this.detection = detection;
        this.context = context;
        this.steps = steps;
    }

    /** Detects and plans for {@code use}. */
    static NativeSession open(AbstractNativeMojo m, Use use) throws IOException {
        m.checkTimeout();
        Detection detection = BuildDetector.detect(m.sourceDirectory.toPath(), m.buildSystem);
        if (use == Use.CONFIGURE || use == Use.DETECT || !detection.found()) {
            m.getLog().info(detection.line());
        } else {
            m.getLog().debug(detection.line());
        }
        if (!detection.found()) {
            return new NativeSession(m, detection, null, List.of());
        }
        if (use == Use.CONFIGURE || use == Use.RUN) {
            OwnerCheck.require(m.project.getBasedir().toPath(), PolyglotManifests.of(m));
        }
        PlanContext context = SessionSetup.context(m, detection, use);
        return new NativeSession(m, detection, context, StepPlanner.forSystem(detection.system()).plan(context));
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

    /**
     * The attached artifacts' classifier: the configured one, else {@code os-arch} plus, on Windows, the runtime the
     * configured build really uses ({@link RuntimeDetector}), guessed from the parameters only before configure.
     */
    String classifier() throws IOException {
        if (mojo.classifier != null && !mojo.classifier.isBlank()) {
            return NativeNames.classifier(mojo.classifier.trim());
        }
        String runtime = RuntimeDetector.runtime(context, planner()).orElseGet(() -> SessionSetup.guessRuntime(mojo,
                context));
        return context.platform().classifier(runtime);
    }

    /** Runs the planned steps of the given phases, in plan order; true when there was any. */
    boolean run(NativePhase... phases) throws IOException, InterruptedException {
        List<NativeStep> selected = steps.stream().filter(s -> Arrays.asList(phases).contains(s.phase())).toList();
        StepExecutor executor = new StepExecutor(context.layout(), mojo.incremental, timeout(), output(),
                line -> log().debug(line));
        executor.prepare(steps);
        for (NativeStep step : selected) {
            run(executor, step);
        }
        return !selected.isEmpty();
    }

    private void run(StepExecutor executor, NativeStep step) throws IOException, InterruptedException {
        Layout layout = context.layout();
        mojo.info(step.name() + ": " + step.display());
        Set<String> before = step.phase() == NativePhase.BOOTSTRAP ? GeneratedFiles.list(layout) : Set.of();
        if (cmakeInstall(step)) {
            InstallManifest.refusePlanned(layout, planner().buildDirectory(context), context.options().prefix());
        }
        executor.run(step);
        if (step.phase() == NativePhase.BOOTSTRAP) {
            GeneratedFiles.record(layout, before);
        }
        after(step);
    }

    /** After a step: the build's product digest, the CMake install's manifest check. */
    private void after(NativeStep step) throws IOException {
        if (step.phase() == NativePhase.BUILD) {
            BuildDigest.write(context.layout(), planner().buildDirectory(context));
        }
        if (cmakeInstall(step)) {
            checkInstall(step);
        }
    }

    private boolean cmakeInstall(NativeStep step) {
        return step.phase() == NativePhase.INSTALL && context.system().family().equals("cmake");
    }

    /** Fails a CMake install that wrote outside the stage; the install then runs again next time. */
    private void checkInstall(NativeStep step) throws IOException {
        try {
            InstallManifest.check(context.layout(), planner().buildDirectory(context));
        } catch (IOException escaped) {
            PolyglotEngine.forget(context.layout().basedir(), step.name());
            throw escaped;
        }
    }

    /** Tool output into Maven's log; under {@code -T} each line names its module. */
    private Consumer<String> output() {
        return mojo::info;
    }
}
