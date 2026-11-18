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
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.argv;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Round-3 review findings, without processes: the Autotools prefix and every stage path stay inside the stage (HIGH),
 * CMake installs through DESTDIR with a manifest check, clean gets the make PATH, the one-owner check reads every
 * polyglot manifest location, and split dependency zips unpack into one folder each (MEDIUM).
 */
final class Round3Contract {
    private Round3Contract() {}

    static void run(Checks c) throws Exception {
        prefixNames(c);
        stageRoot(c);
        cmakeDestdir(c);
        installManifest(c);
        cleanPath(c);
        ownerManifests(c);
        splitDependencies(c);
        Round3ReportContract.run(c);
        Round3ProcessContract.run(c);
    }

    private static void prefixNames(Checks c) {
        c.fails("HIGH prefix: '..' segments are refused", IllegalArgumentException.class,
                () -> NativeNames.prefix("/usr/../.."));
        c.fails("HIGH prefix: a leading '/..' is refused", IllegalArgumentException.class,
                () -> NativeNames.prefix("/../../../../../secret"));
        c.fails("HIGH prefix: '.' and trailing-dot segments are refused", IllegalArgumentException.class,
                () -> NativeNames.prefix("/opt/x./."));
        c.equal("prefix: plain absolute paths stay", List.of("/usr/local", "/opt/fx-1.2", "/"),
                List.of(NativeNames.prefix("/usr/local"), NativeNames.prefix("/opt/fx-1.2"), NativeNames.prefix("/")));
        c.fails("LOW provisioning: a version with '..' is refused", IllegalArgumentException.class,
                () -> NativeNames.coordinate("9.9.9/../..", "native.cmake.version"));
        c.fails("LOW provisioning: a group id with a path is refused", IllegalArgumentException.class,
                () -> NativeNames.coordinate("evil/../x", "native.cmake.provisionGroupId"));
        c.equal("provisioning: plain coordinates stay", "3.31.5-b1",
                NativeNames.coordinate("3.31.5-b1", "native.cmake.version"));
    }

    private static void stageRoot(Checks c) throws Exception {
        Path base = c.dir("r3-root", Map.of("stage/usr/local/lib/libfx.a", "a", "secret/id_rsa_fake", "KEY"));
        Path stage = base.resolve("stage");
        c.fails("HIGH prefix: the stage root never leaves the stage", IllegalArgumentException.class,
                () -> StageCollector.root(stage, "/../secret"));
        c.equal("prefix: the stage root is stage/<prefix>", stage.resolve("usr/local"),
                StageCollector.root(stage, "/usr/local"));
        c.fails("HIGH stage: a declared output outside the project is refused", IllegalArgumentException.class,
                () -> StageCollector.copyOutputs(stage, List.of("../secret/id_rsa_fake"), base.resolve("out")));
        c.check("HIGH stage: nothing was copied", !Files.exists(base.resolve("out/bin/id_rsa_fake")));
    }

    private static void cmakeDestdir(Checks c) throws Exception {
        Path base = c.dir("r3-cmake-destdir", Map.of("CMakeLists.txt", ""));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of(),
                path("cmake", "/opt/cmake"), path("ctest", "/opt/ctest"));
        NativeStep install = new CMakePlanner().plan(ctx).get(3);
        String stage = ctx.layout().mixed(ctx.layout().stage());
        c.equal("MEDIUM cmake install: DESTDIR is the stage", stage, install.environment().get("DESTDIR"));
        List<String> args = argv(install);
        c.check("MEDIUM cmake install: --prefix is the configured prefix, not the stage " + args,
                args.get(args.indexOf("--prefix") + 1).equals("/usr/local") && !args.contains(stage));
    }

    private static void installManifest(Checks c) throws Exception {
        Path base = c.dir("r3-manifest", Map.of("CMakeLists.txt", ""));
        PlanContext ctx = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of());
        Layout l = ctx.layout();
        String inside = "/usr/local/lib/libfx.a\nC:/Users/x/cmake-escaped/fx.h\n/etc/fx.conf";
        String escaped = "/usr/local/../../../../escaped/fx.h";
        Files.createDirectories(l.path(l.build()));
        Files.writeString(l.path(l.inBuild("install_manifest.txt")), inside + "\n" + escaped);
        c.equal("MEDIUM cmake install: manifest entries outside the stage are found", List.of(escaped),
                InstallManifest.outside(l, l.build()));
        plannedDestinations(c, l);
    }

    /** Found in the end-to-end run: a '..' destination must be refused before cmake --install writes it. */
    private static void plannedDestinations(Checks c, Layout l) throws Exception {
        String rel = "${CMAKE_INSTALL_PREFIX}/../../../../../../escaped-rel";
        Files.writeString(l.path(l.inBuild("cmake_install.cmake")), "file(INSTALL DESTINATION \"${CMAKE_INSTALL_PREFIX}"
                + "/include\" TYPE FILE FILES \"a.h\")\nfile(INSTALL DESTINATION \"C:/Users/x/cmake-escaped\" TYPE FILE)\n"
                + "file(INSTALL DESTINATION \"" + rel + "\" TYPE FILE FILES \"a.h\")\n");
        Files.createDirectories(l.path(l.inBuild("sub")));
        Files.writeString(l.path(l.inBuild("sub/cmake_install.cmake")), "file(INSTALL DESTINATION \"/../../../up\")\n");
        c.equal("MEDIUM cmake install: destinations that leave the stage are found before the install runs",
                List.of(rel, "/../../../up"), InstallManifest.planned(l, l.build(), "/usr/local"));
    }

    private static void cleanPath(Checks c) throws Exception {
        Path base = c.dir("r3-clean-path", Map.of("Makefile", "all:\n\techo\nclean:\n\trm -f x\n"));
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", WINDOWS, options().build(), Map.of(),
                path("make", "C:/tools/make.exe"), path("sh", "C:/Program Files/Git/usr/bin/sh.exe"));
        CleanPlan plan = CleanPlan.of(ctx, new MakePlanner());
        c.equal("MEDIUM clean: make clean gets Git's usr/bin before PATH, like make steps",
                "C:/Program Files/Git/usr/bin;${PATH}", plan.environment().get("PATH"));
    }

    private static void ownerManifests(Checks c) throws Exception {
        Path base = c.dir("r3-owner", Map.of("build-native.tsv", "M3-TASKS-V1\ntask\tcmake-validate\tcmake\t.\n"));
        Path absolute = base.resolve("other/x.tsv");
        List<Path> found = OwnerCheck.manifests(base, List.of("build-native.tsv", absolute.toString()), null);
        c.check("MEDIUM owner: configured manifests are read " + found, found.containsAll(List.of(
                base.resolve("build-native.tsv"), absolute, base.resolve(OwnerCheck.MANIFEST))));
        c.check("MEDIUM owner: the m3.polyglot.manifest property is read",
                OwnerCheck.manifests(base, List.of(), "custom.tsv").contains(base.resolve("custom.tsv")));
        c.fails("MEDIUM owner: a renamed manifest with cmake rows is refused", IllegalStateException.class,
                () -> OwnerCheck.require(base, found));
    }

    private static void splitDependencies(Checks c) throws Exception {
        Path base = c.dir("r3-split", Map.of("lib/lib/libfx.a", "a", "hdr/include/fx.h", "int fx;\n"));
        Layout layout = Layout.of(base, base, base.resolve(PlanFixtures.BUILD), base.resolve(PlanFixtures.STAGE),
                base.resolve("target/r"));
        DeterministicZip.write(base.resolve("lib.zip"), base.resolve("lib"), List.of("lib/libfx.a"), 0L);
        DeterministicZip.write(base.resolve("hdr.zip"), base.resolve("hdr"), List.of("include/fx.h"), 0L);
        List<NativeDependencies.Dependency> deps = List.of(new NativeDependencies.Dependency("org.example", "fx",
                "windows-x86_64-mingw-lib", base.resolve("lib.zip")), new NativeDependencies.Dependency("org.example",
                "fx", "windows-x86_64-mingw-headers", base.resolve("hdr.zip")));
        List<String> folders = NativeDependencies.unpack(layout, "target/native/deps", deps);
        c.check("MEDIUM split: each classifier gets its own folder " + folders, folders.stream().distinct().count() == 2);
        c.check("MEDIUM split: both zips stay unpacked", Files.isRegularFile(layout.path(folders.get(0)).resolve(
                "lib/libfx.a")) && Files.isRegularFile(layout.path(folders.get(1)).resolve("include/fx.h")));
    }
}
