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
package org.apache.maven.agent.events;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * The JSON Lines event stream: one object per line, each starting with {@code v}, {@code seq}, {@code tMs}
 * (milliseconds since the stream opened) and {@code type}. Lines are staged outside the build tree, so {@code clean}
 * never meets an open file, and moved to their place by {@link #publish(Path)}. Each build stages in a new private
 * directory ({@value #STAGING_PREFIX}..., owner-only on POSIX) under {@link #stagingBase()}: no existing path is
 * written to, so no other user can stop or forge the stream. Thread-safe.
 */
final class EventWriter implements AutoCloseable {
    /** Schema version of every line. */
    static final int VERSION = 1;
    /** Lines written before further events are only counted; the final event is always written. */
    static final int MAX_EVENTS = 20_000;
    /** Age after which the staging directory of a build that was killed (never published) is removed. */
    static final Duration STALE_STAGED = Duration.ofDays(1);
    /** Name prefix of a build's private staging directory. */
    static final String STAGING_PREFIX = "maven-build-events-";
    /** Name of the staged stream in that directory. */
    static final String STAGED_NAME = "build-events.jsonl";

    private final Path staged;
    private final BufferedWriter out;
    private final Consumer<String> echo;
    private final long startNanos = System.nanoTime();
    private long seq;
    private long dropped;
    private boolean closed;

    private EventWriter(Path staged, Consumer<String> echo) throws IOException {
        this.staged = staged;
        this.out = Files.newBufferedWriter(staged, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        this.echo = echo;
    }

    /**
     * Where builds stage: on Windows the user profile's {@code Temp} ({@code %LOCALAPPDATA%\Temp}), whatever
     * {@code java.io.tmpdir} says; elsewhere {@code java.io.tmpdir}, safe to share because each build's directory
     * is new and owner-only.
     */
    static Path stagingBase() {
        return profileTemp(System.getProperty("os.name", ""), System.getenv("LOCALAPPDATA"))
                .orElse(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** {@code <localAppData>/Temp} on Windows when it is a directory. */
    static Optional<Path> profileTemp(String os, String localAppData) {
        return os.startsWith("Windows") && localAppData != null && !localAppData.isBlank()
                ? Optional.of(Path.of(localAppData, "Temp")).filter(Files::isDirectory) : Optional.empty();
    }

    /** Opens a staged stream under {@link #stagingBase()}; {@code echo} receives every written line. */
    static EventWriter open(Consumer<String> echo) throws IOException {
        return open(stagingBase(), echo);
    }

    /**
     * Opens a staged stream in a new private directory under {@code base}. Directories of killed builds, owned by
     * this user and older than {@link #STALE_STAGED}, are removed first; nothing else under {@code base} is touched.
     */
    static EventWriter open(Path base, Consumer<String> echo) throws IOException {
        Path dir = base.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempDirectory(base, STAGING_PREFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory(base, STAGING_PREFIX);
        removeStale(base, Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS));
        return new EventWriter(dir.resolve(STAGED_NAME), echo);
    }

    private static void removeStale(Path base, UserPrincipal owner) throws IOException {
        FileTime cutoff = FileTime.from(Instant.now().minus(STALE_STAGED));
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, STAGING_PREFIX + "*")) {
            StreamSupport.stream(dirs.spliterator(), false).filter(dir -> stale(dir, owner, cutoff))
                    .forEach(EventWriter::removeDirectory);
        }
    }

    /** A real directory (not a link) of this user, last changed before {@code cutoff}. */
    private static boolean stale(Path dir, UserPrincipal owner, FileTime cutoff) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(dir, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory() && attributes.lastModifiedTime().compareTo(cutoff) < 0
                    && owner.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException unreadable) {
            return false;
        }
    }

    /** Removes a staging directory: its stream (a link is removed, never followed), then the directory if empty. */
    private static void removeDirectory(Path dir) {
        deleteQuietly(dir.resolve(STAGED_NAME));
        deleteQuietly(dir);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException inUse) {
            // another build still writes it, it is locked, or the directory is not empty; the next build tries again
        }
    }

    /** The staged file of this stream, in its private directory. */
    Path stagedFile() {
        return staged;
    }

    /** Writes one event unless the cap is reached; returns whether it was written. */
    synchronized boolean emit(String type, JsonText.Obj fields) {
        if (closed || seq >= MAX_EVENTS) {
            dropped++;
            return false;
        }
        write(type, fields);
        return true;
    }

    /** Writes the closing event even past the cap. */
    synchronized void emitFinal(String type, JsonText.Obj fields) {
        if (!closed) {
            write(type, fields);
        }
    }

    private void write(String type, JsonText.Obj fields) {
        seq++;
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;
        String line = new JsonText.Obj().put("v", VERSION).put("seq", seq).put("tMs", millis).put("type", type)
                .putAll(fields).text();
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        echo.accept(line);
    }

    /** Lines written so far. */
    synchronized long written() {
        return seq;
    }

    /** Events not written because of {@link #MAX_EVENTS}. */
    synchronized long dropped() {
        return dropped;
    }

    /** Closes the stream, moves it to {@code target} (replacing an older file) and removes its directory. */
    synchronized void publish(Path target) throws IOException {
        close();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        deleteQuietly(staged.getParent());
    }

    /** Closes the staged file without publishing it; a published or closed stream ignores further events. */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    /** Removes the staged file and its directory when it was never published. */
    synchronized void discard() throws IOException {
        close();
        removeDirectory(staged.getParent());
    }
}
