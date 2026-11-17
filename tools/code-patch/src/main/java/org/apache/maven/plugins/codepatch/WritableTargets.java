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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;

/**
 * Plan-time checks that apply will be able to write a resolved target, so a plan never promises what apply cannot do:
 * the target is outside the output directory, every existing ancestor below the base directory is a real directory
 * (not a regular file, not a dangling link), and on Windows an existing target is not read-only (a read-only file can
 * be neither replaced nor deleted there).
 */
final class WritableTargets {
    private WritableTargets() { }

    static void check(Path base, Path target, String relative, Path outputDirectory) throws PatchException, IOException {
        if (outputDirectory != null && inside(target, outputDirectory)) {
            throw PathNames.refused(relative, "is inside the output directory " + outputDirectory);
        }
        directories(base, target, relative);
        if (TargetPaths.windows(target) && Files.isRegularFile(target)
                && Files.readAttributes(target, DosFileAttributes.class).isReadOnly()) {
            throw PathNames.refused(relative, "is read-only, and Windows cannot replace or delete a read-only file;"
                    + " clear the attribute and plan again");
        }
    }

    /** Every existing ancestor below the base directory must be a directory once links are followed. */
    private static void directories(Path base, Path target, String relative) throws PatchException {
        for (Path at = target.getParent(); at != null && at.startsWith(base) && !at.equals(base); at = at.getParent()) {
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(at)) {
                String what = Files.isSymbolicLink(at) ? " (a dangling or non-directory link)" : "";
                String name = base.relativize(at).toString().replace('\\', '/');
                throw PathNames.refused(relative, name + " is not a directory" + what);
            }
        }
    }

    private static boolean inside(Path target, Path outputDirectory) throws IOException {
        Path out = outputDirectory.toAbsolutePath().normalize();
        return target.startsWith(out) || TargetPaths.realLocation(target).startsWith(TargetPaths.realLocation(out));
    }
}
