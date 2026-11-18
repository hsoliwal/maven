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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip archives whose bytes depend only on the files: entries sorted by path, one fixed time (Maven's {@code
 * project.build.outputTimestamp}, else 2000-01-01, clamped to the DOS range 1980 to 2107) written as a UTC DOS time so
 * every time zone writes the same bytes, Unix modes 0755 for executables and libraries and 0644 for everything else
 * (see {@link ZipModes}), no directory entries. Also unpacks a zip without letting an entry escape the target folder,
 * restoring the executable bit.
 */
public final class DeterministicZip {
    /** 2000-01-01T00:00:00Z, the time used without an outputTimestamp. */
    public static final long DEFAULT_TIME = 946_684_800_000L;
    /** 1980-01-01T00:00:02Z, the earliest entry time. */
    public static final long DOS_MIN = Instant.parse("1980-01-01T00:00:02Z").toEpochMilli();
    /** 2107-12-31T23:59:58Z, the latest entry time. */
    public static final long DOS_MAX = Instant.parse("2107-12-31T23:59:58Z").toEpochMilli();

    private DeterministicZip() {}

    /** Writes {@code files} (relative to {@code root}, forward slashes) into {@code zip} in sorted order. */
    public static void write(Path zip, Path root, List<String> files, long epochMillis) throws IOException {
        Files.createDirectories(zip.toAbsolutePath().getParent());
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            out.setLevel(6);
            LocalDateTime utc = LocalDateTime.ofEpochSecond(Math.floorDiv(clamp(epochMillis), 1000L), 0,
                    ZoneOffset.UTC);
            for (String name : files.stream().sorted().toList()) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(utc);
                out.putNextEntry(entry);
                Files.copy(root.resolve(name), out);
                out.closeEntry();
            }
        }
        ZipModes.apply(zip, name -> StageCollector.executable(root, name) ? 0755 : 0644);
    }

    /**
     * Milliseconds of an outputTimestamp, clamped to the zip (DOS) time range: ISO-8601 with offset, or seconds since
     * the epoch; else the default.
     */
    public static long epoch(String outputTimestamp) {
        return clamp(parse(outputTimestamp));
    }

    /**
     * {@code millis} within 1980-01-01T00:00:02Z to 2107-12-31T23:59:58Z, the range a DOS time holds (the bounds
     * MavenArchiver uses at the low end). Outside it {@link ZipEntry#setTimeLocal} would add an extended timestamp
     * computed in the default time zone, so the archive bytes would depend on the time zone.
     */
    public static long clamp(long millis) {
        return Math.max(DOS_MIN, Math.min(DOS_MAX, millis));
    }

    /** Milliseconds of an outputTimestamp, unclamped: ISO-8601 with offset, or seconds since the epoch. */
    public static long parse(String outputTimestamp) {
        String value = outputTimestamp == null ? "" : outputTimestamp.strip();
        if (value.matches("\\d+")) {
            return Long.parseLong(value) * 1000L;
        }
        try {
            return value.length() < 2 ? DEFAULT_TIME : OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return DEFAULT_TIME;
        }
    }

    /** Unpacks {@code zip} into {@code target}; an entry with an absolute path or {@code ..} fails. */
    public static void extract(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Map<String, Integer> modes = ZipModes.read(zip);
        try (InputStream file = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(file)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                extractEntry(in, entry, root, zip, modes);
            }
        }
    }

    private static void extractEntry(ZipInputStream in, ZipEntry entry, Path root, Path zip,
            Map<String, Integer> modes) throws IOException {
        Path out = root.resolve(entry.getName()).normalize();
        if (!out.startsWith(root) || out.equals(root)) {
            throw new IOException("unsafe zip entry " + entry.getName() + " in " + zip);
        }
        if (entry.isDirectory()) {
            Files.createDirectories(out);
            return;
        }
        Files.createDirectories(out.getParent());
        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Integer mode = modes.get(entry.getName());
        if (mode != null ? (mode & 0111) != 0 : entry.getName().contains("bin/")) {
            out.toFile().setExecutable(true, false);
        }
    }

    /** Time stamp for logs. */
    public static String describe(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
