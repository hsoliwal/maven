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
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.api.services.M3PluginEvidenceService.Snapshot;

/** Deterministic diagnostic JSON; verified bytes do not authorize output reuse. */
final class CapabilityReport {
    static final String SCHEMA = "m3.resources.capability-report.v1";
    private static final String HEX = "0123456789abcdef";

    private CapabilityReport() {}

    static byte[] render(ResourceEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        StringBuilder output = prefix(evidence.identity(), "KNOWN");
        int delegateTrueCount = 0;
        for (ResourceEvidence.Operation operation : evidence.operations()) {
            if (operation.delegateResult()) {
                delegateTrueCount++;
            }
        }
        output.append(",\"verifiedCapabilities\":").append(ResourceEvidenceCodec.CAPABILITIES);
        output.append(",\"operationCount\":").append(evidence.operations().size());
        output.append(",\"delegateTrueCount\":").append(delegateTrueCount);
        output.append(",\"delegateFalseCount\":").append(evidence.operations().size() - delegateTrueCount);
        output.append(",\"operations\":[");
        boolean separator = false;
        for (ResourceEvidence.Operation operation : evidence.operations()) {
            if (separator) {
                output.append(',');
            }
            operation(output, operation);
            separator = true;
        }
        return output.append("]}\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] read(Session session, M3PluginEvidenceService service, Identity identity) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(identity, "identity");
        Optional<Snapshot> snapshot =
                service.read(session, identity, ResourceEvidenceCodec.SCHEMA, ResourceEvidenceCodec.CAPABILITIES);
        if (snapshot.isEmpty()) {
            return unavailable(identity);
        }
        Receipt receipt = snapshot.get().receipt();
        if (!receipt.identity().equals(identity)
                || !receipt.schema().equals(ResourceEvidenceCodec.SCHEMA)
                || (receipt.capabilities() & ResourceEvidenceCodec.CAPABILITIES) != ResourceEvidenceCodec.CAPABILITIES) {
            throw new IOException("resource receipt snapshot does not match the requested capability");
        }
        return render(ResourceEvidenceCodec.decode(snapshot.get().bytes(), identity));
    }

    static byte[] unavailable(Identity identity) {
        Objects.requireNonNull(identity, "identity");
        return prefix(identity, "UNAVAILABLE")
                .append(",\"verifiedCapabilities\":null,\"operationCount\":null,\"delegateTrueCount\":null")
                .append(",\"delegateFalseCount\":null,\"operations\":null}\n")
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    private static StringBuilder prefix(Identity identity, String status) {
        ResourceEvidence.validateIdentity(identity);
        StringBuilder output = new StringBuilder(1024);
        output.append("{\"schema\":");
        string(output, SCHEMA);
        output.append(",\"evidenceSchema\":");
        string(output, ResourceEvidenceCodec.SCHEMA);
        output.append(",\"status\":");
        string(output, status);
        output.append(",\"adapterRole\":\"records-original-filtering-copy-results\"");
        output.append(",\"delegateResultMeaning\":\"original-copyFileWithResult-return-value\"");
        output.append(",\"donors\":{\"resources\":");
        string(output, ResourceEvidenceCodec.RESOURCES_DONOR);
        output.append(",\"resourcesSourceCommit\":\"5c08057fc76dc25bc54faf332baf3af998b89b4a\"");
        output.append(",\"filtering\":");
        string(output, ResourceEvidenceCodec.FILTERING_DONOR);
        output.append(",\"filteringSourceCommit\":\"131d0cbb9848e320eccf181c83e9b2557f53880b\"}");
        output.append(",\"identity\":{");
        output.append("\"project\":");
        string(output, identity.project());
        output.append(",\"pluginGa\":");
        string(output, identity.pluginGa());
        output.append(",\"version\":");
        string(output, identity.version());
        output.append(",\"goal\":");
        string(output, identity.goal());
        output.append(",\"execution\":");
        string(output, identity.execution());
        output.append(",\"contextSha256\":");
        string(output, identity.contextSha256());
        output.append(",\"generation\":").append(identity.generation()).append('}');
        output.append(",\"requiredCapabilities\":").append(ResourceEvidenceCodec.CAPABILITIES);
        output.append(",\"complete\":false,\"cacheEligible\":false");
        return output;
    }

    private static void operation(StringBuilder output, ResourceEvidence.Operation operation) {
        output.append("{\"input\":");
        string(output, operation.input());
        output.append(",\"output\":");
        string(output, operation.output());
        output.append(",\"inputSha256\":");
        string(output, operation.inputSha256());
        output.append(",\"outputSha256\":");
        string(output, operation.outputSha256());
        output.append(",\"inputBytes\":").append(operation.inputBytes());
        output.append(",\"outputBytes\":").append(operation.outputBytes());
        output.append(",\"delegateResult\":").append(operation.delegateResult());
        output.append(",\"result\":");
        string(output, operation.delegateResult() ? "DELEGATE_TRUE" : "DELEGATE_FALSE");
        output.append('}');
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        output.append("\\u")
                                .append(HEX.charAt((character >>> 12) & 15))
                                .append(HEX.charAt((character >>> 8) & 15))
                                .append(HEX.charAt((character >>> 4) & 15))
                                .append(HEX.charAt(character & 15));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
