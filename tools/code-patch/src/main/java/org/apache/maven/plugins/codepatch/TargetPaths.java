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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves a patch path under the base directory. It refuses every path that breaks a {@link PathNames} rule, that is
 * a symbolic link, or whose resolved location is outside the real base directory or in a {@code .git} directory. The
 * resolved location is the real path of the nearest existing ancestor (links and junctions resolved; on a Windows JVM
 * also 8.3 short names and letter case) followed by the remaining names, so a link to {@code .git} inside the tree is
 * refused. The existing part of the path must also be spelled as on disk, on every JVM: each existing name must be
 * listed exactly so by its directory ({@link OnDiskNames}: no other letter case, no 8.3 short name, and
 * {@code GIT~1/config} refused as {@code .git}), and no directory link may stand in for its target, because plan,
 * apply and rollback key every file by the patch's spelling.
 */
final class TargetPaths {
    private TargetPaths() { }

    static Path resolve(Path base, String relative) throws PatchException, IOException {
        PathNames.check(relative, windows(base));
        Path target = contained(base, relative);
        if (Files.isSymbolicLink(target)) throw PathNames.refused(relative, "is a symbolic link");
        Path realBase = base.toRealPath();
        Path real = realLocation(target);
        if (!real.startsWith(realBase)) throw PathNames.escape(relative);
        if (inGit(realBase.relativize(real))) throw PathNames.refused(relative, "resolves into .git metadata (" + real + ")");
        OnDiskNames.require(base, relative);
        spelling(base, realBase, target, relative);
        return target;
    }

    /**
     * The existing part of the target, resolved by {@code toRealPath}, must keep the patch's names, compared with case:
     * a directory link inside the tree names the same file under a second key, and apply plus rollback would leave the
     * file renamed to the patch's spelling. (On a Windows JVM this also catches letter case and 8.3 short names, which
     * {@link OnDiskNames} refuses on every JVM.)
     */
    private static void spelling(Path base, Path realBase, Path target, String relative) throws PatchException, IOException {
        Path existing = existing(target);
        String named = slashes(base.relativize(existing));
        String real = slashes(realBase.relativize(existing.toRealPath()));
        if (!named.equals(real)) {
            throw PathNames.refused(relative, "names an existing path by another spelling (letter case, 8.3 short name"
                    + " or directory link); real spelling " + (real.isEmpty() ? "the base directory" : real));
        }
    }

    /** True when paths of this file system are Windows paths. */
    static boolean windows(Path path) {
        return "\\".equals(path.getFileSystem().getSeparator());
    }

    /** The real path of the nearest existing ancestor of {@code path} (or itself), followed by the remaining names. */
    static Path realLocation(Path path) throws IOException {
        Path at = path.toAbsolutePath().normalize();
        Path existing = existing(at);
        return existing == null ? at : existing.toRealPath().resolve(existing.relativize(at).toString());
    }

    /** The nearest existing ancestor of {@code path} (or itself; links followed), or null. */
    private static Path existing(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        return existing;
    }

    private static String slashes(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static Path contained(Path base, String relative) throws PatchException {
        Path target;
        try {
            target = base.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw PathNames.refused(relative, "is not a valid path on this file system (" + e.getReason() + ")");
        }
        if (!target.startsWith(base) || target.equals(base)) throw PathNames.escape(relative);
        return target;
    }

    private static boolean inGit(Path relativeReal) {
        for (Path name : relativeReal) {
            if (name.toString().toLowerCase(Locale.ROOT).equals(".git")) return true;
        }
        return false;
    }
}
