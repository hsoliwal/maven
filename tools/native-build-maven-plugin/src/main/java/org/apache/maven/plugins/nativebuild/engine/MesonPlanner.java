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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Meson: {@code meson setup} (with {@code --reconfigure} once the build directory exists), {@code meson compile},
 * {@code meson test} (Meson writes meson-logs/testlog.junit.xml itself) and {@code meson install --destdir}.
 * Configured compilers go into a generated native file instead of environment variables, because polyglot starts
 * tools with the Maven process's environment.
 */
final class MesonPlanner implements StepPlanner {
    /** The generated native file, in the build directory. */
    static final String NATIVE_FILE = "meson-native.ini";
    /** Meson's JUnit output, relative to the build directory. */
    static final String JUNIT = "meson-logs/testlog.junit.xml";

    @Override
    public List<NativeStep> plan(PlanContext c) throws IOException {
        Layout l = c.layout();
        String build = l.mixed(l.build());
        NativeOptions o = c.options();
        return List.of(configure(c, build),
                step(c, NativePhase.BUILD, Steps.list("compile", "-C", build, "-j", String.valueOf(o.jobs()),
                        o.targets()), Steps.buildInputs(c, l.inBuild("build.ninja")), o.outputs()),
                step(c, NativePhase.TEST, Steps.list("test", "-C", build), Steps.afterBuildInputs(c, state(c)),
                        List.of(l.inBuild(JUNIT))),
                step(c, NativePhase.INSTALL, Steps.list("install", "-C", build, "--destdir", l.mixed(l.stage())),
                        Steps.afterBuildInputs(c, state(c)), List.of(l.stage())));
    }

    @Override
    public Map<String, String> files(PlanContext c) {
        return nativeFile(c).map(content -> Map.of(c.layout().inBuild(NATIVE_FILE), content)).orElse(Map.of());
    }

    /** Meson's build type for a CMake-style one. */
    static String buildType(String cmakeType) {
        return switch (cmakeType) {
            case "Debug" -> "debug";
            case "RelWithDebInfo" -> "debugoptimized";
            case "MinSizeRel" -> "minsize";
            case "Release" -> "release";
            default -> cmakeType.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static NativeStep configure(PlanContext c, String build) {
        Layout l = c.layout();
        boolean configured = Files.exists(l.path(l.inBuild("meson-private/coredata.dat")));
        List<String> nativeFile = nativeFile(c).isPresent()
                ? List.of("--native-file", l.mixed(l.inBuild(NATIVE_FILE))) : List.of();
        List<String> deps = c.dependencies().isEmpty() ? List.of() : List.of("-Dpkg_config_path="
                + Steps.joined(c, "lib/pkgconfig", ","), "-Dcmake_prefix_path=" + Steps.joined(c, "", ","));
        List<String> args = Steps.list("setup", configured ? "--reconfigure" : "", build,
                "--buildtype=" + buildType(c.options().buildType()), nativeFile, deps,
                c.options().args(NativePhase.CONFIGURE));
        List<String> inputs = Steps.configureInputs(c, Layout.join(l.source(), "meson.build"),
                Layout.join(l.source(), "meson.options"), Layout.join(l.source(), "meson_options.txt"));
        return new NativeStep(NativePhase.CONFIGURE, c.tool("meson"), l.source(), args, inputs,
                List.of(l.inBuild("build.ninja")));
    }

    private static String state(PlanContext c) {
        return c.layout().inBuild("meson-private/coredata.dat");
    }

    private static NativeStep step(PlanContext c, NativePhase phase, List<String> args, List<String> inputs,
            List<String> outputs) {
        return new NativeStep(phase, c.tool("meson"), c.layout().source(), Steps.list(args, c.options().args(phase)),
                inputs, outputs);
    }

    private static Optional<String> nativeFile(PlanContext c) {
        Optional<String> cc = c.tools().configured("cc");
        Optional<String> cxx = c.tools().configured("cxx");
        if (cc.isEmpty() && cxx.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("[binaries]\n" + cc.map(p -> "c = " + quote(p) + "\n").orElse("")
                + cxx.map(p -> "cpp = " + quote(p) + "\n").orElse(""));
    }

    private static String quote(String path) {
        return "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
