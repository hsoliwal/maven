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
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * The names of a patch path as the directories on disk list them. From the base directory down, every name of the
 * path that exists must be an entry of its directory with exactly that name, compared with case. The volume may find a
 * file by another letter case or by its 8.3 short name, and a Linux JVM on an NTFS volume (WSL {@code /mnt/c}) keeps
 * that spelling in {@code toRealPath}, so only the directory listing tells on every JVM. A refusal names the entry on
 * disk: the entry equal ignoring case, else the entry that is the same file (an 8.3 short name); a name whose entry on
 * disk is {@code .git} is refused as git metadata.
 */
final class OnDiskNames {
    private OnDiskNames() { }

    /** Refuses {@code relative} unless each of its existing names is listed exactly so by its directory. */
    static void require(Path base, String relative) throws PatchException {
        Path directory = base;
        List<String> listed = new ArrayList<>();
        for (String name : relative.split("/")) {
            Path next = directory.resolve(name);
            if (!Files.exists(next)) return;
            List<String> entries = entries(directory, relative);
            if (!entries.contains(name)) throw unlisted(relative, listed, name, entry(entries, directory, name));
            listed.add(name);
            directory = next;
        }
    }

    /** The refusal for {@code name}, found but not listed; {@code listed} is the path so far, {@code entry} its entry. */
    private static PatchException unlisted(String relative, List<String> listed, String name, Optional<String> entry) {
        if (entry.isEmpty()) return PathNames.refused(relative, "names " + name + ", which its directory does not list");
        String real = listed.isEmpty() ? entry.get() : String.join("/", listed) + "/" + entry.get();
        if (entry.get().equalsIgnoreCase(".git")) {
            return PathNames.refused(relative, "resolves into .git metadata (real spelling " + real + ")");
        }
        return PathNames.refused(relative, "names an existing path by another spelling (letter case, 8.3 short name"
                + " or directory link); real spelling " + real);
    }

    /** The entry that {@code name} finds: equal ignoring case, else the same file (an 8.3 short name). */
    private static Optional<String> entry(List<String> entries, Path directory, String name) {
        Optional<String> otherCase = entries.stream().filter(name::equalsIgnoreCase).findFirst();
        return otherCase.isPresent() ? otherCase : entries.stream().filter(e -> same(directory, e, name)).findFirst();
    }

    private static boolean same(Path directory, String entry, String name) {
        try {
            return Files.isSameFile(directory.resolve(entry), directory.resolve(name));
        } catch (IOException e) {
            return false;
        }
    }

    /** The names {@code directory} lists; a directory that cannot be listed refuses the path. */
    private static List<String> entries(Path directory, String relative) throws PatchException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return StreamSupport.stream(stream.spliterator(), false).map(p -> p.getFileName().toString()).toList();
        } catch (IOException | DirectoryIteratorException e) {
            throw PathNames.refused(relative, "cannot list " + directory + " to compare its names with case (" + e + ")");
        }
    }
}
