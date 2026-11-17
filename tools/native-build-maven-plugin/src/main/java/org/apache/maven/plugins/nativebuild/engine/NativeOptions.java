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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The user's native build settings, independent of Maven.
 *
 * @param buildType CMake build type (Release, Debug, RelWithDebInfo, MinSizeRel); Meson maps it to its own names
 * @param jobs parallel jobs, at least 1
 * @param generator CMake generator, empty for CMake's default
 * @param preset CMake preset name, empty for none
 * @param makeTool make, mingw32-make, gmake or nmake; empty for make
 * @param makeFile the makefile, empty for the detected one
 * @param targets build targets (Make, Ninja, CMake {@code --target}), empty for the default
 * @param testTarget the Make or Ninja test target, empty to use {@code check} or {@code test} when the makefile has it
 * @param autoreconf auto (only when configure is missing), always or never
 * @param prefix the Autotools {@code --prefix}, stripped again when the stage is collected
 * @param extraArgs user arguments appended per step
 * @param outputs project-relative build outputs; when given, build steps get content stamps
 */
public record NativeOptions(
        String buildType,
        int jobs,
        String generator,
        String preset,
        String makeTool,
        String makeFile,
        List<String> targets,
        String testTarget,
        String autoreconf,
        String prefix,
        Map<NativePhase, List<String>> extraArgs,
        List<String> outputs) {
    /** Validates and copies. */
    public NativeOptions {
        if (jobs < 1) {
            throw new IllegalArgumentException("native jobs must be at least 1");
        }
        if (!List.of("auto", "always", "never").contains(autoreconf)) {
            throw new IllegalArgumentException("native.autotools.autoreconf must be auto, always or never");
        }
        targets = List.copyOf(targets);
        extraArgs = Map.copyOf(extraArgs);
        outputs = List.copyOf(outputs);
    }

    /** User arguments for one step, empty when none. */
    public List<String> args(NativePhase phase) {
        return extraArgs.getOrDefault(phase, List.of());
    }

    /** Settings with defaults: Release, one job per processor, autoreconf auto, prefix /usr/local. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent construction for mojos and tests. */
    public static final class Builder {
        private String buildType = "Release";
        private int jobs = Runtime.getRuntime().availableProcessors();
        private String generator = "";
        private String preset = "";
        private String makeTool = "";
        private String makeFile = "";
        private List<String> targets = List.of();
        private String testTarget = "";
        private String autoreconf = "auto";
        private String prefix = "/usr/local";
        private final Map<NativePhase, List<String>> extraArgs = new EnumMap<>(NativePhase.class);
        private List<String> outputs = List.of();

        private Builder() {}

        /** Build type. */
        public Builder buildType(String value) {
            buildType = blankTo(value, "Release");
            return this;
        }

        /** Jobs; 0 or less means one per processor. */
        public Builder jobs(int value) {
            jobs = value > 0 ? value : Runtime.getRuntime().availableProcessors();
            return this;
        }

        /** CMake generator and preset. */
        public Builder cmake(String generatorName, String presetName) {
            generator = blankTo(generatorName, "");
            preset = blankTo(presetName, "");
            return this;
        }

        /** Make tool, makefile, targets and test target. */
        public Builder make(String tool, String file, List<String> buildTargets, String test) {
            makeTool = blankTo(tool, "");
            makeFile = blankTo(file, "");
            targets = buildTargets == null ? List.of() : buildTargets;
            testTarget = blankTo(test, "");
            return this;
        }

        /** Autotools settings. */
        public Builder autotools(String autoreconfMode, String installPrefix) {
            autoreconf = blankTo(autoreconfMode, "auto");
            prefix = blankTo(installPrefix, "/usr/local");
            return this;
        }

        /** User arguments appended to one step. */
        public Builder args(NativePhase phase, List<String> values) {
            if (values != null && !values.isEmpty()) {
                extraArgs.put(phase, List.copyOf(values));
            }
            return this;
        }

        /** Declared build outputs. */
        public Builder outputs(List<String> values) {
            outputs = values == null ? List.of() : values;
            return this;
        }

        /** The options. */
        public NativeOptions build() {
            return new NativeOptions(buildType, jobs, generator, preset, makeTool, makeFile, targets, testTarget,
                    autoreconf, prefix, extraArgs, outputs);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
