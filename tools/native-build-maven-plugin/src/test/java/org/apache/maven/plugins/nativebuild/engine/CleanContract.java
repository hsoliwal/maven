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
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEDIUM in-place builds and records: the clean goal's plan runs the makefile's clean, deletes what autoreconf
 * created (recorded outside target), a preset binaryDir outside target, an unchanged published compile database
 * and the plugin's stamps; it never touches a compile database someone else wrote.
 */
final class CleanContract {
    private static final String DB = "[{\"directory\":\"x\",\"file\":\"a.c\",\"arguments\":[\"cc\"]}]\n";

    private CleanContract() {}

    static void run(Checks c) throws Exception {
        make(c);
        preset(c);
        bootstrapRecord(c);
    }

    private static void make(Checks c) throws Exception {
        Path base = c.dir("clean-make", Map.of("Makefile", "all:\n\techo\nclean:\n\trm -f x\n", "compile_commands.json", DB,
                ".m3/polyglot-stamps/native-build.sha256", "x\n", ".m3/polyglot-stamps/other.sha256", "y\n"));
        CompileCommandsPublisher.place(base.resolve("compile_commands.json"), DB.getBytes(StandardCharsets.UTF_8),
                base.resolve(GeneratedFiles.RECORDS).resolve("compile_commands.json.sha256"));
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"));
        CleanPlan plan = CleanPlan.of(ctx, new MakePlanner());
        c.equal("clean: the makefile's own clean runs", List.of("/usr/bin/make", "-f", "Makefile", "clean"),
                plan.command());
        c.check("clean: owned compile database, its record and the plugin's stamp go " + plan.files(),
                plan.files().containsAll(List.of("compile_commands.json", ".m3/native-build/compile_commands.json.sha256",
                        ".m3/polyglot-stamps/native-build.sha256")));
        c.check("clean: other tools' stamps stay", !plan.files().contains(".m3/polyglot-stamps/other.sha256"));
        edited(c, base, ctx);
    }

    private static void edited(Checks c, Path base, PlanContext ctx) throws Exception {
        Files.writeString(base.resolve("compile_commands.json"), "[]\n");
        c.check("clean: a compile database edited since is kept",
                !CleanPlan.of(ctx, new MakePlanner()).files().contains("compile_commands.json"));
    }

    private static void preset(Checks c) throws Exception {
        String presets = "{\"version\":6,\"configurePresets\":[{\"name\":\"native\",\"binaryDir\":"
                + "\"${sourceDir}/out/build/${presetName}\"}]}";
        Path base = c.dir("clean-preset", Map.of("CMakeLists.txt", "", "CMakePresets.json", presets,
                "out/build/native/CMakeCache.txt", "CMAKE_GENERATOR:INTERNAL=Ninja\n"));
        PlanContext ctx = context(base, BuildSystem.CMAKE_PRESETS, "CMakePresets.json", LINUX,
                options().cmake("", "native").build(), Map.of(), path("cmake", "/opt/cmake"));
        c.equal("clean: a preset binaryDir outside target is deleted", List.of("out/build/native"),
                CleanPlan.of(ctx, new CMakePlanner()).trees());
        Files.delete(base.resolve("out/build/native/CMakeCache.txt"));
        c.equal("clean: a folder without CMakeCache.txt is not", List.of(), CleanPlan.of(ctx, new CMakePlanner()).trees());
    }

    private static void bootstrapRecord(Checks c) throws Exception {
        Path base = c.dir("clean-bootstrap", Map.of("configure.ac", "AC_INIT\n", "Makefile.am", ""));
        Layout layout = context(base, BuildSystem.AUTOTOOLS_BOOTSTRAP, "configure.ac", LINUX, options().build(),
                Map.of()).layout();
        Set<String> before = GeneratedFiles.list(layout);
        Files.writeString(base.resolve("configure"), "#!/bin/sh\n");
        Files.createDirectories(base.resolve("autom4te.cache"));
        Files.writeString(base.resolve("autom4te.cache/output.0"), "x");
        GeneratedFiles.record(layout, before);
        c.equal("bootstrap: exactly the new files and folders are recorded", List.of("autom4te.cache",
                "autom4te.cache/output.0", "configure"), GeneratedFiles.recorded(layout));
        PlanContext ctx = context(base, BuildSystem.AUTOTOOLS, "configure", LINUX, options().build(), Map.of());
        List<String> files = CleanPlan.of(ctx, new AutotoolsPlanner()).files();
        c.check("clean: generated files go before their folder, sources stay " + files,
                files.indexOf("autom4te.cache/output.0") < files.indexOf("autom4te.cache") && files.contains("configure")
                        && !files.contains("configure.ac"));
    }
}
