/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** Fixed Maven receipt output with component-wise symlink rejection. */
public final class SafeBuildOutput {
    private static final Set<String> RECEIPTS = Set.of("plan.json", "provision.json", "diagnose.json", "pre-build.json", "post-build.json");
    private SafeBuildOutput() { }

    public static Path directory(Path projectBase, Path mavenBuildDirectory) throws IOException {
        if (projectBase == null || mavenBuildDirectory == null) throw new IOException("missing Maven directories");
        Path base = projectBase.toAbsolutePath().normalize();
        Path build = mavenBuildDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(base) || !build.startsWith(base) || build.equals(base)) {
            throw new IOException("Maven output must be below the project base");
        }
        Path canonicalBase = base.toRealPath();
        Path relative = base.relativize(build.resolve("m3-smart"));
        Path current = canonicalBase;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current); }
                catch (FileAlreadyExistsException collision) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw collision;
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw new IOException("Maven receipt output contains a link/non-directory");
            }
        }
        return current;
    }

    public static Path write(Path directory, String name, String contents) throws IOException {
        if (!RECEIPTS.contains(name)) throw new IOException("unapproved receipt filename");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("unapproved receipt directory");
        }
        Path output = directory.resolve(name);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("receipt destination must be an ordinary file");
        }
        Path temporary = Files.createTempFile(directory, "m3-receipt-", ".part");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return output;
    }
}
