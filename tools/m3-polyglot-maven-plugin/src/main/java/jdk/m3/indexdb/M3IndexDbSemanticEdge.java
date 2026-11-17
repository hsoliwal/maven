// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.util.Objects;

/** Ordered semantic composition edge. */
public record M3IndexDbSemanticEdge(
        String parentId,
        String childId,
        String role,
        int ordinal) {

    public M3IndexDbSemanticEdge {
        parentId = requireSha(parentId, "parentId");
        childId = requireSha(childId, "childId");
        role = token(role, "role");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal < 0");
        if (parentId.equals(childId)) throw new IllegalArgumentException("self edge");
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
