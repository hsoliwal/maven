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

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private static final SessionData.Key<Store> KEY =
            SessionData.key(Store.class, DefaultM3PluginEvidenceService.class.getName());

    /** Sorted source identities and parallel receipt references, no entry nodes. */
    private static final class Store {
        private Identity[] identities = new Identity[8];
        private Receipt[] receipts = new Receipt[8];
        private int size;

        private static int compare(Identity left, Identity right) {
            int c = left.project().compareTo(right.project());
            if (c == 0) {
                c = left.pluginGa().compareTo(right.pluginGa());
            }
            if (c == 0) {
                c = left.version().compareTo(right.version());
            }
            if (c == 0) {
                c = left.goal().compareTo(right.goal());
            }
            if (c == 0) {
                c = left.execution().compareTo(right.execution());
            }
            if (c == 0) {
                c = left.contextSha256().compareTo(right.contextSha256());
            }
            if (c == 0) {
                c = Long.compare(left.generation(), right.generation());
            }
            return c;
        }

        private int locate(Identity key) {
            int low = 0, high = size;
            while (low < high) {
                int middle = low + ((high - low) >>> 1);
                if (compare(identities[middle], key) < 0) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low < size && compare(identities[low], key) == 0 ? low : -low - 1;
        }

        synchronized void publish(Receipt receipt) throws IOException {
            int found = locate(receipt.identity());
            if (found >= 0) {
                if (!receipts[found].equals(receipt)) {
                    throw new IOException("conflicting plugin receipt for original generation");
                }
                receipt.verify();
                return;
            }
            // Refuse a 513th key BEFORE touching its provider-owned file.
            if (size == MAX_RECEIPTS) {
                throw new IOException("M3 plugin evidence session budget exceeded");
            }
            receipt.verify();
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

        synchronized List<Identity> identities(String schema, int mask) {
            Identity[] matching = new Identity[size];
            int count = 0;
            for (int index = 0; index < size; index++) {
                Receipt receipt = receipts[index];
                if (receipt.schema().equals(schema) && (receipt.capabilities() & mask) == mask) {
                    matching[count++] = identities[index];
                }
            }
            return List.of(Arrays.copyOf(matching, count));
        }

        synchronized Optional<Receipt> find(Identity key, String schema, int mask) throws IOException {
            Receipt receipt = select(key, schema, mask);
            if (receipt == null) {
                return Optional.empty();
            }
            receipt.verify();
            return Optional.of(receipt);
        }

        synchronized Optional<Snapshot> read(Identity key, String schema, int mask) throws IOException {
            Receipt receipt = select(key, schema, mask);
            return receipt == null ? Optional.empty() : Optional.of(receipt.snapshot());
        }

        private Receipt select(Identity key, String schema, int mask) {
            int index = locate(key);
            if (index < 0) {
                return null;
            }
            Receipt receipt = receipts[index];
            if (!receipt.schema().equals(schema) || (receipt.capabilities() & mask) != mask) {
                return null;
            }
            return receipt;
        }

        synchronized void revoke(Identity key) {
            int index = locate(key);
            if (index < 0) {
                return;
            }
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
    public List<Identity> identities(Session session, String schema, int requiredCapabilities) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(schema, "schema");
        if (schema.isBlank() || requiredCapabilities <= 0 || (requiredCapabilities & ~KNOWN_CAPABILITIES) != 0) {
            throw new IllegalArgumentException("requiredCapabilities/schema");
        }
        Store existing = session.getData().get(KEY);
        return existing == null ? List.of() : existing.identities(schema, requiredCapabilities);
    }

    @Override
    public Optional<Receipt> find(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException {
        validateQuery(session, identity, schema, requiredCapabilities);
        Store existing = session.getData().get(KEY);
        return existing == null ? Optional.empty() : existing.find(identity, schema, requiredCapabilities);
    }

    @Override
    public Optional<Snapshot> read(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException {
        validateQuery(session, identity, schema, requiredCapabilities);
        Store existing = session.getData().get(KEY);
        return existing == null ? Optional.empty() : existing.read(identity, schema, requiredCapabilities);
    }

    private static void validateQuery(Session session, Identity identity, String schema, int requiredCapabilities) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(schema, "schema");
        if (schema.isBlank() || requiredCapabilities <= 0 || (requiredCapabilities & ~KNOWN_CAPABILITIES) != 0) {
            throw new IllegalArgumentException("requiredCapabilities/schema");
        }
    }

    @Override
    public void revoke(Session session, Identity identity) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Store existing = session.getData().get(KEY);
        if (existing != null) {
            existing.revoke(identity);
        }
    }
}
