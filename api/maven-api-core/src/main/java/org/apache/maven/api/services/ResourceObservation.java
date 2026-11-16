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
package org.apache.maven.api.services;

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

/*
 * Added 2026-11-16 by Hitesh Soliwal and Contributors to the Synexia Project:
 * Bounded, versioned observations of original resource goal executions.
 */

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.annotations.Experimental;

/**
 * Immutable observations surrounding a resource goal which returned successfully.
 *
 * <p>Every observation is PARTIAL. A successful return can include a skipped goal;
 * observed source files need not be selected by the plugin, and files in its output
 * directory can belong to other producers. These observations establish neither
 * complete inputs, output ownership, semantic equivalence nor permission to reuse
 * results. The original goal and the existing Maven lifecycle retain authority.
 * Property maps and source contents are omitted. Configured resource paths and
 * patterns are retained, including values produced by configuration interpolation.
 *
 * @param configurationSha256 digest of the producer's observed invocation context
 * @param skipRequested value of the observed public resource skip getter
 * @param resources resource declarations, in original configured order
 * @param outputDirectory observed absolute file URI of the output directory
 * @param before file observations before the original goal
 * @param after file observations after its successful return
 * @param limitations explicit reasons why evidence remains partial
 */
@Experimental
public record ResourceObservation(
        String configurationSha256,
        boolean skipRequested,
        List<ResourceRoot> resources,
        String outputDirectory,
        List<FileState> before,
        List<FileState> after,
        List<String> limitations) {
    public static final String SCHEMA = "maven.resources.observation.v1";
    public static final int MAX_FILES = 4096;
    public static final int MAX_ROOTS = 128;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final long MAGIC = 0x4d33524553303031L;
    private static final Area[] AREAS = Area.values();
    private static final Kind[] KINDS = Kind.values();
    private static final int MAX_TEXT = 8192;
    private static final int MAX_RULES = 256;
    private static final int MAX_LIMITATIONS = 32;

    public ResourceObservation {
        configurationSha256 = digest(configurationSha256);
        resources = bounded(resources, MAX_ROOTS, "resources");
        outputDirectory = fileUri(outputDirectory);
        before = bounded(before, MAX_FILES, "before");
        after = bounded(after, MAX_FILES, "after");
        limitations = strings(limitations, MAX_LIMITATIONS, "limitations");
        if (limitations.isEmpty()) {
            throw new IllegalArgumentException("partial evidence requires limitations");
        }
    }

    /** Returns the fixed completeness of this observation schema. */
    public String completeness() {
        return "PARTIAL";
    }

    /** Distinguishes observed input roots from the shared output directory. */
    public enum Area {
        INPUT,
        OUTPUT
    }

    /** A missing path is recorded so later creation is observable. */
    public enum Kind {
        FILE,
        DIRECTORY,
        MISSING
    }

    /**
     * A resource declaration, without an assertion about selected membership.
     *
     * @param directory absolute file URI of the configured source directory
     * @param targetPath original target path, empty when unspecified
     * @param filtering whether this resource requests filtering
     * @param includes original include patterns in declaration order
     * @param excludes original exclude patterns in declaration order
     */
    public record ResourceRoot(
            String directory, String targetPath, boolean filtering, List<String> includes, List<String> excludes) {
        public ResourceRoot {
            directory = fileUri(directory);
            targetPath = text(targetPath);
            includes = strings(includes, MAX_RULES, "includes");
            excludes = strings(excludes, MAX_RULES, "excludes");
        }
    }

    /**
     * An actual filesystem observation, including metadata relevant to copying.
     *
     * @param area input-root or output-directory observation
     * @param uri absolute file URI
     * @param kind regular file, directory or missing path
     * @param size observed file size, zero for nonfiles
     * @param modifiedMillis observed last modification time, zero for missing paths
     * @param permissions POSIX rwx mode or UNKNOWN when unavailable
     * @param sha256 content SHA-256 for a regular file, empty for nonfiles
     */
    public record FileState(
            Area area, String uri, Kind kind, long size, long modifiedMillis, String permissions, String sha256) {
        public FileState {
            Objects.requireNonNull(area, "area");
            uri = fileUri(uri);
            Objects.requireNonNull(kind, "kind");
            permissions = text(permissions);
            if (!"UNKNOWN".equals(permissions) && !permissions.matches("[r-][w-][x-][r-][w-][x-][r-][w-][x-]")) {
                throw new IllegalArgumentException("permissions");
            }
            if (size < 0 || (kind != Kind.FILE && size != 0) || (kind == Kind.MISSING && modifiedMillis != 0)) {
                throw new IllegalArgumentException("file metadata");
            }
            if (kind == Kind.FILE) {
                sha256 = digest(sha256);
            } else if (!"".equals(sha256)) {
                throw new IllegalArgumentException("nonfile digest");
            }
        }
    }

    /**
     * Writes the bounded v1 binary representation without closing the caller's stream.
     * This format does not use Java object serialization and preserves declaration order.
     *
     * @param destination stream owned by the caller
     * @throws IOException if writing fails or the encoded observation exceeds its byte budget
     */
    public void writeTo(OutputStream destination) throws IOException {
        DataOutputStream out =
                new DataOutputStream(new BoundedOutput(Objects.requireNonNull(destination, "destination")));
        out.writeLong(MAGIC);
        out.writeUTF(configurationSha256);
        out.writeBoolean(skipRequested);
        out.writeUTF(outputDirectory);
        out.writeInt(resources.size());
        for (ResourceRoot resource : resources) {
            out.writeUTF(resource.directory());
            out.writeUTF(resource.targetPath());
            out.writeBoolean(resource.filtering());
            writeStrings(out, resource.includes());
            writeStrings(out, resource.excludes());
        }
        writeFiles(out, before);
        writeFiles(out, after);
        writeStrings(out, limitations);
        out.flush();
    }

    /**
     * Reads exactly one bounded v1 observation, rejecting unknown or trailing data.
     * The caller verifies receipt identity and content custody separately.
     *
     * @param source stream owned by the caller
     * @return immutable partial observation
     * @throws IOException if the encoding, limits or value contracts are invalid
     */
    public static ResourceObservation readFrom(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BoundedInput(Objects.requireNonNull(source, "source")));
        try {
            if (in.readLong() != MAGIC) {
                throw new IOException("unsupported resource observation schema");
            }
            String context = in.readUTF();
            boolean skip = readFlag(in);
            String output = in.readUTF();
            int count = count(in, MAX_ROOTS);
            List<ResourceRoot> roots = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                roots.add(new ResourceRoot(
                        in.readUTF(),
                        in.readUTF(),
                        readFlag(in),
                        readStrings(in, MAX_RULES),
                        readStrings(in, MAX_RULES)));
            }
            ResourceObservation result = new ResourceObservation(
                    context, skip, roots, output, readFiles(in), readFiles(in), readStrings(in, MAX_LIMITATIONS));
            if (in.read() != -1) {
                throw new IOException("trailing resource observation data");
            }
            return result;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("invalid resource observation", invalid);
        }
    }

    private static void writeFiles(DataOutputStream out, List<FileState> files) throws IOException {
        out.writeInt(files.size());
        for (FileState file : files) {
            out.writeByte(file.area().ordinal());
            out.writeUTF(file.uri());
            out.writeByte(file.kind().ordinal());
            out.writeLong(file.size());
            out.writeLong(file.modifiedMillis());
            out.writeUTF(file.permissions());
            out.writeUTF(file.sha256());
        }
    }

    private static List<FileState> readFiles(DataInputStream in) throws IOException {
        int count = count(in, MAX_FILES);
        List<FileState> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int area = in.readUnsignedByte();
            String uri = in.readUTF();
            int kind = in.readUnsignedByte();
            if (area >= AREAS.length || kind >= KINDS.length) {
                throw new IOException("unknown resource observation file kind");
            }
            result.add(new FileState(
                    AREAS[area], uri, KINDS[kind], in.readLong(), in.readLong(), in.readUTF(), in.readUTF()));
        }
        return result;
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            out.writeUTF(value);
        }
    }

    private static List<String> readStrings(DataInputStream in, int maximum) throws IOException {
        int count = count(in, maximum);
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(text(in.readUTF()));
        }
        return result;
    }

    private static boolean readFlag(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) {
            throw new IOException("noncanonical resource observation flag");
        }
        return value == 1;
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > maximum) {
            throw new IOException("resource observation count exceeds budget");
        }
        return value;
    }

    private static String digest(String value) {
        if (value == null || value.length() != 64) {
            throw new IllegalArgumentException("noncanonical SHA-256");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) {
                throw new IllegalArgumentException("noncanonical SHA-256");
            }
        }
        return value;
    }

    private static String fileUri(String value) {
        URI uri = URI.create(text(value));
        if (!"file".equals(uri.getScheme())
                || uri.getPath() == null
                || !uri.getPath().startsWith("/")
                || uri.getFragment() != null
                || uri.getQuery() != null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("absolute file URI required");
        }
        return value;
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "text");
        if (value.length() > MAX_TEXT || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("resource observation text exceeds budget");
        }
        return value;
    }

    private static List<String> strings(List<String> values, int maximum, String name) {
        List<String> result = bounded(values, maximum, name);
        result.forEach(ResourceObservation::text);
        return result;
    }

    private static <T> List<T> bounded(List<T> values, int maximum, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds budget");
        }
        return List.copyOf(values);
    }

    private static final class BoundedInput extends FilterInputStream {
        private int used;

        private BoundedInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++used > MAX_BYTES) {
                throw new IOException("resource observation exceeds byte budget");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            int count = in.read(buffer, offset, Math.min(length, MAX_BYTES - used + 1));
            if (count > 0) {
                used += count;
                if (used > MAX_BYTES) {
                    throw new IOException("resource observation exceeds byte budget");
                }
            }
            return count;
        }
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private int used;

        private BoundedOutput(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            reserve(length);
            out.write(buffer, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length > MAX_BYTES - used) {
                throw new IOException("resource observation exceeds byte budget");
            }
            used += length;
        }
    }
}
