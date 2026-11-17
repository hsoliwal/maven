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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * compile_commands.json: shell words without a shell, the make dry-run parser on the Autotools line (with $depbase,
 * escaped quotes and continued lines) and a Windows make line, ninja compdb filtering, where the database is found
 * and published, and that a foreign file is kept.
 */
final class CompileDbContract {
    private CompileDbContract() {}

    static void run(Checks c) throws Exception {
        words(c);
        autotoolsDryRun(c);
        windowsDryRun(c);
        ninjaFilter(c);
        sources(c);
        publish(c);
    }

    private static void words(Checks c) {
        c.equal("commands split outside quotes", List.of("a 'b;c'", "d \"e && f\"", "g `h | i`", "j 2>&1", "k"),
                PosixWords.commands("a 'b;c' && d \"e && f\"; g `h | i` || j 2>&1 | k"));
        c.equal("words unquote and keep $ and back quotes", List.of("a", "b c", "d\"e", "f g", "`x y`", "$v", "-DN=\"q\""),
                PosixWords.words("a 'b c' \"d\\\"e\" f\\ g `x y` $v -DN=\\\"q\\\""));
    }

    private static void autotoolsDryRun(Checks c) throws Exception {
        Path build = c.dir("compdb-autotools/target/native/linux-x86_64", Map.of());
        String dir = WindowsPaths.mixed(build);
        String output = "make: Entering directory '" + dir + "'\n"
                + "depbase=`echo src/core.o | sed 's|[^/]*$|.deps/&|;s|\\.o$||'`;\\\n"
                + "gcc -DPACKAGE_NAME=\\\"fxauto\\\" -DPACKAGE_STRING=\\\"fxauto\\ 1.0\\\" -I. -I../../..  "
                + "-I../../../include -DFX_BUILD=1   -g -O2 -MT src/core.o -MD -MP -MF $depbase.Tpo -c -o src/core.o "
                + "../../../src/core.c &&\\\nmv -f $depbase.Tpo $depbase.Po\nrm -f libfxcore.a\n"
                + "ar cru libfxcore.a src/core.o \ngcc  -g -O2   -o fxtool src/main.o libfxcore.a \n"
                + "make: Leaving directory '" + dir + "'\n";
        List<CompileDatabase.Entry> entries = MakeDryRunParser.parse(output, build.getParent(),
                MakeDryRunParser.compilers(List.of()));
        c.equal("autotools: one compile entry (link, ar, mv skipped)", 1, entries.size());
        autotoolsEntry(c, build, dir, entries.get(0));
    }

    private static void autotoolsEntry(Checks c, Path build, String dir, CompileDatabase.Entry e) {
        c.equal("autotools entry", List.of(dir, WindowsPaths.mixed(build.resolve("../../../src/core.c")), "src/core.o"),
                List.of(e.directory(), e.file(), e.output()));
        c.equal("autotools arguments: quotes resolved, dependency options dropped", List.of("gcc",
                "-DPACKAGE_NAME=\"fxauto\"", "-DPACKAGE_STRING=\"fxauto 1.0\"", "-I.", "-I../../..", "-I../../../include",
                "-DFX_BUILD=1", "-g", "-O2", "-c", "-o", "src/core.o", "../../../src/core.c"), e.arguments());
    }

    private static void windowsDryRun(Checks c) throws Exception {
        Path project = c.dir("compdb-make", Map.of());
        String dir = WindowsPaths.mixed(project);
        String output = "make-shell=C:/Program Files/Git/usr/bin/sh.exe\nmake: Entering directory '" + dir + "'\n"
                + "C:/mingw/bin/gcc.exe -O2 -Iinclude -DFX_BUILD=1 -c src/core.c -o src/core.o\n"
                + "make[1]: Entering directory '" + dir + "/sub'\nC:/mingw/bin/g++.exe -c x.cpp\n"
                + "make[1]: Leaving directory '" + dir + "/sub'\nC:/mingw/bin/gcc.exe -o fxtool src/core.o src/main.o\n"
                + "mycc -c y.c\n";
        List<CompileDatabase.Entry> entries = MakeDryRunParser.parse(output, project,
                MakeDryRunParser.compilers(List.of("C:/tools/mycc.exe")));
        c.equal("windows make entries (nested directory, configured compiler)", List.of(dir + "/src/core.c",
                dir + "/sub/x.cpp", dir + "/y.c"), entries.stream().map(CompileDatabase.Entry::file).toList());
        c.equal("directory restored after Leaving", dir, entries.get(2).directory());
    }

    private static void ninjaFilter(Checks c) {
        String compdb = "[{\"directory\":\"/b\",\"command\":\"gcc -c ../a.c\",\"file\":\"../a.c\",\"output\":\"a.o\"},"
                + "{\"directory\":\"/b\",\"command\":\"gcc -o x a.o\",\"file\":\"a.o\",\"output\":\"x\"},"
                + "{\"directory\":\"/b\",\"command\":\"\",\"file\":\"all\"}]";
        List<?> kept = (List<?>) JsonText.parse(CompileDatabase.sourcesOnly(compdb));
        c.equal("ninja compdb: sources only", 1, kept.size());
    }

    private static void sources(Checks c) throws Exception {
        c.equal("auto modes", List.of(CompileDbSource.Mode.CMAKE, CompileDbSource.Mode.NINJA, CompileDbSource.Mode.BEAR,
                CompileDbSource.Mode.MAKE_DRY_RUN, CompileDbSource.Mode.MESON), List.of(
                CompileDbSource.effective(CompileDbSource.Mode.AUTO, BuildSystem.CMAKE_PRESETS, true),
                CompileDbSource.effective(CompileDbSource.Mode.AUTO, BuildSystem.NINJA, false),
                CompileDbSource.effective(CompileDbSource.Mode.AUTO, BuildSystem.MAKE, true),
                CompileDbSource.effective(CompileDbSource.Mode.AUTO, BuildSystem.AUTOTOOLS, false),
                CompileDbSource.effective(CompileDbSource.Mode.parse("meson"), BuildSystem.MAKE, false)));
        placement(c);
    }

    private static void placement(Checks c) throws Exception {
        Path base = c.dir("compdb-cmake", Map.of("CMakeLists.txt", "x\n"));
        PlanContext ctx = PlanFixtures.context(base, BuildSystem.CMAKE, "CMakeLists.txt", PlanFixtures.LINUX,
                PlanFixtures.options().build(), Map.of());
        CompileDbSource.Result none = CompileDbSource.produce(ctx, new CMakePlanner(), CompileDbSource.Mode.CMAKE,
                Duration.ofSeconds(5));
        c.check("no database from a Visual Studio-like build", none.database().isEmpty());
        Path db = base.resolve(PlanFixtures.BUILD + "/compile_commands.json");
        Files.createDirectories(db.getParent());
        Files.writeString(db, "[]\n");
        c.equal("cmake database found in the build directory", db, CompileDbSource.produce(ctx, new CMakePlanner(),
                CompileDbSource.Mode.CMAKE, Duration.ofSeconds(5)).database().orElseThrow());
    }

    private static void publish(Checks c) throws Exception {
        Path base = c.dir("compdb-publish", Map.of("target/native/x/compile_commands.json", "[{\"a\":1}]\n"));
        Path db = base.resolve("target/native/x/compile_commands.json");
        Path root = base.resolve("compile_commands.json");
        Path owner = base.resolve("target/native/compile_commands.json.sha256");
        c.equal("first publish writes", CompileCommandsPublisher.Outcome.WROTE, publish(db, root, owner));
        c.equal("root copy is the database", Files.readString(db), Files.readString(root));
        c.equal("same content is unchanged", CompileCommandsPublisher.Outcome.UNCHANGED, publish(db, root, owner));
        Files.writeString(db, "[{\"a\":2}]\n");
        c.equal("own file is replaced", CompileCommandsPublisher.Outcome.WROTE, publish(db, root, owner));
        Files.writeString(root, "[{\"hand\":\"written\"}]\n");
        c.equal("foreign file is kept", CompileCommandsPublisher.Outcome.KEPT_FOREIGN, publish(db, root, owner));
        c.equal("foreign content untouched", "[{\"hand\":\"written\"}]\n", Files.readString(root));
        clangd(c, base, db, root, owner);
    }

    private static void clangd(Checks c, Path base, Path db, Path root, Path owner) throws Exception {
        Path clangd = base.resolve(".clangd");
        CompileCommandsPublisher.publish(CompileCommandsPublisher.Publish.parse("clangd-config"), db, clangd,
                base.resolve("target/native/.clangd.sha256"), "target/native/x");
        c.equal(".clangd points into the build tree", "CompileFlags:\n  CompilationDatabase: target/native/x\n",
                Files.readString(clangd, StandardCharsets.UTF_8));
        c.equal("none publishes nothing", CompileCommandsPublisher.Outcome.SKIPPED, CompileCommandsPublisher.publish(
                CompileCommandsPublisher.Publish.NONE, db, root, owner, ""));
    }

    private static CompileCommandsPublisher.Outcome publish(Path db, Path root, Path owner) throws Exception {
        return CompileCommandsPublisher.publish(CompileCommandsPublisher.Publish.COPY, db, root, owner, "");
    }
}
