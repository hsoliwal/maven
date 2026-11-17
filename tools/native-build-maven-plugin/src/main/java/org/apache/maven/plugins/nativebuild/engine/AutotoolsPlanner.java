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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Autotools, out of tree: {@code autoreconf -fi} in the source directory only when configure is missing (it has no
 * out-of-tree mode), then {@code sh <relative>/configure --prefix=...} with the build directory as working
 * directory, {@code make}, {@code make check} and {@code make install DESTDIR=<stage>}. The configure script is
 * named by its path relative to the build directory, so the generated makefiles stay relocatable.
 */
final class AutotoolsPlanner implements StepPlanner {
    private static final Pattern TESTS = Pattern.compile("(?m)^\\s*(?:TESTS|check_SCRIPTS)\\s*\\+?=");

    @Override
    public List<NativeStep> plan(PlanContext c) throws IOException {
        List<NativeStep> steps = new ArrayList<>();
        if (bootstrap(c)) {
            steps.add(bootstrapStep(c));
        }
        steps.add(configure(c));
        steps.add(make(c, NativePhase.BUILD, Steps.list("-j" + c.options().jobs(), c.options().targets()),
                Steps.buildInputs(c, statusFile(c)), c.options().outputs()));
        steps.add(make(c, NativePhase.TEST, Steps.list("-j" + c.options().jobs(), testTarget(c)),
                Steps.afterBuildInputs(c, statusFile(c)), testOutputs(c)));
        steps.add(make(c, NativePhase.INSTALL, Steps.list("install", "DESTDIR=" + c.layout().mixed(c.layout().stage())),
                Steps.afterBuildInputs(c, statusFile(c)), List.of(c.layout().stage())));
        return steps;
    }

    /** True when autoreconf runs: configure is missing (mode auto) or mode always. */
    static boolean bootstrap(PlanContext c) {
        String mode = c.options().autoreconf();
        boolean missing = c.system() == BuildSystem.AUTOTOOLS_BOOTSTRAP;
        if (missing && "never".equals(mode)) {
            throw new IllegalStateException("configure is missing and native.autotools.autoreconf=never");
        }
        return missing || "always".equals(mode);
    }

    /**
     * {@code make -n} arguments that print every compile command without running anything and without re-running
     * configure: {@code -B} considers all targets out of date, and {@code -o} holds the generated files old.
     */
    static List<String> dryRunArguments(PlanContext c) {
        String up = relativeSource(c);
        List<String> held = List.of("Makefile", "config.status", "config.h", Layout.join(up, "configure"),
                Layout.join(up, "Makefile.in"), Layout.join(up, "aclocal.m4"), Layout.join(up, "config.h.in"));
        List<String> args = Steps.list("-C", c.layout().build(), "-nwB");
        held.forEach(file -> args.addAll(List.of("-o", file)));
        return args;
    }

    private static NativeStep bootstrapStep(PlanContext c) {
        String src = c.layout().source();
        List<String> inputs = Steps.existing(c.layout(), Layout.join(src, "configure.ac"),
                Layout.join(src, "configure.in"), Layout.join(src, "Makefile.am"), Layout.join(src, "m4"),
                Layout.join(src, "acinclude.m4"));
        return new NativeStep(NativePhase.BOOTSTRAP, c.tool("autoreconf"), src, Steps.list("-fi",
                c.options().args(NativePhase.BOOTSTRAP)), inputs, List.of(Layout.join(src, "configure")));
    }

    private static NativeStep configure(PlanContext c) {
        NativeOptions o = c.options();
        List<String> args = Steps.list(Layout.join(relativeSource(c), "configure"), "--prefix=" + o.prefix(),
                Steps.compilerAssignments(c), o.args(NativePhase.CONFIGURE));
        List<String> inputs = Steps.list(Layout.join(c.layout().source(), "configure"), Steps.configureInputs(c));
        return new NativeStep(NativePhase.CONFIGURE, c.tool("sh"), c.layout().build(), args, inputs,
                List.of(statusFile(c)));
    }

    private static NativeStep make(PlanContext c, NativePhase phase, List<String> what, List<String> inputs,
            List<String> outputs) {
        List<String> args = Steps.list("-C", c.layout().build(), what, Steps.makeShell(c), c.options().args(phase));
        return new NativeStep(phase, c.tool("make"), ".", args, inputs, outputs);
    }

    private static String testTarget(PlanContext c) {
        return c.options().testTarget().isEmpty() ? "check" : c.options().testTarget();
    }

    private static List<String> testOutputs(PlanContext c) throws IOException {
        Path am = c.layout().path(Layout.join(c.layout().source(), "Makefile.am"));
        boolean automakeTests = Files.isRegularFile(am)
                && TESTS.matcher(Files.readString(am, StandardCharsets.UTF_8)).find();
        return automakeTests ? List.of(c.layout().inBuild("test-suite.log")) : List.of();
    }

    private static String statusFile(PlanContext c) {
        return c.layout().inBuild("config.status");
    }

    private static String relativeSource(PlanContext c) {
        return WindowsPaths.relative(c.layout().path(c.layout().build()), c.layout().path(c.layout().source()));
    }
}
