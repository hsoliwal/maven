// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;

/** Source-bound *syntactic* contract envelope; not a contract or semantic equivalence proof. */
record MachineContractEnvelope(String syntacticContractSha256, int controlMask, int effectMask,
                              String attributedTypeSha256, String staticCallTargetsSha256,
                              int unresolvedCalls, String mayFlowSha256, String defUseSha256,
                              int incompleteMask) {
    private static final int CONTRACT = 0x01;
    private static final int EFFECTS = 0x02;
    private static final int DISPATCH = 0x04;
    private static final int FLOW = 0x08;
    private static final int FLOW_RESIDUE = 0x10;
    private static final int API_ABI = 0x20;

    static MachineContractEnvelope fromValidatedRow(String[] fields) throws IOException {
        // The independent MachineEvidence consumer validates masks, digests, order, coverage and source hash.
        if (fields.length != 7 && fields.length != 9 && fields.length != 13 && fields.length != 21)
            throw new IOException("unsupported contract envelope ledger width");
        return new MachineContractEnvelope(fields[6],
                fields.length >= 9 ? Integer.parseInt(fields[7], 16) : -1,
                fields.length >= 9 ? Integer.parseInt(fields[8], 16) : -1,
                fields.length >= 13 ? fields[11] : null,
                fields.length >= 13 ? fields[12] : null,
                fields.length >= 13 ? Integer.parseInt(fields[10]) : -1,
                fields.length == 21 ? fields[15] : null,
                fields.length == 21 ? fields[19] : null,
                fields.length == 21 ? Integer.parseInt(fields[20], 16) : -1);
    }

    int outstandingProofMask() {
        // Even a complete-looking may-flow graph never discharges semantic, API or dispatch proof.
        return CONTRACT | EFFECTS | DISPATCH | FLOW | API_ABI
                | (incompleteMask > 0 ? FLOW_RESIDUE : 0);
    }

    String json() {
        return "{\"schema\":\"m3.smart.contract-envelope-cues.v1\",\"syntacticContractSha256\":"
                + TinyJson.quote(syntacticContractSha256)
                + ",\"controlFlowMask\":" + controlMask
                + ",\"effectCueMask\":" + effectMask
                + ",\"attributedTypeSha256\":" + maybe(attributedTypeSha256)
                + ",\"staticCallTargetsSha256\":" + maybe(staticCallTargetsSha256)
                + ",\"unresolvedCallSites\":" + unresolvedCalls
                + ",\"mayFlowSha256\":" + maybe(mayFlowSha256)
                + ",\"localDefUseSha256\":" + maybe(defUseSha256)
                + ",\"flowIncompleteMask\":" + incompleteMask
                + ",\"authority\":\"PRODUCER_REPORTED\",\"observableContractProven\":false,"
                + "\"effectsProven\":false,\"apiAbiProven\":false,\"dynamicDispatchProven\":false,"
                + "\"semanticProofAuthenticated\":false}";
    }

    private static String maybe(String value) { return value == null ? "null" : TinyJson.quote(value); }
}
