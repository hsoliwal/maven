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
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.configured;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.path;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exact argument lists of the Make and Autotools planners, on Windows (SHELL=) and Linux. */
final class MakePlannerContract {
    private static final String MAKEFILE = "CC ?= cc\nall: x\ncheck: all\n\t./x\ninstall: all\n\tcp x $(DESTDIR)/bin\n"
            + ".PHONY: all check\n";
    private static final String SH = "C:/Git/usr/bin/sh.exe";
    private static final String MAKE = "C:/choco/bin/make.exe";
    private static final String GCC = "C:/mingw/bin/gcc.exe";

    private MakePlannerContract() {}

    static void run(Checks c) throws Exception {
        makeWindows(c);
        makeTargets(c);
        nmake(c);
        autotools(c);
        autotoolsModes(c);
    }

    private static void makeWindows(Checks c) throws Exception {
        Path base = c.dir("plan-make", Map.of("Makefile", MAKEFILE));
        PlanContext ctx = context(base, BuildSystem.MAKE, "Makefile", WINDOWS, options().build(), Map.of(),
                path("make", MAKE), path("sh", SH), configured("cc", GCC));
        List<NativeStep> steps = new MakePlanner().plan(ctx);
        String shell = "SHELL=" + SH;
        c.equal("make tasks", List.of("native-build", "native-test", "native-install"), PlanFixtures.names(steps));
        c.equal("make build argv (Windows)", List.of(MAKE, "-f", "Makefile", "-j4", shell, "CC=" + GCC), argv(steps.get(0)));
        c.equal("make check argv", List.of(MAKE, "-f", "Makefile", "check", shell, "CC=" + GCC), argv(steps.get(1)));
        c.equal("make install argv", List.of(MAKE, "-f", "Makefile", "install", "DESTDIR=" + ctx.layout().mixed(STAGE),
                shell, "CC=" + GCC), argv(steps.get(2)));
        makeLinux(c, base, ctx, steps);
    }

    private static void makeLinux(Checks c, Path base, PlanContext ctx, List<NativeStep> steps) throws Exception {
        c.equal("make dry-run arguments", List.of("-f", "Makefile", "-nwB", "SHELL=" + SH, "CC=" + GCC),
                MakePlanner.dryRunArguments(ctx));
        c.check("make: no shell command", PlanFixtures.noShellCommand(steps));
        PlanContext linux = context(base, BuildSystem.MAKE, "Makefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"), path("sh", "/bin/sh"));
        c.equal("make build argv (Linux: no SHELL, no CC unless configured)", List.of("/usr/bin/make", "-f", "Makefile",
                "-j4"), argv(new MakePlanner().plan(linux).get(0)));
    }

    private static void makeTargets(Checks c) throws Exception {
        c.equal("declared test target", "test", MakePlanner.declared("all:\ntest: all\n", "check", "test"));
        c.equal(".PHONY is not a rule", "", MakePlanner.declared(".PHONY: check install\n", "check", "install"));
        c.equal("assignment is not a rule", "", MakePlanner.declared("check := 1\n", "check"));
        c.equal("double-colon rule", "install", MakePlanner.declared("all install:: x\n", "install"));
        Path base = c.dir("plan-make-plain", Map.of("GNUmakefile", "all:\n\tcc -c a.c\n"));
        PlanContext ctx = context(base, BuildSystem.MAKE, "GNUmakefile", LINUX, options().build(), Map.of(),
                path("make", "/usr/bin/make"));
        c.equal("no check, test or install: build only", List.of("native-build"),
                PlanFixtures.names(new MakePlanner().plan(ctx)));
        c.equal("detected makefile name used", "GNUmakefile", argv(new MakePlanner().plan(ctx).get(0)).get(2));
    }

    private static void nmake(Checks c) throws Exception {
        Path base = c.dir("plan-nmake", Map.of("Makefile.win", "all:\n"));
        NativeOptions o = options().make("nmake", "Makefile.win", List.of(), "").build();
        PlanContext bare = context(base, BuildSystem.MAKE, "", WINDOWS, o, Map.of(), configured("make", "C:/VS/nmake.exe"));
        c.fails("nmake outside a developer environment", IllegalStateException.class, () -> new MakePlanner().plan(bare));
        PlanContext dev = context(base, BuildSystem.MAKE, "", WINDOWS, o, Map.of("VCINSTALLDIR", "C:/VS/VC/"),
                configured("make", "C:/VS/nmake.exe"));
        c.equal("nmake argv", List.of("C:/VS/nmake.exe", "/NOLOGO", "/F", "Makefile.win"),
                argv(new MakePlanner().plan(dev).get(0)));
    }

    private static void autotools(Checks c) throws Exception {
        Path base = c.dir("plan-autotools", Map.of("configure.ac", "AC_INIT([x],[1])\n", "Makefile.am",
                "bin_PROGRAMS = x\nTESTS = x\n", "src/x.c", "int main(void){return 0;}\n"));
        PlanContext ctx = context(base, BuildSystem.AUTOTOOLS_BOOTSTRAP, "configure.ac", LINUX, options().build(),
                Map.of(), path("autoreconf", "/usr/bin/autoreconf"), path("sh", "/bin/sh"), path("make", "/usr/bin/make"),
                configured("cc", "/usr/bin/gcc"));
        List<NativeStep> steps = new AutotoolsPlanner().plan(ctx);
        c.equal("autotools tasks", List.of("native-bootstrap", "native-configure", "native-build", "native-test",
                "native-install"), PlanFixtures.names(steps));
        c.equal("autoreconf argv and outputs", List.of("/usr/bin/autoreconf", "-fi", "->", "configure"),
                Steps.list(argv(steps.get(0)), "->", steps.get(0).outputs()));
        autotoolsMake(c, ctx, steps);
    }

    private static void autotoolsMake(Checks c, PlanContext ctx, List<NativeStep> steps) {
        c.equal("configure argv, out of tree", List.of("/bin/sh", "../../../configure", "--prefix=/usr/local",
                "CC=/usr/bin/gcc", "in", BUILD), Steps.list(argv(steps.get(1)), "in", steps.get(1).workingDir()));
        c.equal("configure stamp", List.of("configure", "->", BUILD + "/config.status"),
                Steps.list(steps.get(1).inputs(), "->", steps.get(1).outputs()));
        c.equal("make argv", List.of("/usr/bin/make", "-C", BUILD, "-j4"), argv(steps.get(2)));
        c.equal("make check argv and Automake log", List.of("/usr/bin/make", "-C", BUILD, "-j4", "check", "->",
                BUILD + "/test-suite.log"), Steps.list(argv(steps.get(3)), "->", steps.get(3).outputs()));
        c.equal("make install argv", List.of("/usr/bin/make", "-C", BUILD, "install", "DESTDIR="
                + ctx.layout().mixed(STAGE)), argv(steps.get(4)));
        c.check("dry run holds configure old", AutotoolsPlanner.dryRunArguments(ctx).containsAll(List.of("-nwB", "-o",
                "../../../configure", "config.status")));
        c.check("autotools: configure runs a script file, not sh -c", PlanFixtures.noShellCommand(steps));
    }

    private static void autotoolsModes(Checks c) throws Exception {
        Path base = c.dir("plan-autotools-modes", Map.of("configure", "#!/bin/sh\n", "configure.ac", "AC_INIT\n"));
        PlanContext never = context(base, BuildSystem.AUTOTOOLS_BOOTSTRAP, "configure.ac", LINUX,
                options().autotools("never", "").build(), Map.of());
        c.fails("autoreconf=never without configure", IllegalStateException.class,
                () -> new AutotoolsPlanner().plan(never));
        PlanContext shipped = context(base, BuildSystem.AUTOTOOLS, "configure", LINUX, options().build(), Map.of());
        c.equal("shipped configure: no bootstrap, no Automake log", List.of("native-configure", "[]"), List.of(
                new AutotoolsPlanner().plan(shipped).get(0).name(), new AutotoolsPlanner().plan(shipped).get(2).outputs()
                        .toString()));
        autoreconfAlways(c, base);
    }

    private static void autoreconfAlways(Checks c, Path base) throws Exception {
        PlanContext always = context(base, BuildSystem.AUTOTOOLS, "configure", WINDOWS, options().autotools("always",
                "/opt").build(), Map.of(), path("sh", SH));
        List<NativeStep> steps = new AutotoolsPlanner().plan(always);
        c.equal("autoreconf=always bootstraps; prefix; SHELL on Windows", List.of("native-bootstrap", "--prefix=/opt",
                "SHELL=" + SH), List.of(steps.get(0).name(), steps.get(1).arguments().get(1),
                steps.get(2).arguments().get(3)));
    }
}
