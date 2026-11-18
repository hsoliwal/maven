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

import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.LINUX;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.WINDOWS;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The pieces behind the review fixes: Windows quoting rules, the step file, the real runtime detection, build
 * directory keys, classifier checks, the one-owner rule, the clean plan, and native dependencies in every planner.
 */
final class ReviewFixContract {
    private ReviewFixContract() {}

    static void run(Checks c) throws Exception {
        quoting(c);
        stepSpec(c);
        runtime(c);
        keysAndNames(c);
        owner(c);
        CleanContract.run(c);
        DependencyContract.run(c);
        ReviewFixMoreContract.run(c);
    }

    private static void quoting(Checks c) {
        c.equal("quote: plain argument unchanged", "-DX=1", WindowsArgs.quote("-DX=1"));
        c.equal("quote: empty argument", "\"\"", WindowsArgs.quote(""));
        c.equal("quote: inner quote escaped", "\"say \\\"hi\\\"\"", WindowsArgs.quote("say \"hi\""));
        c.equal("quote: trailing backslash doubled inside quotes", "\"sp ace\\\\\"", WindowsArgs.quote("sp ace\\"));
        c.equal("quote: backslashes before a quote doubled", "\"a\\\\\\\"b\"", WindowsArgs.quote("a\\\"b"));
        c.equal("quote: lone trailing backslash without spaces unchanged", "tail\\", WindowsArgs.quote("tail\\"));
        c.fails("batch: & refused", IllegalArgumentException.class,
                () -> WindowsArgs.encode(List.of("C:/x/fake.cmd", "a&b")));
        c.fails("batch: % refused", IllegalArgumentException.class,
                () -> WindowsArgs.encode(List.of("C:/x/fake.BAT", "%PATH%")));
        c.equal("batch: a path with spaces and parentheses is quoted", List.of("C:/x/fake.cmd",
                "\"C:/Program Files (x86)/a b\""), WindowsArgs.encode(List.of("C:/x/fake.cmd",
                "C:/Program Files (x86)/a b")));
    }

    private static void stepSpec(Checks c) {
        StepSpec spec = new StepSpec("C:/t/make.exe", List.of("-f", "", "x y\"z"), Map.of("CPPFLAGS", "-I/a b",
                "PKG_CONFIG_PATH", "/p"), "C:/w/target/native/stage/x");
        c.equal("step file round trip", spec, StepSpec.parse(spec.render()));
        c.fails("step file refuses a line break", IllegalArgumentException.class,
                () -> new StepSpec("make", List.of("a\nb"), Map.of(), ""));
    }

    private static void runtime(Checks c) throws Exception {
        c.equal("cmake GNU is mingw", Platform.MINGW, RuntimeDetector.cmakeRuntime("set(CMAKE_C_COMPILER_ID \"GNU\")\n"));
        c.equal("cmake MSVC is msvc", Platform.MSVC, RuntimeDetector.cmakeRuntime("set(CMAKE_C_COMPILER_ID \"MSVC\")\n"));
        c.equal("cmake clang simulating MSVC is msvc", Platform.MSVC, RuntimeDetector.cmakeRuntime(
                "set(CMAKE_C_COMPILER_ID \"Clang\")\nset(CMAKE_C_SIMULATE_ID \"MSVC\")\n"));
        c.equal("cmake MinGW clang is mingw", Platform.MINGW, RuntimeDetector.cmakeRuntime(
                "set(CMAKE_C_COMPILER_ID \"Clang\")\nset(CMAKE_C_SIMULATE_ID \"\")\n"));
        runtimeFiles(c);
    }

    private static void runtimeFiles(Checks c) throws Exception {
        Path base = c.dir("fix-runtime", Map.of("CMakeLists.txt", "", PlanFixtures.BUILD
                + "/CMakeFiles/4.3.1/CMakeCCompiler.cmake", "set(CMAKE_C_COMPILER_ID \"Clang\")\n"
                + "set(CMAKE_C_SIMULATE_ID \"MSVC\")\n", "meson/a.json", "{\"host\":{\"c\":{\"id\":\"gcc\","
                + "\"linker_id\":\"ld.bfd\"}}}", "meson/b.json", "{\"host\":{\"c\":{\"id\":\"clang\",\"linker_id\":"
                + "\"lld-link\"}}}"));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", WINDOWS, options().cmake("Ninja", "")
                .build(), Map.of());
        c.equal("a configured CMake tree tells the runtime, not the parameters", java.util.Optional.of(Platform.MSVC),
                RuntimeDetector.runtime(ctx, new CMakePlanner()));
        c.equal("meson gcc is mingw, clang with lld-link is msvc", List.of(Platform.MINGW, Platform.MSVC), List.of(
                RuntimeDetector.meson(base.resolve("meson/a.json")).orElse(""),
                RuntimeDetector.meson(base.resolve("meson/b.json")).orElse("")));
        c.equal("no runtime suffix off Windows", java.util.Optional.empty(), RuntimeDetector.runtime(context(base,
                BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of()), new CMakePlanner()));
    }

    private static void keysAndNames(Checks c) {
        c.equal("build key names generator and compiler", "windows-x86_64-ninja-gcc",
                WINDOWS.buildKey("", "Ninja", "C:/mingw/bin/gcc.exe"));
        c.equal("build key for another generator differs", "windows-x86_64-mingw-makefiles",
                WINDOWS.buildKey("", "MinGW Makefiles", ""));
        c.equal("build key of a configured classifier", "custom", LINUX.buildKey("custom", "", ""));
        c.equal("os-maven-plugin vocabulary", List.of("linux-aarch_64", "osx-aarch_64", "windows-x86_64"),
                List.of(Platform.of("Linux", "aarch64").classifier(""), Platform.of("Mac OS X", "aarch64").classifier(""),
                        WINDOWS.classifier("")));
        c.fails("classifier ../.. refused", IllegalArgumentException.class, () -> NativeNames.classifier("../.."));
        c.fails("classifier with a slash refused", IllegalArgumentException.class, () -> NativeNames.classifier("a/b"));
        c.equal("classifier accepted", "windows-x86_64-mingw", NativeNames.classifier("windows-x86_64-mingw"));
        c.fails("generator with a quote refused", IllegalArgumentException.class,
                () -> options().cmake("Ninja\" -DX=1", "").build());
        c.equal("jobs divided by reactor threads", List.of(8, 1, 8), List.of(NativeOptions.defaultJobs(32, 4),
                NativeOptions.defaultJobs(2, 4), NativeOptions.defaultJobs(8, 1)));
    }

    private static void owner(Checks c) throws Exception {
        Path mavenized = c.dir("fix-owner", Map.of("m3-build.tsv", "M3-POLYGLOT-TASKS-V1\ntask\tcmake-validate\tcmake\t.\n"
                + "task\tnpm-compile\tnpm\t.\n"));
        c.equal("Mavenizer native rows found", List.of("cmake-validate (cmake)"), OwnerCheck.competing(mavenized));
        c.fails("one owner per descriptor", IllegalStateException.class, () -> OwnerCheck.require(mavenized));
        Path other = c.dir("fix-owner-other", Map.of("m3-build.tsv", "M3-POLYGLOT-TASKS-V1\ntask\tnpm-compile\tnpm\t.\n"));
        c.equal("other tasks do not compete", List.of(), OwnerCheck.competing(other));
        c.equal("no manifest, no conflict", List.of(), OwnerCheck.competing(c.dir("fix-owner-none", Map.of())));
    }
}
