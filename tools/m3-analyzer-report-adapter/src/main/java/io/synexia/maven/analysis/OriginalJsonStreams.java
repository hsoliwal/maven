/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.analysis;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * The one defensive JSON parser plan shared by original SARIF and Semgrep
 * adapters. No JSON tree materialization, implicit network I/O or global
 * parser configuration changes.
 */
final class OriginalJsonStreams {
    private OriginalJsonStreams() {}

    static JsonFactory factory() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(48)
                .maxStringLength(8_192)
                .maxNameLength(512)
                .maxNumberLength(32)
                .build());
        return factory;
    }
}
