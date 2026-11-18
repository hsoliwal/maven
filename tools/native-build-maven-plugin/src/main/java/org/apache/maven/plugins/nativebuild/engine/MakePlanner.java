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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A hand-written makefile, built in place: {@code make -f <file> -jN}, the test target ({@code make.testTarget}, else
 * {@code check} or {@code test} when the makefile declares it) and {@code make install DESTDIR=<stage>} when it
 * declares install and its text uses {@code $(DESTDIR)}: an install rule that ignores DESTDIR would write outside the
 * project, so it is not run. Configured compilers are passed as {@code CC=} and {@code CXX=} arguments; native
 * dependencies as {@code CPPFLAGS}, {@code LDFLAGS}, {@code PKG_CONFIG_PATH} and {@code CMAKE_PREFIX_PATH} added to
 * the environment (the makefile's own assignments win). nmake runs only with an explicit {@code make.tool=nmake}
 * inside a Visual Studio developer environment.
 */
final class MakePlanner implements StepPlanner {
    private static final Pattern DESTDIR = Pattern.compile("\\$[({]DESTDIR[)}]");

    @Override
    public List<NativeStep> plan(PlanContext c) throws IOException {
        String text = makefileText(c);
        List<NativeStep> steps = new ArrayList<>();
        steps.add(step(c, NativePhase.BUILD, Steps.list(nmake(c) ? "" : "-j" + c.options().jobs(), c.options().targets()),
                Steps.buildInputs(c), c.options().outputs()));
        String test = c.options().testTarget().isEmpty() ? declared(text, "check", "test") : c.options().testTarget();
        if (!test.isEmpty()) {
            steps.add(step(c, NativePhase.TEST, List.of(test), Steps.afterBuildInputs(c), List.of()));
        }
        if (!nmake(c) && !declared(text, "install").isEmpty() && installRefusal(c).isEmpty()) {
            steps.add(step(c, NativePhase.INSTALL, List.of("install", "DESTDIR=" + c.layout().mixed(c.layout().stage())),
                    Steps.afterBuildInputs(c), List.of(c.layout().stage())));
        }
        return steps;
    }

    @Override
    public String buildDirectory(PlanContext c) {
        return c.layout().source();
    }

    /**
     * Why the makefile's install target is not run: it ignores DESTDIR, or the stage path holds characters make
     * passes unquoted to the shell. Empty when install runs or there is no install target. "Ignores DESTDIR" is a
     * heuristic: the makefile text, without comments, must use {@code $(DESTDIR)} or {@code ${DESTDIR}} somewhere; an
     * install rule that uses it for some files but not for others still writes those outside, so DESTDIR must be used
     * throughout the rule (or outputs declared instead).
     */
    @Override
    public Optional<String> installRefusal(PlanContext c) throws IOException {
        String text = makefileText(c);
        if (nmake(c) || declared(text, "install").isEmpty()) {
            return Optional.empty();
        }
        if (!DESTDIR.matcher(text.replaceAll("(?m)#.*$", "")).find()) {
            return Optional.of(makefile(c) + "'s install target ignores DESTDIR, so it would install outside the "
                    + "project; use $(DESTDIR) in its install rule, or declare outputs to stage instead");
        }
        return Steps.unsafeStage(c);
    }

    /** {@code make -n} arguments that print every compile command of the makefile without running it. */
    static List<String> dryRunArguments(PlanContext c) {
        return Steps.list("-f", makefile(c), "-nwB", Steps.makeShell(c), Steps.compilerAssignments(c));
    }

    /** The first of {@code names} the makefile declares as a target (rule {@code name:}), else empty. */
    static String declared(String makefile, String... names) {
        for (String name : names) {
            Pattern rule = Pattern.compile("(?m)^(?:[\\w./-]+[ \\t]+)*" + Pattern.quote(name)
                    + "(?:[ \\t]+[\\w./-]+)*[ \\t]*::?(?!=)");
            if (rule.matcher(makefile).find()) {
                return name;
            }
        }
        return "";
    }

    static String makefile(PlanContext c) {
        String file = c.options().makeFile().isEmpty() ? c.detection().descriptor() : c.options().makeFile();
        return file.isEmpty() ? "Makefile" : file;
    }

    private static NativeStep step(PlanContext c, NativePhase phase, List<String> what, List<String> inputs,
            List<String> outputs) {
        if (nmake(c) && !c.environment().containsKey("VCINSTALLDIR")) {
            throw new IllegalStateException("nmake needs a Visual Studio developer environment (VCINSTALLDIR is not "
                    + "set): start Maven from a developer prompt, or build with CMake and the Visual Studio generator");
        }
        List<String> args = nmake(c) ? Steps.list("/NOLOGO", "/F", makefile(c), what, c.options().args(phase))
                : Steps.list("-f", makefile(c), what, Steps.makeShell(c), Steps.compilerAssignments(c),
                        c.options().args(phase));
        return new NativeStep(phase, c.tool("make"), c.layout().source(), args, inputs, outputs,
                Steps.makeEnvironment(c));
    }

    private static boolean nmake(PlanContext c) {
        String tool = c.options().makeTool().isEmpty() ? c.tool("make") : c.options().makeTool();
        return "nmake".equals(WindowsPaths.stem(tool).toLowerCase(Locale.ROOT));
    }

    private static String makefileText(PlanContext c) throws IOException {
        Path file = c.layout().path(Layout.join(c.layout().source(), makefile(c)));
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }
}
