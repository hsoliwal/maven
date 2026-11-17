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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The native build systems this plugin drives, with the name used in reports and the {@code buildSystem} parameter. */
public enum BuildSystem {
    /** CMakeLists.txt with CMakePresets.json. */
    CMAKE_PRESETS("cmake-presets", "cmake"),
    /** CMakeLists.txt. */
    CMAKE("cmake", "cmake"),
    /** meson.build. */
    MESON("meson", "meson"),
    /** A generated configure script. */
    AUTOTOOLS("autotools", "autotools"),
    /** configure.ac or configure.in without a configure script: autoreconf runs first. */
    AUTOTOOLS_BOOTSTRAP("autotools-bootstrap", "autotools"),
    /** A hand-written build.ninja. */
    NINJA("ninja", "ninja"),
    /** GNUmakefile, makefile or Makefile. */
    MAKE("make", "make"),
    /** Nothing this plugin knows. */
    NOTHING("none", "none");

    private final String id;
    private final String family;

    BuildSystem(String id, String family) {
        this.id = id;
        this.family = family;
    }

    /** Name in parameters and logs. */
    public String id() {
        return id;
    }

    /** Name in report file names: cmake, meson, autotools, ninja, make. */
    public String family() {
        return family;
    }

    /** The system named by {@code value} (its id, case-insensitive), if any. */
    public static Optional<BuildSystem> parse(String value) {
        String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s != NOTHING && s.id.equals(wanted)).findFirst();
    }
}
