/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.M3PluginEvidenceService;

/**
 * Native Maven 4 session reference table. No second parser, file-delta ledger,
 * build cache, Mojo runner, network access, or retained source payloads.
 */
@Named
@Singleton
public final class DefaultM3PluginEvidenceService implements M3PluginEvidenceService {
    private static final int MAX_RECEIPTS = 512;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final SessionData.Key<Store> KEY =
            SessionData.key(Store.class, DefaultM3PluginEvidenceService.class.getName());

    /** Sorted source identities and parallel receipt references, no entry nodes. */
    private static final class Store {
        private Identity[] identities = new Identity[8];
        private Receipt[] receipts = new Receipt[8];
        private int size;

        private static int compare(Identity left, Identity right) {
            int c = left.project().compareTo(right.project());
            if (c == 0) c = left.pluginGa().compareTo(right.pluginGa());
            if (c == 0) c = left.version().compareTo(right.version());
            if (c == 0) c = left.goal().compareTo(right.goal());
            if (c == 0) c = left.execution().compareTo(right.execution());
            if (c == 0) c = left.contextSha256().compareTo(right.contextSha256());
            if (c == 0) c = Long.compare(left.generation(), right.generation());
            return c;
        }

        private int locate(Identity key) {
            int low = 0, high = size;
            while (low < high) {
                int middle = low + ((high - low) >>> 1);
                if (compare(identities[middle], key) < 0) low = middle + 1;
                else high = middle;
            }
            return low < size && compare(identities[low], key) == 0 ? low : -low - 1;
        }

        synchronized void publish(Receipt receipt) throws IOException {
            int found = locate(receipt.identity());
            if (found >= 0) {
                if (!receipts[found].equals(receipt)) {
                    throw new IOException("conflicting plugin receipt for original generation");
                }
                verify(receipt);
                return;
            }
            // Refuse a 513th key BEFORE touching its provider-owned file.
            if (size == MAX_RECEIPTS) throw new IOException("M3 plugin evidence session budget exceeded");
            verify(receipt);
            int insertion = -found - 1;
            if (size == identities.length) {
                identities = Arrays.copyOf(identities, Math.min(MAX_RECEIPTS, size * 2));
                receipts = Arrays.copyOf(receipts, identities.length);
            }
            System.arraycopy(identities, insertion, identities, insertion + 1, size - insertion);
            System.arraycopy(receipts, insertion, receipts, insertion + 1, size - insertion);
            identities[insertion] = receipt.identity();
            receipts[insertion] = receipt;
            size++;
        }

        synchronized Optional<Receipt> find(Identity key, String schema, int mask) throws IOException {
            int index = locate(key);
            if (index < 0) return Optional.empty();
            Receipt receipt = receipts[index];
            if (!receipt.schema().equals(schema) || (receipt.capabilities() & mask) != mask) {
                return Optional.empty();
            }
            verify(receipt);
            return Optional.of(receipt);
        }

        synchronized void revoke(Identity key) {
            int index = locate(key);
            if (index < 0) return;
            int remaining = size - index - 1;
            System.arraycopy(identities, index + 1, identities, index, remaining);
            System.arraycopy(receipts, index + 1, receipts, index, remaining);
            identities[--size] = null;
            receipts[size] = null;
        }
    }

    @Override
    public void publish(Session session, Receipt receipt) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(receipt, "receipt");
        session.getData().computeIfAbsent(KEY, Store::new).publish(receipt);
    }

    @Override
    public Optional<Receipt> find(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(schema, "schema");
        if (schema.isBlank() || requiredCapabilities <= 0
                || (requiredCapabilities & ~KNOWN_CAPABILITIES) != 0) {
            throw new IllegalArgumentException("requiredCapabilities/schema");
        }
        Store existing = session.getData().get(KEY);
        return existing == null ? Optional.empty() : existing.find(identity, schema, requiredCapabilities);
    }

    @Override
    public void revoke(Session session, Identity identity) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Store existing = session.getData().get(KEY);
        if (existing != null) existing.revoke(identity);
    }

    private static void verify(Receipt receipt) throws IOException {
        Path file = receipt.file();
        BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attr.isRegularFile() || attr.size() > MAX_BYTES) {
            throw new IOException("plugin evidence requires a bounded regular source-owned file");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream input = Channels.newInputStream(
                Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] buffer = new byte[16_384];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (read == 0) throw new IOException("stalled plugin evidence read");
                total += read;
                if (total > MAX_BYTES) throw new IOException("plugin evidence byte budget exceeded");
                digest.update(buffer, 0, read);
            }
            if (total != attr.size()) throw new IOException("plugin evidence changed during read");
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(receipt.sha256())) {
            throw new IOException("plugin evidence content digest mismatch");
        }
    }
}
