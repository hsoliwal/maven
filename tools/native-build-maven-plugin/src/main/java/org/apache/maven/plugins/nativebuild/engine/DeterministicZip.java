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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip archives whose bytes depend only on the files: entries sorted by path, one fixed time (Maven's
 * {@code project.build.outputTimestamp}, else 2000-01-01), no directory entries. Also unpacks a zip without letting
 * an entry escape the target folder.
 */
public final class DeterministicZip {
    /** 2000-01-01T00:00:00Z, the time used without an outputTimestamp. */
    public static final long DEFAULT_TIME = 946_684_800_000L;

    private DeterministicZip() {}

    /** Writes {@code files} (relative to {@code root}, forward slashes) into {@code zip} in sorted order. */
    public static void write(Path zip, Path root, List<String> files, long epochMillis) throws IOException {
        Files.createDirectories(zip.toAbsolutePath().getParent());
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            out.setLevel(6);
            for (String name : files.stream().sorted().toList()) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(epochMillis);
                out.putNextEntry(entry);
                Files.copy(root.resolve(name), out);
                out.closeEntry();
            }
        }
    }

    /** Milliseconds of an outputTimestamp: ISO-8601 with offset, or seconds since the epoch; else the default. */
    public static long epoch(String outputTimestamp) {
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
        try (InputStream file = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(file)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                extractEntry(in, entry, root, zip);
            }
        }
    }

    private static void extractEntry(ZipInputStream in, ZipEntry entry, Path root, Path zip) throws IOException {
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
        if (entry.getName().contains("bin/")) {
            out.toFile().setExecutable(true, false);
        }
    }

    /** Time stamp for logs. */
    static String describe(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
