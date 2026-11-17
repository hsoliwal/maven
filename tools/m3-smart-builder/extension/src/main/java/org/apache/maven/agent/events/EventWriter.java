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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * The JSON Lines event stream: one object per line, each starting with {@code v}, {@code seq}, {@code tMs}
 * (milliseconds since the stream opened) and {@code type}. Lines are staged in a temporary file outside the build
 * tree ({@link #stagingDir()}), so {@code clean} never meets an open file, and moved to their place by
 * {@link #publish(Path)}. Thread-safe.
 */
final class EventWriter implements AutoCloseable {
    /** Schema version of every line. */
    static final int VERSION = 1;
    /** Lines written before further events are only counted; the final event is always written. */
    static final int MAX_EVENTS = 20_000;
    /** Age after which a staged stream that was never published (a killed build) is removed. */
    static final Duration STALE_STAGED = Duration.ofDays(1);

    private final Path staged;
    private final BufferedWriter out;
    private final Consumer<String> echo;
    private final long startNanos = System.nanoTime();
    private long seq;
    private long dropped;
    private boolean closed;

    private EventWriter(Path staged, Consumer<String> echo) throws IOException {
        this.staged = staged;
        this.out = Files.newBufferedWriter(staged, StandardCharsets.UTF_8);
        this.echo = echo;
    }

    /** Where streams are staged: {@code maven-build-events} in the temporary directory. */
    static Path stagingDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "maven-build-events");
    }

    /**
     * Opens a staged stream; {@code echo} receives every written line (standard output in events mode). Streams that
     * killed builds left staged more than {@link #STALE_STAGED} ago are removed first.
     */
    static EventWriter open(Consumer<String> echo) throws IOException {
        Path dir = Files.createDirectories(stagingDir());
        removeStale(dir);
        return new EventWriter(Files.createTempFile(dir, "build-events-", ".jsonl"), echo);
    }

    private static void removeStale(Path dir) throws IOException {
        FileTime cutoff = FileTime.from(Instant.now().minus(STALE_STAGED));
        try (DirectoryStream<Path> staged = Files.newDirectoryStream(dir, "build-events-*.jsonl")) {
            StreamSupport.stream(staged.spliterator(), false).filter(file -> modified(file).compareTo(cutoff) < 0)
                    .forEach(EventWriter::deleteQuietly);
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException gone) {
            return FileTime.from(Instant.now());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException inUse) {
            // another build still writes it, or it is locked; the next build tries again
        }
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

    /** Closes the stream and moves it to {@code target}, replacing an older file. */
    synchronized void publish(Path target) throws IOException {
        close();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Closes the staged file without publishing it; a published or closed stream ignores further events. */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    /** Removes the staged file when it was never published. */
    synchronized void discard() throws IOException {
        close();
        Files.deleteIfExists(staged);
    }
}
