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

import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.BUILD;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.LINUX;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.STAGE;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.argv;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.configured;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exact argument lists and phase mapping of the CMake, preset, Meson and Ninja planners; no shell anywhere. */
final class PlannerContract {
    private static final String CMAKE = "/opt/cmake/bin/cmake";
    private static final String CTEST = "/opt/cmake/bin/ctest";

    private PlannerContract() {}

    static void run(Checks c) throws Exception {
        cmake(c);
        cmakeStamps(c);
        presets(c);
        meson(c);
        ninja(c);
        MakePlannerContract.run(c);
    }

    private static void cmake(Checks c) throws Exception {
        NativeOptions o = options().cmake("Ninja", "").args(NativePhase.CONFIGURE, List.of("-DNAME=a b;rm -rf /",
                "$(id)")).build();
        Path base = c.dir("plan-cmake", Map.of("CMakeLists.txt", "project(x C)\n", "src/a.c", "int a;\n"));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, o, Map.of(), path("cmake", CMAKE),
                path("ctest", CTEST), configured("cc", "/usr/bin/gcc"), path("ninja", "/usr/bin/ninja"));
        List<NativeStep> steps = new CMakePlanner().plan(ctx);
        c.equal("cmake tasks", List.of("native-configure", "native-build", "native-test", "native-install"),
                PlanFixtures.names(steps));
        c.equal("cmake Maven phases", List.of("generate-sources", "compile", "test", "prepare-package"),
                PlanFixtures.mavenPhases(steps));
        cmakeArgv(c, ctx, steps);
    }

    private static void cmakeArgv(Checks c, PlanContext ctx, List<NativeStep> steps) {
        c.equal("cmake configure argv (user args stay single, unquoted)", List.of(CMAKE, "-S", ".", "-B", BUILD, "-G",
                "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-DCMAKE_C_COMPILER=/usr/bin/gcc",
                "-DNAME=a b;rm -rf /", "$(id)"), argv(steps.get(0)));
        c.equal("cmake build argv", List.of(CMAKE, "--build", BUILD, "--parallel", "4", "--config", "Release"),
                argv(steps.get(1)));
        c.equal("ctest argv", List.of(CTEST, "--test-dir", BUILD, "-C", "Release", "--output-on-failure",
                "--output-junit", ctx.layout().mixed(BUILD + "/ctest-junit.xml")), argv(steps.get(2)));
        c.equal("cmake install argv (prefix under DESTDIR=<stage>)", List.of(CMAKE, "--install", BUILD, "--prefix",
                "/usr/local", "--config", "Release"), argv(steps.get(3)));
        c.check("cmake: no shell command", PlanFixtures.noShellCommand(steps));
    }

    private static void cmakeStamps(Checks c) throws Exception {
        Path base = c.dir("plan-cmake-stamps", Map.of("CMakeLists.txt", "project(x C)\n", "src/a.c", "int a;\n",
                "include/a.h", "", BUILD + "/toolchain.properties", "platform=x\n", ".hidden", ""));
        NativeOptions o = options().outputs(List.of(BUILD + "/x")).build();
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, o, Map.of(), path("cmake", CMAKE));
        List<NativeStep> steps = new CMakePlanner().plan(ctx);
        c.equal("configure inputs: descriptor and fingerprint", List.of("CMakeLists.txt", BUILD + "/toolchain.properties"),
                steps.get(0).inputs());
        c.equal("configure output", List.of(BUILD + "/CMakeCache.txt"), steps.get(0).outputs());
        c.equal("build inputs: source set (no target, no hidden) and cache", List.of("CMakeLists.txt", "include", "src",
                BUILD + "/CMakeCache.txt"), steps.get(1).inputs());
        c.equal("build outputs: declared", List.of(BUILD + "/x"), steps.get(1).outputs());
        c.equal("test inputs: declared outputs and the existing chain (fingerprint)", List.of(BUILD + "/x",
                BUILD + "/toolchain.properties"), steps.get(2).inputs());
        unstamped(c, base);
    }

    private static void unstamped(Checks c, Path base) throws Exception {
        PlanContext unstamped = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of());
        c.equal("build without declared outputs has no stamp", List.of(),
                new CMakePlanner().plan(unstamped).get(1).inputs());
    }

    private static void presets(Checks c) throws Exception {
        String presets = "{\"version\":6,\"configurePresets\":[{\"name\":\"native\",\"generator\":\"Ninja\","
                + "\"binaryDir\":\"${sourceDir}/target/native/preset-${presetName}\"},{\"name\":\"inherits\","
                + "\"inherits\":\"native\"}],\"buildPresets\":[{\"name\":\"native\",\"configurePreset\":\"native\"}]}";
        Path base = c.dir("plan-presets", Map.of("CMakeLists.txt", "project(x C)\n", "CMakePresets.json", presets));
        PlanContext ctx = context(base, BuildSystem.CMAKE_PRESETS, "CMakePresets.json", LINUX,
                options().cmake("", "native").build(), Map.of(), path("cmake", CMAKE));
        List<NativeStep> steps = new CMakePlanner().plan(ctx);
        c.equal("preset configure argv", List.of(CMAKE, "--preset", "native"), argv(steps.get(0)));
        c.equal("preset binaryDir expanded", List.of("target/native/preset-native/CMakeCache.txt"), steps.get(0).outputs());
        c.equal("preset build argv", List.of(CMAKE, "--build", "--preset", "native"), argv(steps.get(1)));
        PlanContext inherited = context(base, BuildSystem.CMAKE_PRESETS, "CMakePresets.json", LINUX,
                options().cmake("", "inherits").build(), Map.of(), path("cmake", CMAKE));
        c.equal("inherited binaryDir is followed, with the child's name", List.of(
                "target/native/preset-inherits/CMakeCache.txt"), new CMakePlanner().plan(inherited).get(0).outputs());
    }

    private static void meson(Checks c) throws Exception {
        Path base = c.dir("plan-meson", Map.of("meson.build", "project('x', 'c')\n"));
        PlanContext ctx = context(base, BuildSystem.MESON, "meson.build", LINUX, options().buildType("RelWithDebInfo")
                .build(), Map.of(), path("meson", "/usr/bin/meson"), configured("cc", "/usr/bin/gcc"));
        List<NativeStep> steps = new MesonPlanner().plan(ctx);
        String b = ctx.layout().mixed(BUILD);
        c.equal("meson setup argv", List.of("/usr/bin/meson", "setup", b, "--buildtype=debugoptimized", "--native-file",
                ctx.layout().mixed(BUILD + "/meson-native.ini")), argv(steps.get(0)));
        c.equal("meson native file", Map.of(BUILD + "/meson-native.ini", "[binaries]\nc = '/usr/bin/gcc'\n"),
                new MesonPlanner().files(ctx));
        c.equal("meson test junit output", List.of(BUILD + "/meson-logs/testlog.junit.xml"), steps.get(2).outputs());
        c.equal("meson install argv", List.of("/usr/bin/meson", "install", "-C", b, "--destdir",
                ctx.layout().mixed(STAGE)), argv(steps.get(3)));
        mesonReconfigure(c, base, ctx);
    }

    private static void mesonReconfigure(Checks c, Path base, PlanContext ctx) throws Exception {
        Files.createDirectories(base.resolve(BUILD + "/meson-private"));
        Files.writeString(base.resolve(BUILD + "/meson-private/coredata.dat"), "x");
        c.check("meson reconfigures an existing build directory",
                new MesonPlanner().plan(ctx).get(0).arguments().contains("--reconfigure"));
    }

    private static void ninja(Checks c) throws Exception {
        Path base = c.dir("plan-ninja", Map.of("build.ninja", "rule cc\n"));
        PlanContext ctx = context(base, BuildSystem.NINJA, "build.ninja", LINUX, options().build(), Map.of(),
                path("ninja", "/usr/bin/ninja"));
        List<NativeStep> steps = new NinjaPlanner().plan(ctx);
        c.equal("ninja build argv, no test step", List.of(List.of("/usr/bin/ninja", "-C", ".", "-j", "4")),
                steps.stream().map(PlanFixtures::argv).toList());
        PlanContext test = context(base, BuildSystem.NINJA, "build.ninja", LINUX, options().make("", "", List.of(),
                "check").build(), Map.of(), path("ninja", "/usr/bin/ninja"));
        c.equal("ninja test target argv", List.of("/usr/bin/ninja", "-C", ".", "check"),
                argv(new NinjaPlanner().plan(test).get(1)));
    }
}
