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
package io.synexia.maven.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.api.services.M3PluginEvidenceService.Identity;

/** Immutable observations of bounded original file-filter delegate operations. */
record ResourceEvidence(Identity identity, List<ResourceEvidence.Operation> operations) {
    static final int MAX_OPERATIONS = 1024;
    static final int MAX_PATH_UTF8_BYTES = 2048;
    static final int MAX_IDENTITY_UTF8_BYTES = 1024;
    static final String ABSENT = "ABSENT";
    static final Comparator<Operation> OPERATION_ORDER =
            Comparator.comparing(Operation::output).thenComparing(Operation::input);

    ResourceEvidence {
        validateIdentity(identity);
        Objects.requireNonNull(operations, "operations");
        if (operations.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("resource operation count exceeds limit");
        }
        List<Operation> sorted = new ArrayList<>(operations.size());
        Set<String> destinations = new HashSet<>();
        for (Operation operation : operations) {
            Objects.requireNonNull(operation, "operation");
            if (!destinations.add(operation.output())) {
                throw new IllegalArgumentException("duplicate resource destination");
            }
            sorted.add(operation);
        }
        sorted.sort(OPERATION_ORDER);
        operations = List.copyOf(sorted);
    }

    static void validateIdentity(Identity identity) {
        Objects.requireNonNull(identity, "identity");
        utf8Length(identity.project(), "project", MAX_IDENTITY_UTF8_BYTES);
        utf8Length(identity.pluginGa(), "pluginGa", MAX_IDENTITY_UTF8_BYTES);
        utf8Length(identity.version(), "version", MAX_IDENTITY_UTF8_BYTES);
        utf8Length(identity.goal(), "goal", MAX_IDENTITY_UTF8_BYTES);
        utf8Length(identity.execution(), "execution", MAX_IDENTITY_UTF8_BYTES);
    }

    /**
     * The flag is the unchanged original delegate return value. False can accompany
     * an actual write; it may describe equal, different or absent output bytes.
     */
    record Operation(
            String input,
            String output,
            String inputSha256,
            String outputSha256,
            long inputBytes,
            long outputBytes,
            boolean delegateResult) {
        Operation {
            relativePath(input, "input");
            relativePath(output, "output");
            digest(inputSha256, "inputSha256");
            Objects.requireNonNull(outputSha256, "outputSha256");
            if (inputBytes < 0) {
                throw new IllegalArgumentException("negative resource input size");
            }
            if (ABSENT.equals(outputSha256)) {
                if (outputBytes != -1 || delegateResult) {
                    throw new IllegalArgumentException("invalid absent resource output");
                }
            } else {
                digest(outputSha256, "outputSha256");
                if (outputBytes < 0) {
                    throw new IllegalArgumentException("negative resource output size");
                }
            }
            if (delegateResult && (!inputSha256.equals(outputSha256) || inputBytes != outputBytes)) {
                throw new IllegalArgumentException("true-result resource bytes differ from input");
            }
        }
    }

    private static void relativePath(String path, String field) {
        Objects.requireNonNull(path, field);
        if (path.isEmpty()
                || path.charAt(0) == '/'
                || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0
                || (path.length() >= 2 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':')) {
            throw new IllegalArgumentException("invalid project-relative " + field + " path");
        }
        utf8Length(path, field, MAX_PATH_UTF8_BYTES);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid project-relative " + field + " segment");
            }
        }
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static void digest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() != 64) {
            throw new IllegalArgumentException("invalid " + field);
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f'))) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
    }

    /** Reject malformed UTF-16 before a UTF-8 encoder could replace it silently. */
    private static int utf8Length(String value, String field, int limit) {
        if (value.length() > limit) {
            throw new IllegalArgumentException(field + " exceeds UTF-8 byte limit");
        }
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("malformed Unicode in " + field);
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("malformed Unicode in " + field);
            } else {
                bytes += character <= 0x7f ? 1 : character <= 0x7ff ? 2 : 3;
            }
            if (bytes > limit) {
                throw new IllegalArgumentException(field + " exceeds UTF-8 byte limit");
            }
        }
        return bytes;
    }
}
