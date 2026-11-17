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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The file discipline of one module's {@code .m3} state directory, shared by the module-state owner and every
 * backend: no symlinked or non-regular state paths, forced contents before an atomic replacement, and a
 * self-ignore file so that no consumer project commits its build state.
 */
final class StateFiles {
    /** Created with the state directory; {@code *} also ignores itself. */
    static final String IGNORE = ".m3/.gitignore";

    private final Path directory;
    private final Path lock;
    private final Path marker;
    private boolean directorySyncUnavailable;

    StateFiles(Path root, String lock, String marker) {
        this.directory = root.resolve(".m3");
        this.lock = root.resolve(lock);
        this.marker = root.resolve(marker);
    }

    Path directory() {
        return directory;
    }

    /** Writes {@code .m3/.gitignore} once, inside an existing state directory; a present file is kept. */
    static void ignoreSelf(Path stateDirectory) throws IOException {
        Path ignore = stateDirectory.resolve(".gitignore");
        if (Files.exists(ignore, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.writeString(ignore, "*\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException concurrent) {
            // Another module session wrote the same one line.
        }
    }

    /** The state directory, lock and marker are ordinary, unlinked paths. */
    void checkPaths() throws IOException {
        checkDirectory(directory);
        checkFile(lock);
        checkFile(marker);
    }

    /** File contents are forced before atomic replacement; no partial-copy fallback. */
    void atomicFile(Path target, byte[] bytes) throws IOException {
        checkPaths();
        checkFile(target);
        Path temporary = Files.createTempFile(directory, "module-state-", ".tmp");
        withCleanup(() -> {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            forceFile(temporary);
            replace(temporary, target);
        }, temporary);
    }

    /** A body of file work that may fail. */
    @FunctionalInterface
    interface IoWork {
        void run() throws IOException;
    }

    /** Runs the work, then removes the temporaries; a cleanup failure never hides the work's own failure. */
    static void withCleanup(IoWork work, Path... temporaries) throws IOException {
        Throwable failure = null;
        try {
            work.run();
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            deleteAll(failure, temporaries);
        }
    }

    private static void deleteAll(Throwable failure, Path... paths) throws IOException {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
        }
    }

    /** Moves a forced file over the target atomically and orders the rename where the provider allows. */
    void replace(Path forced, Path target) throws IOException {
        checkPaths();
        checkFile(target);
        Files.move(forced, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(target.getParent());
    }

    static void forceFile(Path file) throws IOException {
        try (FileChannel data = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            data.force(true);
        }
    }

    /**
     * Java has no portable directory-fsync API. Providers that expose directory channels get ordered rename
     * durability; other providers keep atomic process-crash recovery, with power-loss durability their own.
     */
    void forceDirectory(Path path) throws IOException {
        if (directorySyncUnavailable) return;
        try (FileChannel data = FileChannel.open(path, StandardOpenOption.READ)) {
            data.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException unsupported) {
            directorySyncUnavailable = true;
        }
    }

    static void checkDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB state directory is not a directory or is a symlink: " + path);
        }
    }

    static void checkFile(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3DB state path is not a regular file or is a symlink: " + path);
        }
    }
}
