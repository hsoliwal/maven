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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Detection precedence: a generating file wins over what it generates; explicit choice overrides. */
final class DetectorContract {
    private DetectorContract() {}

    static void run(Checks c) throws Exception {
        expect(c, "cmake over make and ninja", BuildSystem.CMAKE, "CMakeLists.txt", "CMakeLists.txt", "Makefile",
                "build.ninja");
        expect(c, "presets", BuildSystem.CMAKE_PRESETS, "CMakePresets.json", "CMakeLists.txt", "CMakePresets.json");
        expect(c, "meson over its build.ninja", BuildSystem.MESON, "meson.build", "meson.build", "build.ninja");
        expect(c, "configure over Makefile", BuildSystem.AUTOTOOLS, "configure", "configure", "configure.ac",
                "Makefile");
        expect(c, "configure.ac without configure", BuildSystem.AUTOTOOLS_BOOTSTRAP, "configure.ac", "configure.ac",
                "Makefile.am");
        expect(c, "configure.in", BuildSystem.AUTOTOOLS_BOOTSTRAP, "configure.in", "configure.in");
        rest(c);
        explicit(c);
    }

    private static void rest(Checks c) throws Exception {
        expect(c, "ninja over make", BuildSystem.NINJA, "build.ninja", "build.ninja", "Makefile");
        expect(c, "GNUmakefile first", BuildSystem.MAKE, "GNUmakefile", "GNUmakefile", "Makefile");
        expect(c, "Makefile", BuildSystem.MAKE, "Makefile", "Makefile");
        expect(c, "nothing", BuildSystem.NOTHING, "", "README.md");
    }

    private static void explicit(Checks c) throws Exception {
        Path cmake = fixture(c, "explicit", "CMakeLists.txt", "Makefile");
        c.equal("explicit make over CMakeLists.txt", BuildSystem.MAKE, BuildDetector.detect(cmake, "make").system());
        Path ac = fixture(c, "explicit-ac", "configure.ac");
        c.equal("explicit autotools without configure bootstraps", BuildSystem.AUTOTOOLS_BOOTSTRAP,
                BuildDetector.detect(ac, "autotools").system());
        c.fails("unknown buildSystem", IllegalArgumentException.class, () -> BuildDetector.detect(cmake, "scons"));
        Path folder = fixture(c, "configure-folder");
        Files.createDirectories(folder.resolve("configure"));
        c.equal("a folder named configure is not a script", BuildSystem.NOTHING,
                BuildDetector.detect(folder, "").system());
        c.fails("missing source directory", java.io.IOException.class,
                () -> BuildDetector.detect(folder.resolve("absent"), ""));
    }

    private static void expect(Checks c, String name, BuildSystem system, String descriptor, String... files)
            throws Exception {
        Detection d = BuildDetector.detect(fixture(c, "detect-" + name.replace(' ', '-'), files), null);
        c.equal("detect " + name, system + " " + descriptor, d.system() + " " + d.descriptor());
    }

    private static Path fixture(Checks c, String name, String... files) throws Exception {
        Map<String, String> content = Arrays.stream(files).collect(Collectors.toMap(f -> f, f -> "# " + f + "\n"));
        return c.dir(name, content);
    }
}
