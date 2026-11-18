/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Additive Java 17 SPI for ORIGINAL Eclipse language-plugin source evidence.
 *
 * <p>Implementations are installed, explicitly trusted Java JARs registered
 * through ServiceLoader. Each provider owns a versioned compact packet
 * produced by its original Eclipse builder/language implementation, NOT an
 * inferred compiler command. Maven/Gradle never install SDKs or execute a
 * compiler by discovering this SPI. A provider may inspect at most 1 MiB of
 * already bounded packet bytes but cannot authorize cache admission.</p>
 *
 * <p>Implementations MUST validate their own language-specific packet
 * schema, encoding, origin and metadata before returning Evidence. The
 * host independently verifies the exact Eclipse project/configuration/
 * invocation plus matching installed builder or nature declarations.</p>
 */
public interface M3EclipseLanguageEvidenceProvider {
    int API_VERSION = 1;

    /** Stable lowercase identifier. Must be unique on the selected classpath. */
    String id();

    /** Exact original Eclipse org.eclipse.core.resources.builders IDs. */
    Set<String> builderIds();

    /** Exact original Eclipse project nature IDs. */
    Set<String> natureIds();

    /** No compatibility promise with a different SPI version. */
    default int apiVersion() { return API_VERSION; }

    /** Parse native owner evidence without executing any build or network call. */
    Evidence decode(byte[] sourceOwnerPacket) throws IOException;

    /**
     * Detached source-owned metadata; never a successful compiler result.
     * The source owner may have richer language-specific sidecars, but the
     * universal transport is bounded, deterministic and Java-agnostic.
     */
    record Evidence(String schema, String project, String invocation,
            String selectedConfiguration, int observedCount, String observedSha256) {
        public Evidence {
            Objects.requireNonNull(schema, "language evidence schema");
            if (!schema.matches("M3-[A-Z0-9-]{1,100}-V[1-9][0-9]*")) {
                throw new IllegalArgumentException("invalid versioned language evidence schema");
            }
            project = checked(project, false);
            selectedConfiguration = checked(selectedConfiguration, true);
            Objects.requireNonNull(invocation, "original Eclipse invocation");
            try {
                if (!UUID.fromString(invocation).toString().equals(invocation)) {
                    throw new IllegalArgumentException("noncanonical Eclipse invocation UUID");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid original Eclipse invocation", invalid);
            }
            if (observedCount < 0 || observedCount > 10_000_000
                    || observedSha256 == null || !observedSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid original language evidence fingerprint");
            }
        }

        /** This is advisory, even when project and invocation match. */
        public boolean authorizesBuildSkipping() { return false; }
    }

    private static String checked(String text, boolean empty) {
        Objects.requireNonNull(text, "original language source owner");
        if ((!empty && text.isBlank()) || text.length() > 4096
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid language owner identity");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("invalid original language Unicode identity");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid original language Unicode identity");
            }
        }
        return text;
    }
}
