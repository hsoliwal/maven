// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.util.Objects;

/** One stable semantic identity and its current leaf or recomposed fingerprint. */
public record M3IndexDbSemanticNode(
        String nodeId,
        M3IndexDbSemanticKind kind,
        String semanticKey,
        String sourcePath,
        String symbol,
        String patternRole,
        M3IndexDbSemanticFingerprint fingerprint) {

    public M3IndexDbSemanticNode {
        nodeId = requireSha(nodeId, "nodeId");
        kind = Objects.requireNonNull(kind, "kind");
        semanticKey = token(semanticKey, "semanticKey");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        symbol = token(symbol, "symbol");
        patternRole = token(patternRole, "patternRole");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (!nodeId.equals(M3IndexDbSemanticIndex.nodeId(kind, semanticKey))) {
            throw new IllegalArgumentException("nodeId does not match semantic identity");
        }
    }

    public M3IndexDbSemanticNode withFingerprint(M3IndexDbSemanticFingerprint replacement) {
        return new M3IndexDbSemanticNode(
                nodeId,
                kind,
                semanticKey,
                sourcePath,
                symbol,
                patternRole,
                replacement);
    }

    public M3IndexDbSemanticNode(
            String nodeId,
            M3IndexDbSemanticKind kind,
            String semanticKey,
            String sourcePath,
            String symbol,
            M3IndexDbSemanticFingerprint fingerprint) {
        this(
                nodeId,
                kind,
                semanticKey,
                sourcePath,
                symbol,
                "M3:" + Objects.requireNonNull(kind, "kind").name(),
                fingerprint);
    }

    private static String requireSha(String value, String field) {
        String checked = Objects.requireNonNull(value, field);
        if (!checked.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field);
        return checked;
    }

    private static String token(String value, String field) {
        String checked = Objects.requireNonNull(value, field).strip();
        if (checked.isEmpty() || checked.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field);
        }
        return checked;
    }
}
