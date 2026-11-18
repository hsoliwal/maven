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
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.WINDOWS;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.argv;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Regression checks for the adversarial review findings that the engine can show without Maven: Windows argument
 * lists, chained stamps, chained test and install inputs, make target injection, DESTDIR, zip bytes and modes,
 * configure-only presets, the WSL bash launcher, pinned tools, empty CTest runs and Bear.
 */
final class ReviewRegressionContract {
    private static final String CMAKE = "/opt/cmake/bin/cmake";

    private ReviewRegressionContract() {}

    static void run(Checks c) throws Exception {
        ReviewProcessContract.run(c);
        chainedInputs(c);
        makeTargets(c);
        destdir(c);
        zipBytes(c);
        presetsWithoutBuildPresets(c);
        windowsShell(c);
        pinnedTool(c);
        emptyCtest(c);
        c.equal("LOW: Bear is opt-in (auto picks the dry run)", CompileDbSource.Mode.MAKE_DRY_RUN,
                CompileDbSource.effective(CompileDbSource.Mode.AUTO, BuildSystem.MAKE, true));
    }

    /** HIGH 2: test and install also depend on the configure state, the fingerprint and the build products. */
    private static void chainedInputs(Checks c) throws Exception {
        Path base = c.dir("review-chain", Map.of("CMakeLists.txt", "project(x C)\n", "src/a.c", "int a;\n",
                BUILD + "/toolchain.properties", "platform=x\n", BUILD + "/CMakeCache.txt", "CMAKE_GENERATOR=Ninja\n",
                BUILD + "/" + "build-products.digest", "a.o 1 1\n"));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of(),
                path("cmake", CMAKE));
        List<NativeStep> steps = new CMakePlanner().plan(ctx);
        List<String> chain = List.of(BUILD + "/CMakeCache.txt", BUILD + "/toolchain.properties",
                BUILD + "/build-products.digest");
        c.check("HIGH 2: test inputs chain configure state, fingerprint and build products " + steps.get(2).inputs(),
                steps.get(2).inputs().containsAll(chain));
        c.check("HIGH 2: install inputs chain configure state, fingerprint and build products " + steps.get(3).inputs(),
                steps.get(3).inputs().containsAll(chain));
    }

    /** MEDIUM: make and ninja targets are names, never assignments, options or functions. */
    private static void makeTargets(Checks c) throws Exception {
        Path base = c.dir("review-targets", Map.of("Makefile", "all:\n\techo\ncheck:\n\techo\n", "build.ninja", ""));
        c.fails("MEDIUM: make target with := $(shell)", IllegalArgumentException.class, () -> new MakePlanner().plan(
                context(base, BuildSystem.MAKE, "Makefile", LINUX, options().make("", "", List.of("all",
                        "x:=$(shell echo pwned > PWNED.txt)"), "").build(), Map.of(), path("make", "/usr/bin/make"))));
        c.fails("MEDIUM: make test target --eval", IllegalArgumentException.class, () -> new MakePlanner().plan(
                context(base, BuildSystem.MAKE, "Makefile", LINUX, options().make("", "", List.of(),
                        "--eval=$(shell echo pwned)").build(), Map.of(), path("make", "/usr/bin/make"))));
        c.fails("MEDIUM: ninja target -t", IllegalArgumentException.class, () -> new NinjaPlanner().plan(
                context(base, BuildSystem.NINJA, "build.ninja", LINUX, options().make("", "", List.of("-t"), "clean")
                        .build(), Map.of(), path("ninja", "/usr/bin/ninja"))));
    }

    /** MEDIUM: an install rule that ignores DESTDIR would install outside the project, so it is not run. */
    private static void destdir(Checks c) throws Exception {
        Path base = c.dir("review-destdir", Map.of("Makefile", "PREFIX ?= /usr/local\nall:\n\techo\ninstall: all\n"
                + "\tcp x $(PREFIX)/bin\n"));
        List<NativeStep> steps = new MakePlanner().plan(context(base, BuildSystem.MAKE, "Makefile", LINUX,
                options().build(), Map.of(), path("make", "/usr/bin/make")));
        c.check("MEDIUM: no install step for a makefile that ignores DESTDIR " + PlanFixtures.names(steps),
                !PlanFixtures.names(steps).contains("native-install"));
    }

    /** MEDIUM: zip bytes do not depend on the time zone; executables keep their mode. */
    private static void zipBytes(Checks c) throws Exception {
        Path stage = c.dir("review-zip", Map.of("bin/tool", "#!/bin/sh\n", "include/a.h", "int a;\n"));
        long time = DeterministicZip.epoch("2026-01-01T00:00:00Z");
        List<byte[]> zips = new ArrayList<>();
        for (String zone : List.of("UTC", "Asia/Kolkata", "America/Los_Angeles")) {
            zips.add(zipIn(zone, stage, time));
        }
        c.check("MEDIUM: same zip bytes in three time zones", java.util.Arrays.equals(zips.get(0), zips.get(1))
                && java.util.Arrays.equals(zips.get(0), zips.get(2)));
        c.equal("MEDIUM: zip keeps unix modes (bin/tool 0755, header 0644)", List.of(0755, 0644),
                ZipCentral.modes(zips.get(0)));
    }

    private static byte[] zipIn(String zone, Path stage, long time) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            Path zip = stage.resolveSibling("review-" + zone.replace('/', '-') + ".zip");
            DeterministicZip.write(zip, stage, List.of("bin/tool", "include/a.h"), time);
            return Files.readAllBytes(zip);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** MEDIUM: a preset project with only configure presets builds and tests the preset's binary directory. */
    private static void presetsWithoutBuildPresets(Checks c) throws Exception {
        String presets = "{\"version\":6,\"configurePresets\":[{\"name\":\"native\",\"generator\":\"Ninja\","
                + "\"binaryDir\":\"${sourceDir}/out/build/${presetName}\"}]}";
        Path base = c.dir("review-presets", Map.of("CMakeLists.txt", "project(x C)\n", "CMakePresets.json", presets,
                "out/build/native/CMakeCache.txt", "", "src/a.c", ""));
        PlanContext ctx = context(base, BuildSystem.CMAKE_PRESETS, "CMakePresets.json", LINUX,
                options().cmake("", "native").build(), Map.of(), path("cmake", CMAKE), path("ctest", "/opt/ctest"));
        List<NativeStep> steps = new CMakePlanner().plan(ctx);
        c.equal("MEDIUM: configure-only preset builds its binaryDir", List.of(CMAKE, "--build", "out/build/native",
                "--parallel", "4", "--config", "Release"), argv(steps.get(1)));
        c.check("MEDIUM: configure-only preset tests with --test-dir " + argv(steps.get(2)),
                argv(steps.get(2)).containsAll(List.of("--test-dir", "out/build/native")));
        c.equal("a preset binaryDir inside the sources is not a source", List.of("CMakeLists.txt", "CMakePresets.json",
                "src", "out/build/native/CMakeCache.txt"), steps.get(2).inputs());
    }

    /** MEDIUM: sh never resolves to the WSL launcher in System32. */
    private static void windowsShell(Checks c) throws Exception {
        Path root = c.dir("review-systemroot", Map.of("System32/bash.exe", "", "System32/sh.exe", ""));
        Map<String, String> env = Map.of("PATH", root.resolve("System32").toString(), "SystemRoot", root.toString(),
                "PATHEXT", ".EXE");
        java.util.Optional<ToolSet.Tool> sh = new ToolResolver(Map.of(), List.of(), Map.of(), Map.of(), WINDOWS, env)
                .resolve("sh");
        c.check("MEDIUM: sh is not the System32 WSL launcher " + sh.map(ToolSet.Tool::path).orElse("(none)"),
                sh.map(t -> !t.path().contains("System32")).orElse(true));
    }

    /** MEDIUM: a configured tool that does not exist fails instead of falling back to PATH. */
    private static void pinnedTool(Checks c) {
        Map<String, String> env = Map.of("PATH", System.getenv().getOrDefault("PATH", ""));
        c.fails("MEDIUM: missing pinned cmake fails", IllegalStateException.class, () -> new ToolResolver(
                Map.of("cmake", "C:/pinned/cmake-3.28/bin/cmake.exe"), List.of(), Map.of(), Map.of(), LINUX, env)
                .resolveAll(List.of("cmake", "ctest")));
    }

    /** LOW: CTest that found no tests reports no test case, not one passing case. */
    private static void emptyCtest(Checks c) throws Exception {
        Path base = c.dir("review-notests", Map.of("CMakeLists.txt", "project(x C)\n", BUILD + "/ctest-junit.xml",
                "<?xml version=\"1.0\"?>\n<testsuite name=\"(empty)\" tests=\"0\" failures=\"0\"></testsuite>\n"));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of());
        TestSuiteReport report = TestReports.collect(ctx, new CMakePlanner(), "native.cmake.x", "", 0.1);
        c.equal("LOW: no tests found gives zero cases", 0, report.cases().size());
    }
}
