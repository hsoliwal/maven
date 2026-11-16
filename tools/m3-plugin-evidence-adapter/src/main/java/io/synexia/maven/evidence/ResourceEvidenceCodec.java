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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;

/** Canonical, bounded version-one framing for resource operation receipts. */
final class ResourceEvidenceCodec {
    static final String SCHEMA = "m3.resources.operations.v1";
    static final int CAPABILITIES = M3PluginEvidenceService.INPUTS
            | M3PluginEvidenceService.OUTPUTS
            | M3PluginEvidenceService.RESOURCES;
    static final String RESOURCES_DONOR = "org.apache.maven.plugins:maven-resources-plugin:4.0.0-beta-2";
    static final String FILTERING_DONOR = "org.apache.maven.shared:maven-filtering:4.0.0-beta-2";
    private static final int MAGIC = 0x4d335245;
    private static final int VERSION = 1;

    private ResourceEvidenceCodec() {}

    static byte[] encode(ResourceEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Identity identity = evidence.identity();
        long length = 3L * Integer.BYTES + Long.BYTES;
        for (String text : identityStrings(identity)) {
            length += framedLength(text);
        }
        for (ResourceEvidence.Operation operation : evidence.operations()) {
            length += framedLength(operation.input());
            length += framedLength(operation.output());
            length += framedLength(operation.inputSha256());
            length += framedLength(operation.outputSha256());
            length += 2L * Long.BYTES + 1;
        }
        if (length > M3PluginEvidenceService.MAX_RECEIPT_BYTES) {
            throw new IllegalArgumentException("resource receipt byte limit exceeded");
        }
        ByteBuffer output = ByteBuffer.allocate((int) length).order(ByteOrder.BIG_ENDIAN);
        output.putInt(MAGIC).putInt(VERSION);
        for (String text : identityStrings(identity)) {
            putText(output, text);
        }
        output.putLong(identity.generation()).putInt(evidence.operations().size());
        for (ResourceEvidence.Operation operation : evidence.operations()) {
            putText(output, operation.input());
            putText(output, operation.output());
            putText(output, operation.inputSha256());
            putText(output, operation.outputSha256());
            output.putLong(operation.inputBytes()).putLong(operation.outputBytes());
            output.put((byte) (operation.delegateResult() ? 1 : 0));
        }
        return output.array();
    }

    static ResourceEvidence decode(byte[] bytes, Identity expected) throws IOException {
        if (bytes == null || expected == null) {
            throw new IOException("resource receipt bytes and expected identity are required");
        }
        if (bytes.length > M3PluginEvidenceService.MAX_RECEIPT_BYTES) {
            throw new IOException("resource receipt byte limit exceeded");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        try {
            if (input.getInt() != MAGIC || input.getInt() != VERSION) {
                throw new IOException("unsupported resource receipt magic or version");
            }
            Identity identity = new Identity(
                    readText(input, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES),
                    readText(input, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES),
                    readText(input, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES),
                    readText(input, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES),
                    readText(input, ResourceEvidence.MAX_IDENTITY_UTF8_BYTES),
                    readText(input, 64),
                    input.getLong());
            if (!identity.equals(expected)) {
                throw new IOException("resource receipt invocation identity mismatch");
            }
            int count = input.getInt();
            if (count < 0 || count > ResourceEvidence.MAX_OPERATIONS) {
                throw new IOException("resource operation count exceeds limit");
            }
            List<ResourceEvidence.Operation> operations = new ArrayList<>(count);
            ResourceEvidence.Operation previous = null;
            for (int index = 0; index < count; index++) {
                ResourceEvidence.Operation operation = readOperation(input);
                if (previous != null && ResourceEvidence.OPERATION_ORDER.compare(previous, operation) >= 0) {
                    throw new IOException("resource operations are not in canonical order");
                }
                operations.add(operation);
                previous = operation;
            }
            if (input.hasRemaining()) {
                throw new IOException("trailing resource receipt bytes");
            }
            return new ResourceEvidence(identity, operations);
        } catch (BufferUnderflowException | IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("invalid resource receipt framing or values", invalid);
        }
    }

    private static ResourceEvidence.Operation readOperation(ByteBuffer input) throws IOException {
        String source = readText(input, ResourceEvidence.MAX_PATH_UTF8_BYTES);
        String destination = readText(input, ResourceEvidence.MAX_PATH_UTF8_BYTES);
        String sourceHash = readText(input, 64);
        String destinationHash = readText(input, 64);
        long sourceBytes = input.getLong();
        long destinationBytes = input.getLong();
        int delegateResult = Byte.toUnsignedInt(input.get());
        if (delegateResult > 1) {
            throw new IOException("invalid resource delegate result flag");
        }
        return new ResourceEvidence.Operation(
                source, destination, sourceHash, destinationHash, sourceBytes, destinationBytes, delegateResult == 1);
    }

    private static List<String> identityStrings(Identity identity) {
        return List.of(
                identity.project(),
                identity.pluginGa(),
                identity.version(),
                identity.goal(),
                identity.execution(),
                identity.contextSha256());
    }

    private static int framedLength(String value) {
        return Integer.BYTES + value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void putText(ByteBuffer output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.putInt(bytes.length).put(bytes);
    }

    private static String readText(ByteBuffer input, int limit) throws IOException {
        int length = input.getInt();
        if (length < 0 || length > limit || length > input.remaining()) {
            throw new IOException("invalid resource receipt text length");
        }
        ByteBuffer framed = input.slice();
        framed.limit(length);
        try {
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(framed)
                    .toString();
            input.position(input.position() + length);
            return value;
        } catch (CharacterCodingException invalid) {
            throw new IOException("malformed UTF-8 in resource receipt", invalid);
        }
    }
}
