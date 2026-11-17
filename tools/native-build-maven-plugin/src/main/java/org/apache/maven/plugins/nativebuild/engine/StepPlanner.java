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
import java.util.List;
import java.util.Map;

/** Turns a detected build system and the user settings into argument-list steps, one planner per system. */
public interface StepPlanner {
    /** The steps in execution order; a system without a test or install step leaves it out. */
    List<NativeStep> plan(PlanContext context) throws IOException;

    /** Files to write before the steps run (project-relative path to content), such as a Meson native file. */
    default Map<String, String> files(PlanContext context) {
        return Map.of();
    }

    /** The directory that holds the build tree, the compile database and test output. */
    default String buildDirectory(PlanContext context) throws IOException {
        return context.layout().build();
    }

    /** The planner of one build system. */
    static StepPlanner forSystem(BuildSystem system) {
        return switch (system) {
            case CMAKE, CMAKE_PRESETS -> new CMakePlanner();
            case MESON -> new MesonPlanner();
            case AUTOTOOLS, AUTOTOOLS_BOOTSTRAP -> new AutotoolsPlanner();
            case NINJA -> new NinjaPlanner();
            case MAKE -> new MakePlanner();
            case NOTHING -> throw new IllegalArgumentException("no native build system detected");
        };
    }

    /** The logical tool names a system may use; each is resolved before planning. */
    static List<String> toolNames(BuildSystem system) {
        return switch (system) {
            case CMAKE, CMAKE_PRESETS -> List.of("cmake", "ctest", "ninja", "cc", "cxx");
            case MESON -> List.of("meson", "ninja", "cc", "cxx");
            case AUTOTOOLS, AUTOTOOLS_BOOTSTRAP -> List.of("autoreconf", "sh", "make", "mkdir", "cc", "cxx", "bear");
            case NINJA -> List.of("ninja", "cc", "cxx");
            case MAKE -> List.of("make", "sh", "cc", "cxx", "bear");
            case NOTHING -> List.of();
        };
    }
}
