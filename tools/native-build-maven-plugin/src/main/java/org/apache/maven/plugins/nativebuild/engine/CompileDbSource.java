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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a build's compile_commands.json comes from: CMake ({@code CMAKE_EXPORT_COMPILE_COMMANDS}, not written by the
 * Visual Studio and Xcode generators) and Meson write one; for build.ninja it is {@code ninja -t compdb} filtered to
 * sources; for Make and Autotools a {@code make -nwB} dry run with the generated Autotools files held old so configure
 * does not run again, or Bear when asked for ({@code compileCommands=bear}; an external GPL tool, only run, around a
 * forced rebuild with the same files held old).
 */
public final class CompileDbSource {
    /** The configured source. */
    public enum Mode {
        /** Chosen by build system. */
        AUTO,
        /** CMake's own file. */
        CMAKE,
        /** Meson's own file. */
        MESON,
        /** {@code ninja -t compdb}. */
        NINJA,
        /** Parsed {@code make -nwB} output. */
        MAKE_DRY_RUN,
        /** Bear around a full make rebuild. */
        BEAR,
        /** No database. */
        NONE;

        /** The mode named by a parameter value such as {@code make-dry-run}. */
        public static Mode parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    /**
     * The outcome.
     *
     * @param database the database in the build tree, when there is one
     * @param note what happened, for the log
     */
    public record Result(Optional<Path> database, String note) {}

    private CompileDbSource() {}

    /** The mode to use: AUTO resolved by build system; Bear only when configured (it rebuilds everything). */
    public static Mode effective(Mode configured, BuildSystem system, boolean bear) {
        if (configured != Mode.AUTO) {
            return configured;
        }
        return switch (system) {
            case CMAKE, CMAKE_PRESETS -> Mode.CMAKE;
            case MESON -> Mode.MESON;
            case NINJA -> Mode.NINJA;
            case AUTOTOOLS, AUTOTOOLS_BOOTSTRAP, MAKE -> Mode.MAKE_DRY_RUN;
            case NOTHING -> Mode.NONE;
        };
    }

    /** Produces or finds the database. Generated databases go to {@code <build>/compile_commands.json}. */
    public static Result produce(PlanContext c, StepPlanner planner, Mode mode, Duration timeout)
            throws IOException, InterruptedException {
        Path generated = c.layout().path(c.layout().inBuild("compile_commands.json"));
        return switch (mode) {
            case CMAKE, MESON -> existing(c.layout().path(planner.buildDirectory(c)).resolve("compile_commands.json"));
            case NINJA -> ninja(c, planner, generated, timeout);
            case MAKE_DRY_RUN -> dryRun(c, generated, timeout);
            case BEAR -> bear(c, generated, timeout);
            case AUTO, NONE -> new Result(Optional.empty(), "compile database switched off");
        };
    }

    private static Result existing(Path database) {
        return Files.isRegularFile(database) ? new Result(Optional.of(database), "written by the build system")
                : new Result(Optional.empty(), "no " + database + " (the Visual Studio and Xcode generators write none)");
    }

    private static Result ninja(PlanContext c, StepPlanner planner, Path generated, Duration timeout)
            throws IOException, InterruptedException {
        CapturedProcess.Result run = capture(c, List.of(c.tool("ninja"), "-C", planner.buildDirectory(c), "-t", "compdb"),
                ".", timeout);
        return write(generated, CompileDatabase.sourcesOnly(run.out()), "ninja -t compdb, sources only");
    }

    private static Result dryRun(PlanContext c, Path generated, Duration timeout)
            throws IOException, InterruptedException {
        boolean autotools = c.system() != BuildSystem.MAKE;
        List<String> args = autotools ? AutotoolsPlanner.dryRunArguments(c) : MakePlanner.dryRunArguments(c);
        String directory = autotools ? "." : c.layout().source();
        CapturedProcess.Result run = capture(c, Steps.list(c.tool("make"), args), directory, timeout);
        List<String> compilers = Steps.list(c.tools().configured("cc"), c.tools().configured("cxx"));
        List<CompileDatabase.Entry> entries = MakeDryRunParser.parse(run.out(), c.layout().path(directory),
                MakeDryRunParser.compilers(compilers));
        return write(generated, CompileDatabase.render(entries), "make -nwB dry run, " + entries.size() + " entries");
    }

    private static Result bear(PlanContext c, Path generated, Duration timeout)
            throws IOException, InterruptedException {
        capture(c, bearCommand(c, generated), c.system() != BuildSystem.MAKE ? "." : c.layout().source(), timeout);
        return existing(generated);
    }

    /** Bear around a forced make; for Autotools with the generated files held old, so configure never re-runs. */
    static List<String> bearCommand(PlanContext c, Path generated) {
        boolean autotools = c.system() != BuildSystem.MAKE;
        List<String> make = autotools ? Steps.list(c.tool("make"), "-C", c.layout().build(), "-B",
                AutotoolsPlanner.held(c)) : Steps.list(c.tool("make"), "-f", MakePlanner.makefile(c), "-B",
                Steps.makeShell(c), Steps.compilerAssignments(c));
        return Steps.list(c.tool("bear"), "--output", WindowsPaths.mixed(generated), "--", make);
    }

    private static CapturedProcess.Result capture(PlanContext c, List<String> command, String directory,
            Duration timeout) throws IOException, InterruptedException {
        CapturedProcess.Result run = CapturedProcess.run(command, c.layout().path(directory), timeout);
        if (run.exit() != 0) {
            throw new IOException(String.join(" ", command) + " exited " + run.exit() + ": " + run.err().strip());
        }
        return run;
    }

    private static Result write(Path file, String json, String note) throws IOException {
        TaskFileWriter.write(file, json);
        return new Result(Optional.of(file), note);
    }
}
