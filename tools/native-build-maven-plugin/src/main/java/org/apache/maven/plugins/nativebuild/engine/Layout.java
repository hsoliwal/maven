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

import java.nio.file.Path;

/**
 * Where one native build lives, as paths relative to the project directory (the polyglot workspace: its manifests
 * accept only project-relative input, output and working-directory paths).
 *
 * @param basedir the project directory, absolute
 * @param source the native source directory ({@code "."} for the project directory)
 * @param build the build directory, {@code target/native/<classifier>} by default
 * @param stage the install stage, {@code target/native/stage/<classifier>} by default
 * @param reports the normalized test reports directory, {@code target/native-reports} by default
 */
public record Layout(Path basedir, String source, String build, String stage, String reports) {
    /** Relativizes absolute directories; each must lie inside the project directory. */
    public static Layout of(Path basedir, Path source, Path build, Path stage, Path reports) {
        Path base = basedir.toAbsolutePath().normalize();
        return new Layout(base, inside(base, source), inside(base, build), inside(base, stage), inside(base, reports));
    }

    /** The absolute path of a project-relative one. */
    public Path path(String relative) {
        return ".".equals(relative) ? basedir : basedir.resolve(relative).normalize();
    }

    /** The absolute path of a project-relative one, in the mixed spelling for native tools. */
    public String mixed(String relative) {
        return WindowsPaths.mixed(path(relative));
    }

    /** {@code child} under the project-relative {@code directory}, still project-relative. */
    public static String join(String directory, String child) {
        return ".".equals(directory) ? child : directory + "/" + child;
    }

    /** The compile database, test output and fingerprint files of the build directory. */
    public String inBuild(String child) {
        return join(build, child);
    }

    /**
     * True when the stage may be emptied: it lies strictly inside the project directory and holds neither the
     * sources nor the build directory.
     */
    public boolean stageDisposable() {
        Path stagePath = path(stage);
        return FileTrees.strictlyBelow(stagePath, basedir) && !path(source).startsWith(stagePath)
                && !path(build).startsWith(stagePath);
    }

    /**
     * Fails unless {@code directory} lies inside the project directory; checked for the build directory before
     * anything (provisioning, dependency unpacking) writes below it.
     */
    public static String requireInside(Path basedir, Path directory) {
        return inside(basedir.toAbsolutePath().normalize(), directory);
    }

    private static String inside(Path base, Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new IllegalArgumentException("native directory must lie inside the project directory " + base
                    + ": " + absolute);
        }
        return WindowsPaths.relative(base, absolute);
    }
}
