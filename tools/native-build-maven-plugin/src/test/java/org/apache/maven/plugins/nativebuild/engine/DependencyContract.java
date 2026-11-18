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
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.WINDOWS;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.argv;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * MEDIUM native dependency consumption: zips for this platform are unpacked once per content into {@code
 * deps/<groupId>/<artifactId>/<classifier>} and passed to CMake, Meson, Autotools and Make; their marker is a configure
 * input.
 */
final class DependencyContract {
    private static final String DEP = "target/native/deps/org.example/fxcore";

    private DependencyContract() {}

    static void run(Checks c) throws Exception {
        platformMatch(c);
        unpack(c);
        planners(c);
    }

    private static void platformMatch(Checks c) {
        c.equal("dependency classifiers for this platform", List.of(true, true, false, false), List.of(
                NativeDependencies.forPlatform("windows-x86_64-mingw", WINDOWS),
                NativeDependencies.forPlatform("windows-x86_64", WINDOWS),
                NativeDependencies.forPlatform("linux-x86_64", WINDOWS),
                NativeDependencies.forPlatform("windows-x86_64x", WINDOWS)));
    }

    private static void unpack(Checks c) throws Exception {
        Path base = c.dir("deps-unpack", Map.of("stage/bin/fxtool", "x", "stage/include/fx.h", "int fx;\n"));
        Path zip = base.resolve("fxcore.zip");
        DeterministicZip.write(zip, base.resolve("stage"), List.of("bin/fxtool", "include/fx.h"), 0L);
        Layout layout = Layout.of(base, base, base.resolve(BUILD), base.resolve(STAGE), base.resolve("target/r"));
        List<NativeDependencies.Dependency> deps = List.of(new NativeDependencies.Dependency("org.example", "fxcore",
                "linux-x86_64", zip));
        List<String> folders = NativeDependencies.unpack(layout, "target/native/deps", deps);
        c.equal("dependency folder, one per classifier", List.of(DEP + "/linux-x86_64"), folders);
        Path header = base.resolve(folders.get(0)).resolve("include/fx.h");
        c.check("dependency unpacked with its marker", Files.isRegularFile(header)
                && Files.isRegularFile(base.resolve(folders.get(0)).resolve(NativeDependencies.MARKER)));
        Files.writeString(header, "changed\n");
        NativeDependencies.unpack(layout, "target/native/deps", deps);
        c.equal("an unchanged zip is not unpacked again", "changed\n", Files.readString(header));
    }

    private static void planners(Checks c) throws Exception {
        Path base = c.dir("deps-plan", Map.of("CMakeLists.txt", "", "configure", "", "Makefile", "all:\n\techo\n",
                "meson.build", "", DEP + "/" + NativeDependencies.MARKER, "abc\n"));
        String prefix = WindowsPaths.mixed(base.resolve(DEP));
        NativeStep cmake = new CMakePlanner().plan(context(base, BuildSystem.CMAKE)).get(0);
        c.check("cmake gets CMAKE_PREFIX_PATH " + argv(cmake), argv(cmake).contains("-DCMAKE_PREFIX_PATH=" + prefix));
        c.check("the dependency marker is a configure input " + cmake.inputs(),
                cmake.inputs().contains(DEP + "/" + NativeDependencies.MARKER));
        NativeStep meson = new MesonPlanner().plan(context(base, BuildSystem.MESON)).get(0);
        c.check("meson gets pkg_config_path and cmake_prefix_path " + argv(meson), argv(meson).containsAll(List.of(
                "-Dpkg_config_path=" + prefix + "/lib/pkgconfig", "-Dcmake_prefix_path=" + prefix)));
        makeAndAutotools(c, base, prefix);
    }

    private static void makeAndAutotools(Checks c, Path base, String prefix) throws Exception {
        NativeStep configure = new AutotoolsPlanner().plan(context(base, BuildSystem.AUTOTOOLS)).get(0);
        c.check("configure gets CPPFLAGS, LDFLAGS and PKG_CONFIG_PATH " + argv(configure), argv(configure).containsAll(
                List.of("CPPFLAGS=-I" + prefix + "/include", "LDFLAGS=-L" + prefix + "/lib",
                        "PKG_CONFIG_PATH=" + prefix + "/lib/pkgconfig")));
        NativeStep make = new MakePlanner().plan(context(base, BuildSystem.MAKE)).get(0);
        c.equal("make gets them in its environment", "-I" + prefix + "/include", make.environment().get("CPPFLAGS"));
    }

    private static PlanContext context(Path base, BuildSystem system) {
        PlanContext plain = PlanFixtures.context(base, system, "x", LINUX, options().build(), Map.of(),
                path("cmake", "/opt/cmake"), path("meson", "/usr/bin/meson"), path("make", "/usr/bin/make"),
                path("sh", "/bin/sh"));
        return new PlanContext(plain.detection(), plain.layout(), plain.options(), plain.tools(), plain.platform(),
                plain.environment(), true, List.of(DEP));
    }
}
