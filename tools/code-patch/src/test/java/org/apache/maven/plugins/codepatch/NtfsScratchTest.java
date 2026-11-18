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
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A Linux JVM on an NTFS volume (WSL, {@code /mnt/c}): {@code toRealPath} keeps another letter case and 8.3 short
 * names there, so the 8.3 ({@code LONGFI~1.TXT}, {@code GIT~1}), letter case and case collision checks of
 * {@link TargetPathsTest}, {@link SpellingTest} and {@link CaseCollisionTest} run again in a scratch directory on such
 * a volume. The directory is {@code $CODE_PATCH_NTFS}, else {@code /mnt/c/Windows/Temp} or {@code /mnt/c/Users/Public}
 * when writable; it is removed afterwards. Skipped on Windows (the main run is on NTFS there) and where none exists.
 */
final class NtfsScratchTest {
    private static final String WHAT = "TargetPaths, Spelling and CaseCollision on NTFS from a Linux JVM";

    private NtfsScratchTest() { }

    static void run() throws Exception {
        Optional<Path> scratch = Scratch.windows() ? Optional.empty() : scratch();
        if (scratch.isEmpty()) {
            Links.skip(WHAT, Scratch.windows() ? "Windows JVM: the main run covers NTFS" : "no writable /mnt/c scratch");
            return;
        }
        checks(scratch.get());
    }

    private static void checks(Path dir) throws Exception {
        System.err.println("NTFS scratch " + dir + " (" + Files.getFileStore(dir).type() + ")");
        try {
            TargetPathsTest.run(dir.resolve("target-paths"));
            SpellingTest.run(dir.resolve("spelling"));
            CaseCollisionTest.run(dir.resolve("case-collision"));
        } finally {
            Scratch.clean(dir);
            Files.delete(dir);
        }
    }

    /** A new directory under the first writable candidate on a Linux JVM, or empty. */
    private static Optional<Path> scratch() {
        String chosen = System.getenv("CODE_PATCH_NTFS");
        return Stream.of(chosen, "/mnt/c/Windows/Temp", "/mnt/c/Users/Public").filter(c -> c != null && !c.isEmpty())
                .map(Path::of).filter(Files::isDirectory).map(NtfsScratchTest::created).flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Path> created(Path parent) {
        try {
            return Optional.of(Files.createTempDirectory(parent, "code-patch-ntfs-"));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }
}
