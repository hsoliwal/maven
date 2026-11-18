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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CMake: {@code cmake -S -B} (or {@code --preset}) with the compile database switched on, {@code cmake --build}, {@code
 * ctest --output-junit} and {@code cmake --install --prefix <prefix>} with {@code DESTDIR=<stage>} in its environment,
 * so files land in {@code <stage>/<prefix>} and an absolute {@code install(... DESTINATION /etc/...)} lands in {@code
 * <stage>/etc/...} instead of the real system (see {@link InstallManifest} for the check afterwards). {@code --config}
 * is always passed so multi-config generators (Visual Studio, Ninja Multi-Config) build the requested type;
 * single-config generators ignore it. With a preset, build and test use {@code --preset} only when CMakePresets.json
 * (or CMakeUserPresets.json) declares a build or test preset of that name; a project with only configure presets is
 * built and tested in the preset's binaryDir. Native dependencies are passed as {@code CMAKE_PREFIX_PATH}.
 */
final class CMakePlanner implements StepPlanner {
    /** CTest's JUnit output, in the plugin's build directory. */
    static final String JUNIT = "ctest-junit.xml";

    @Override
    public List<NativeStep> plan(PlanContext c) throws IOException {
        Optional<String> build = knownBuildDirectory(c);
        String dir = build.orElse(c.layout().build());
        return List.of(configure(c, build), build(c, dir, build), test(c, dir, build), install(c, dir, build));
    }

    @Override
    public String buildDirectory(PlanContext c) throws IOException {
        return knownBuildDirectory(c).orElse(c.layout().build());
    }

    private static Optional<String> knownBuildDirectory(PlanContext c) throws IOException {
        return preset(c) ? CMakePresets.binaryDir(c.layout(), c.options().preset())
                : Optional.of(c.layout().build());
    }

    private static boolean preset(PlanContext c) {
        return !c.options().preset().isEmpty();
    }

    private static NativeStep configure(PlanContext c, Optional<String> build) {
        NativeOptions o = c.options();
        String src = c.layout().source();
        List<String> inputs = Steps.configureInputs(c, Layout.join(src, "CMakeLists.txt"),
                Layout.join(src, "CMakePresets.json"), Layout.join(src, "CMakeUserPresets.json"), Layout.join(src, "cmake"));
        String prefixPath = Steps.joined(c, "", ";");
        List<String> deps = prefixPath.isEmpty() ? List.of() : List.of("-DCMAKE_PREFIX_PATH=" + prefixPath);
        List<String> args = preset(c) ? Steps.list("--preset", o.preset(), deps, o.args(NativePhase.CONFIGURE))
                : Steps.list("-S", src, "-B", build.orElseThrow(), generator(o), "-DCMAKE_BUILD_TYPE=" + o.buildType(),
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", compilers(c), deps, o.args(NativePhase.CONFIGURE));
        List<String> outputs = build.map(b -> List.of(Layout.join(b, "CMakeCache.txt"))).orElse(List.of());
        return new NativeStep(NativePhase.CONFIGURE, c.tool("cmake"), preset(c) ? src : ".", args, inputs, outputs);
    }

    private static NativeStep build(PlanContext c, String dir, Optional<String> known) throws IOException {
        NativeOptions o = c.options();
        List<String> targets = o.targets().isEmpty() ? List.of() : Steps.list("--target", o.targets());
        boolean byPreset = presetOf(c, "buildPresets", known);
        List<String> args = byPreset ? Steps.list("--build", "--preset", o.preset(), targets, o.args(NativePhase.BUILD))
                : Steps.list("--build", dir, "--parallel", String.valueOf(o.jobs()), "--config", o.buildType(),
                        targets, o.args(NativePhase.BUILD));
        String cache = known.map(b -> Layout.join(b, "CMakeCache.txt")).orElse("");
        List<String> inputs = cache.isEmpty() ? Steps.buildInputs(c) : Steps.buildInputs(c, cache);
        return new NativeStep(NativePhase.BUILD, c.tool("cmake"), byPreset ? c.layout().source() : ".", args, inputs,
                o.outputs());
    }

    private static NativeStep test(PlanContext c, String dir, Optional<String> known) throws IOException {
        NativeOptions o = c.options();
        String junit = c.layout().inBuild(JUNIT);
        String abs = c.layout().mixed(junit);
        boolean byPreset = presetOf(c, "testPresets", known);
        List<String> args = byPreset ? Steps.list("--preset", o.preset(), "--output-junit", abs, o.args(NativePhase.TEST))
                : Steps.list("--test-dir", dir, "-C", o.buildType(), "--output-on-failure", "--output-junit", abs,
                        o.args(NativePhase.TEST));
        return new NativeStep(NativePhase.TEST, c.tool("ctest"), byPreset ? c.layout().source() : ".", args,
                Steps.afterBuildInputs(c, state(dir)), List.of(junit));
    }

    private static NativeStep install(PlanContext c, String dir, Optional<String> known) throws IOException {
        if (preset(c) && known.isEmpty()) {
            throw unknownDirectory(c, "install");
        }
        NativeOptions o = c.options();
        List<String> args = Steps.list("--install", dir, "--prefix", o.prefix(), "--config", o.buildType(),
                o.args(NativePhase.INSTALL));
        return new NativeStep(NativePhase.INSTALL, c.tool("cmake"), ".", args, Steps.afterBuildInputs(c, state(dir)),
                List.of(c.layout().stage()), Map.of("DESTDIR", c.layout().mixed(c.layout().stage())));
    }

    /**
     * True when build or test goes through {@code --preset}: a preset of that kind and name is declared. Otherwise
     * the preset's binaryDir must be known (declared plainly), or planning fails with what to add.
     */
    private static boolean presetOf(PlanContext c, String kind, Optional<String> known) throws IOException {
        if (!preset(c) || CMakePresets.declares(c.layout(), kind, c.options().preset())) {
            return preset(c);
        }
        if (known.isEmpty()) {
            throw unknownDirectory(c, kind);
        }
        return false;
    }

    private static IllegalStateException unknownDirectory(PlanContext c, String what) {
        return new IllegalStateException("CMake preset " + c.options().preset() + " has no plain binaryDir, so " + what
                + " cannot find its build tree; add a binaryDir such as ${sourceDir}/target/native/${presetName}"
                + " (or a " + what + " preset of that name)");
    }

    private static String state(String dir) {
        return Layout.join(dir, "CMakeCache.txt");
    }

    private static List<String> generator(NativeOptions o) {
        return o.generator().isEmpty() ? List.of() : List.of("-G", o.generator());
    }

    private static List<String> compilers(PlanContext c) {
        boolean ninja = c.options().generator().contains("Ninja");
        return Steps.list(c.tools().configured("cc").map(cc -> "-DCMAKE_C_COMPILER=" + cc),
                c.tools().configured("cxx").map(cxx -> "-DCMAKE_CXX_COMPILER=" + cxx),
                ninja ? c.tools().configured("ninja").map(n -> "-DCMAKE_MAKE_PROGRAM=" + n) : Optional.empty());
    }
}
