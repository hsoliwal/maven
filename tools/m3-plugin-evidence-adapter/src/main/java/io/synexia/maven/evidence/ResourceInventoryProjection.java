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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import org.apache.maven.api.services.M3PluginEvidenceService;

/**
 * Optional lossy, source-owned projection of observed resource copy operations
 * into Maven's textual resource input/output manifest. No file is read here.
 */
final class ResourceInventoryProjection {
    static final String SCHEMA = "m3.resource-io.manifest.v1";
    static final String FORMAT = "resource-io";
    static final String DEFAULT_FORMAT = "operations";
    private static final int MAX_LOGICAL_PATH_LENGTH = 1024;
    private static final int MAX_ROWS = 65_536;
    private static final String MAGIC_AND_HEADER = "M3-RESOURCE-IO-V1\nrole\tpath\tsha256\tbytes\n";

    private ResourceInventoryProjection() {}

    static byte[] encode(ResourceEvidence evidence) throws IOException {
        Objects.requireNonNull(evidence, "evidence");
        ResourceEvidence.Operation[] byInput =
                evidence.operations().toArray(ResourceEvidence.Operation[]::new);
        Arrays.sort(byInput, Comparator.comparing(ResourceEvidence.Operation::input));
        StringBuilder out = new StringBuilder(MAGIC_AND_HEADER);
        int inputs = 0;
        ResourceEvidence.Operation previous = null;
        for (ResourceEvidence.Operation operation : byInput) {
            requireManifestPath(operation.input());
            requireManifestPath(operation.output());
            if (previous == null || !previous.input().equals(operation.input())) {
                appendRow(out, 'I', operation.input(), operation.inputSha256(), operation.inputBytes());
                inputs++;
            } else if (!previous.inputSha256().equals(operation.inputSha256())
                    || previous.inputBytes() != operation.inputBytes()) {
                throw new IOException("conflicting observations for one resource input");
            }
            previous = operation;
        }
        Arrays.sort(byInput, Comparator.comparing(ResourceEvidence.Operation::output));
        int outputs = 0;
        String lastOutput = null;
        for (ResourceEvidence.Operation operation : byInput) {
            if (operation.output().equals(lastOutput)) {
                throw new IOException("duplicate resource output observation");
            }
            lastOutput = operation.output();
            if (!ResourceEvidence.ABSENT.equals(operation.outputSha256())) {
                appendRow(out, 'O', operation.output(), operation.outputSha256(), operation.outputBytes());
                outputs++;
            }
        }
        if ((long) inputs + outputs > MAX_ROWS) {
            throw new IOException("resource inventory row budget exceeded");
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > M3PluginEvidenceService.MAX_RECEIPT_BYTES) {
            throw new IOException("resource inventory receipt byte budget exceeded");
        }
        return bytes;
    }

    private static void appendRow(StringBuilder out, char role, String path, String sha256, long size) {
        out.append(role).append('\t').append(path).append('\t')
                .append(sha256).append('\t').append(size).append('\n');
    }

    /** More restrictive than the binary operation schema, matching the text consumer. */
    private static void requireManifestPath(String path) throws IOException {
        if (path.isEmpty() || path.length() > MAX_LOGICAL_PATH_LENGTH || path.charAt(0) == '/'
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IOException("resource operation path cannot be represented in inventory");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("noncanonical resource inventory path segment");
            }
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IOException("control character in resource inventory path");
            }
            if (Character.isHighSurrogate(character)) {
                if (++index == path.length() || !Character.isLowSurrogate(path.charAt(index))) {
                    throw new IOException("unpaired surrogate in resource inventory path");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IOException("unpaired surrogate in resource inventory path");
            }
        }
    }
}
