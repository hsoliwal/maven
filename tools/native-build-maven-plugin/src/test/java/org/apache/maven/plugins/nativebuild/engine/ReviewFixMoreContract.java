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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * More review fixes: pinned CMake archives (HIGH 5), the build products digest (HIGH 2), the stage emptied by the
 * install that refills it, refused installs into unquotable paths, Bear with held files, start failures as errors and
 * log decoding.
 */
final class ReviewFixMoreContract {
    private ReviewFixMoreContract() {}

    static void run(Checks c) throws Exception {
        provisioning(c);
        digest(c);
        stageAndRefusal(c);
        smallParts(c);
        environment(c);
    }

    /** MEDIUM sh: make steps on Windows get Git's usr/bin before PATH; the shim applies step environments. */
    private static void environment(Checks c) throws Exception {
        Path base = c.dir("fix-env", Map.of("Makefile", "all:\n\techo\n"));
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", PlanFixtures.WINDOWS, options().build(), Map.of(),
                path("make", "C:/choco/make.exe"), path("sh", "C:/Git/usr/bin/sh.exe"));
        c.equal("make on Windows: sh's folder before PATH", Map.of("PATH", "C:/Git/usr/bin;${PATH}"),
                new MakePlanner().plan(ctx).get(0).environment());
        c.equal("expansion of an inherited variable", "x;" + System.getenv().getOrDefault("PATH", ""),
                CapturedProcess.expand("x;${PATH}"));
        NativeStep step = new NativeStep(NativePhase.BUILD, ReviewProcessContract.java(), ".", List.of("-cp",
                ReviewProcessContract.classes(), ArgvMain.class.getName(), "--env=NATIVE_FIX_PROBE"), List.of(),
                List.of(), Map.of("NATIVE_FIX_PROBE", "a b;${NATIVE_FIX_UNSET}c"));
        List<String> log = new java.util.ArrayList<>();
        ReviewProcessContract.execute(base, step, false, log);
        c.check("the shim applies the step environment " + log, log.contains("ENV<NATIVE_FIX_PROBE=a b;c>"));
    }

    /** A tampered archive next to an unpacked release (the marker also records bin/cmake's hash). */
    private static final Map<String, String> PROVISIONED = Map.of("cmake-binaries.jar", "tampered",
            "cmake-1/bin/cmake", "ELF");

    private static void provisioning(Checks c) throws Exception {
        String pin = "b392cf39462e5b208e8ef3644074db424ee214021e7fe8a83e1d87c6f444fe1f";
        c.equal("HIGH 5: the default CMake archive is pinned", Optional.of(pin),
                CMakeBinaries.expected(CMakeBinaries.DEFAULT_VERSION, "windows-x86_64", ""));
        c.equal("HIGH 5: another version has no pin", Optional.empty(),
                CMakeBinaries.expected("9.9.9", "windows-x86_64", null));
        Path jar = c.dir("fix-provision", PROVISIONED).resolve("cmake-binaries.jar");
        c.fails("HIGH 5: a tampered archive is refused", IOException.class,
                () -> CMakeBinaries.verify(jar, "x:cmake-binaries:1", Optional.of(pin)));
        c.fails("HIGH 5: an unpinned archive is refused", IOException.class,
                () -> CMakeBinaries.verify(jar, "x:cmake-binaries:1", Optional.empty()));
        String own = NativeDependencies.sha256(jar);
        CMakeBinaries.verify(jar, "x:cmake-binaries:1", Optional.of(own));
        CMakeBinaries.mark(jar.getParent(), "x:cmake-binaries:1", own);
        c.check("HIGH 5: the unpacked marker is checked against the hash",
                CMakeBinaries.unpacked(jar.getParent(), own) && !CMakeBinaries.unpacked(jar.getParent(), pin));
    }

    private static void digest(Checks c) throws Exception {
        Path base = c.dir("fix-digest", Map.of("src/a.c", "int a;\n", PlanFixtures.BUILD + "/obj/a.o", "o1",
                PlanFixtures.BUILD + "/libfx.a", "a1", PlanFixtures.BUILD + "/notes.txt", "n"));
        Layout layout = context(base, BuildSystem.CMAKE, "CMakeLists.txt", LINUX, options().build(), Map.of()).layout();
        c.check("HIGH 2: the first digest is written", BuildDigest.write(layout, layout.build()));
        String first = Files.readString(layout.path(layout.inBuild(BuildDigest.FILE)), StandardCharsets.UTF_8);
        c.check("HIGH 2: the digest lists objects and libraries only " + first, first.contains("libfx.a ")
                && first.contains("obj/a.o ") && !first.contains("notes.txt") && !first.contains(BuildDigest.FILE));
        c.check("HIGH 2: a no-op build leaves it unchanged", !BuildDigest.write(layout, layout.build()));
        Files.writeString(layout.path(PlanFixtures.BUILD + "/libfx.a"), "a2-relinked");
        c.check("HIGH 2: a relink changes it", BuildDigest.write(layout, layout.build()));
    }

    private static void stageAndRefusal(Checks c) throws Exception {
        Path base = c.dir("fix-stage", Map.of("Makefile", "all:\n\techo\ninstall:\n\tcp x $(DESTDIR)/bin\n"));
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"));
        List<NativeStep> steps = new MakePlanner().plan(ctx);
        StepExecutor executor = new StepExecutor(ctx.layout(), true, java.time.Duration.ofMinutes(1), l -> { }, l -> { });
        NativeStep install = steps.get(steps.size() - 1);
        c.equal("the install that refills the stage empties it first", ctx.layout().mixed(ctx.layout().stage()),
                executor.spec(install).clean());
        c.equal("other steps empty nothing", "", executor.spec(steps.get(0)).clean());
        refusal(c);
    }

    private static void refusal(Checks c) throws Exception {
        Path spaced = c.dir("fix-stage/sp ace", Map.of("Makefile", "all:\n\techo\ninstall:\n\tcp x $(DESTDIR)/bin\n",
                "configure", ""));
        PlanContext unsafe = context(spaced, BuildSystem.MAKE, "Makefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"));
        c.check("MEDIUM: no make install into a stage path with spaces", new MakePlanner().installRefusal(unsafe)
                .isPresent() && PlanFixtures.names(new MakePlanner().plan(unsafe)).stream().noneMatch(
                        n -> n.equals("native-install")));
        PlanContext autotools = context(spaced, BuildSystem.AUTOTOOLS, "configure", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"), path("sh", "/bin/sh"));
        c.check("MEDIUM: nor an Autotools one", !PlanFixtures.names(new AutotoolsPlanner().plan(autotools))
                .contains("native-install"));
    }

    private static void smallParts(Checks c) throws Exception {
        Path base = c.dir("fix-bear", Map.of("configure", ""));
        PlanContext ctx = context(base, BuildSystem.AUTOTOOLS, "configure", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"), path("bear", "/usr/bin/bear"));
        List<String> bear = CompileDbSource.bearCommand(ctx, base.resolve("cc.json"));
        c.check("LOW: Bear holds the generated files old " + bear, bear.containsAll(List.of("-B", "-o", "config.status")));
        c.equal("a tool that could not start is an error", TestSuiteReport.Status.ERRORED, ExitStatusReport.of("s",
                "make test step", "failed; " + StepShim.CANNOT_START + ": x", 0).cases().get(0).status());
        c.equal("log lines: UTF-8, else the native encoding", List.of("grüß", "x"), List.of(
                LogTail.decode("grüß".getBytes(StandardCharsets.UTF_8)), LogTail.decode(new byte[] {'x'})));
        c.check("a byte that is not UTF-8 still decodes", !LogTail.decode(new byte[] {(byte) 0xfc}).isEmpty());
        c.check("generated-file listing skips target", GeneratedFiles.list(context(base, BuildSystem.AUTOTOOLS,
                "configure", LINUX, options().build(), Map.of()).layout()).equals(Set.of("configure")));
    }
}
