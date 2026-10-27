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
package org.apache.maven.impl;

// Modified 2026-10-21 by Hitesh Soliwal and Contributors to the Synexia Project:
// Select and verify the existing production constructor in native Maven DI.

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.M3BuildToolsService;

/**
 * Original Maven 4 session service; the original JDK owns all classfile work.
 * Sorted parallel cache lanes are bounded and never retain an AST or class bytes.
 */
@Named
@Singleton
public final class DefaultM3BuildToolsService implements M3BuildToolsService {
    private static final int MAX_RECEIPTS = 256;
    private static final int MAX_RESULT_CHARS = 4_194_304;
    private static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;
    private static final SessionData.Key<Store> KEY =
            SessionData.key(Store.class, DefaultM3BuildToolsService.class.getName());
    private final Supplier<Optional<ToolProvider>> provider;
    private final Supplier<Path> runtimeHome;

    @Inject
    public DefaultM3BuildToolsService() {
        this(() -> ToolProvider.findFirst(DEPENDENCY_PROVIDER), () -> Path.of(System.getProperty("java.home")));
    }

    /* Package-private for differential tests, not an API for plugin injection. */
    DefaultM3BuildToolsService(Supplier<Optional<ToolProvider>> provider, Supplier<Path> runtimeHome) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.runtimeHome = Objects.requireNonNull(runtimeHome, "runtimeHome");
    }

    @Override
    public Optional<Receipt> dependencies(Session session, Request request) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        // JVM-resident tools are correct only when selected toolchain matches
        // the current runtime. An external compiler needs an explicit IPC plan.
        if (!Files.isSameFile(request.selectedJdkHome(), runtimeHome.get())) {
            return Optional.empty();
        }
        Optional<ToolProvider> selected = provider.get();
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        if (!DEPENDENCY_PROVIDER.equals(selected.get().name())) {
            throw new IOException("selected provider does not implement M3 class dependency facts");
        }
        return Optional.of(session.getData().computeIfAbsent(KEY, Store::new).admit(request, selected.get()));
    }

    /** Store 256 key/receipt pairs; identity sorted for binary search. */
    private static final class Store {
        private String[] keys = new String[8];
        private Receipt[] receipts = new Receipt[8];
        private int size;

        synchronized Receipt admit(Request request, ToolProvider provider) throws IOException {
            Path file = request.classFile().toAbsolutePath().normalize();
            // The original SDK tool also refuses symlinks. Check before invoking
            // a provider, so failed input admission never performs analysis.
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("M3 class dependency input must be regular and not a symlink");
            }
            String key = file.toString() + '\0' + (request.publicApiOnly() ? 'A' : 'C');
            int at = Arrays.binarySearch(keys, 0, size, key);
            if (at >= 0) {
                // Re-hash exact bytes, not timestamps: a changed file with
                // restored attributes must not reuse an old analyzer receipt.
                String now = sha256(file);
                if (now.equals(receipts[at].classSha256())) {
                    return receipts[at];
                }
                remove(at);
            }
            Receipt result = invoke(provider, request.publicApiOnly(), file);
            int slot = Arrays.binarySearch(keys, 0, size, key);
            if (slot >= 0) {
                throw new IllegalStateException("duplicate facts cache slot");
            }
            slot = -slot - 1;
            if (size == MAX_RECEIPTS) {
                throw new IOException("M3 build receipt session budget exceeded");
            }
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, Math.min(MAX_RECEIPTS, keys.length * 2));
                receipts = Arrays.copyOf(receipts, keys.length);
            }
            System.arraycopy(keys, slot, keys, slot + 1, size - slot);
            System.arraycopy(receipts, slot, receipts, slot + 1, size - slot);
            keys[slot] = key;
            receipts[slot] = result;
            size++;
            return result;
        }

        private void remove(int index) {
            int remaining = size - index - 1;
            System.arraycopy(keys, index + 1, keys, index, remaining);
            System.arraycopy(receipts, index + 1, receipts, index, remaining);
            size--;
            keys[size] = null;
            receipts[size] = null;
        }
    }

    private static Receipt invoke(ToolProvider provider, boolean publicApi, Path classFile) throws IOException {
        LimitedWriter stdout = new LimitedWriter(MAX_RESULT_CHARS);
        LimitedWriter stderr = new LimitedWriter(4096);
        PrintWriter out = new PrintWriter(stdout), err = new PrintWriter(stderr);
        int status = publicApi
                ? provider.run(out, err, "--api", classFile.toString())
                : provider.run(out, err, classFile.toString());
        out.flush();
        err.flush();
        if (stdout.overflow || stderr.overflow || out.checkError() || err.checkError() || status != 0) {
            throw new IOException("M3JDK21 build tool failed or exceeded output budget: " + status);
        }
        String receipt = stdout.toString();
        int firstEnd = receipt.indexOf('\n');
        if (firstEnd < 0) {
            throw new IOException("missing native build-fact header");
        }
        // The provider writes platform-native line endings. Validate each record
        // without modifying the original source receipt or its SHA-256 identity.
        final boolean crlf = firstEnd > 0 && receipt.charAt(firstEnd - 1) == '\r';
        String[] header = receipt.substring(0, crlf ? firstEnd - 1 : firstEnd).split("\t", -1);
        String expected = publicApi ? "PUBLIC_API" : "CLASS";
        if (header.length != 5
                || !DEPENDENCY_SCHEMA.equals(header[0])
                || !expected.equals(header[1])
                || !header[2].matches("[0-9a-f]{64}")
                || header[3].isEmpty()) {
            throw new IOException("unrecognized M3JDK21 dependency fact schema");
        }
        try {
            int count = Integer.parseInt(header[4]);
            if (count < 0 || count > 131_072) {
                throw new IOException("dependency fact row budget exceeded");
            }
            int seen = 0, start = firstEnd + 1;
            while (start < receipt.length()) {
                int end = receipt.indexOf('\n', start);
                if (end < 0) {
                    throw new IOException("malformed dependency fact row");
                }
                final boolean rowCrlf = end > start && receipt.charAt(end - 1) == '\r';
                if (rowCrlf != crlf
                        || !receipt.substring(start, rowCrlf ? end - 1 : end)
                                .matches("D\\t[A-Za-z0-9_-]+")) {
                    throw new IOException("malformed dependency fact row");
                }
                seen++;
                start = end + 1;
            }
            if (seen != count) {
                throw new IOException("dependency count differs from fact rows");
            }
        } catch (NumberFormatException invalid) {
            throw new IOException("bad dependency row count", invalid);
        }
        // Validate source generation *after* the provider reads it. A concurrent
        // writer cannot authorize a completed receipt with mismatched bytes.
        if (!sha256(classFile).equals(header[2])) {
            throw new IOException("class changed during fact publication");
        }
        return new Receipt(header[0], header[1], header[2], receipt);
    }

    private static String sha256(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3 class dependency input must be regular and not a symlink");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long length = 0L;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] block = new byte[32 * 1024];
            for (int n; (n = input.read(block)) != -1; ) {
                if (n == 0) {
                    throw new IOException("stalled class hash input");
                }
                length += n;
                if (length > MAX_CLASS_BYTES) {
                    throw new IOException("class hash byte budget exceeded");
                }
                digest.update(block, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static final class LimitedWriter extends Writer {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean overflow;

        LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] data, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, data.length);
            if (len > limit - text.length()) {
                overflow = true;
                throw new IOException("build tool output budget");
            }
            text.append(data, off, len);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
