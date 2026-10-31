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
package org.apache.maven.api.services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Shared, read-only evidence from original Maven plugin goals. A producer owns
 * its work and output; this service checks receipt-byte custody and makes no
 * cache, build, deployment, semantic-parity or goal-skipping decision.
 * Original compiler and dependency facts retain their specialized owners.
 */
@Experimental
public interface M3PluginEvidenceService extends Service {
    int INPUTS = 1, OUTPUTS = 2, RESOURCES = 4, ARCHIVE = 8;
    int TESTS = 16, DEPENDENCIES = 32, PUBLICATION = 64;
    int KNOWN_CAPABILITIES = INPUTS | OUTPUTS | RESOURCES | ARCHIVE | TESTS | DEPENDENCIES | PUBLICATION;

    /** Exact original Mojo identity and source-bound invocation generation. */
    record Identity(String project, String pluginGa, String version, String goal,
                    String execution, String contextSha256, long generation) {
        public Identity {
            project = name(project, "project");
            pluginGa = name(pluginGa, "pluginGa");
            int separator = pluginGa.indexOf(':');
            if (separator <= 0 || separator == pluginGa.length() - 1
                    || pluginGa.indexOf(':', separator + 1) >= 0) {
                throw new IllegalArgumentException("pluginGa");
            }
            version = name(version, "version");
            goal = name(goal, "goal");
            execution = name(execution, "execution");
            contextSha256 = sha(contextSha256);
            if (generation < 1) throw new IllegalArgumentException("generation");
        }
    }

    /** A producer-owned immutable receipt, never an assertion of goal parity. */
    record Receipt(Identity identity, Path file, String schema, String sha256, int capabilities) {
        public Receipt {
            identity = Objects.requireNonNull(identity, "identity");
            file = Objects.requireNonNull(file, "file");
            schema = name(schema, "schema");
            sha256 = sha(sha256);
            if (capabilities <= 0 || (capabilities & ~KNOWN_CAPABILITIES) != 0) {
                throw new IllegalArgumentException("capabilities");
            }
        }
    }

    /** Binds one immutable provider receipt to this session after byte verification. */
    void publish(Session session, Receipt receipt) throws IOException;

    /** Exact schema/mask/identity match, verified against original file bytes again. */
    Optional<Receipt> find(Session session, Identity identity, String schema, int requiredCapabilities)
            throws IOException;

    /** Revokes a reference without modifying the provider-owned file. */
    void revoke(Session session, Identity identity);

    private static String name(String text, String field) {
        Objects.requireNonNull(text, field);
        if (text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        return text;
    }

    private static String sha(String text) {
        Objects.requireNonNull(text, "sha256");
        if (!text.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256");
        return text;
    }
}
