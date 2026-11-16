// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import java.util.Objects;

/**
 * Method-local ordinal IDs for identities supplied by an attributed compiler.
 * Equal spellings, source offsets, syntax tokens, and user-provided equals()
 * implementations do not establish symbol identity. Callers must supply
 * actual javac/ECJ variable symbols and verify the source/method generation.
 *
 * <p>Keys are retained in one object-reference array and IDs in one primitive
 * array; no Entry/Integer instance is allocated per variable. This holder
 * neither parses sources nor claims a syntax node is a resolved variable.
 * It is mutable only until frozen, confined to one method, and not thread-safe.
 */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/main/java/jdk/internal/m3/ast/NullFlowVariableIds.java at b7539452eb6c
final class NullFlowVariableIds {
    private final String sourceSha256;
    private final String methodIdentity;
    private Object[] symbols = new Object[16];
    private int[] ids = new int[16];
    private int size;
    private boolean frozen;

    NullFlowVariableIds(String sourceSha256, String methodIdentity) {
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected exact source SHA-256");
        }
        Objects.requireNonNull(methodIdentity, "methodIdentity");
        if (methodIdentity.isBlank() || methodIdentity.length() > 1024
                || methodIdentity.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid attributed method identity");
        }
        this.sourceSha256 = sourceSha256;
        this.methodIdentity = methodIdentity;
    }

    String sourceSha256() {
        return sourceSha256;
    }

    String methodIdentity() {
        return methodIdentity;
    }

    int variableCount() {
        return size;
    }

    boolean frozen() {
        return frozen;
    }

    /** First attribution wins; repeat appearances of the same object reuse the ID. */
    int intern(Object attributedSymbol) {
        Objects.requireNonNull(attributedSymbol, "attributedSymbol");
        if (frozen) throw new IllegalStateException("method symbol table frozen");
        int found = findId(attributedSymbol);
        if (found != -1) return found;
        if (size >= symbols.length >>> 1) grow();
        int slot = slotOf(attributedSymbol, symbols);
        symbols[slot] = attributedSymbol;
        ids[slot] = size;
        return size++;
    }

    /** An unresolved or foreign symbol is a miss, never a guessed slot. */
    int findId(Object attributedSymbol) {
        Objects.requireNonNull(attributedSymbol, "attributedSymbol");
        int slot = slotOf(attributedSymbol, symbols);
        return symbols[slot] == null ? -1 : ids[slot];
    }

    int requireId(Object attributedSymbol) {
        int found = findId(attributedSymbol);
        if (found == -1) throw new IllegalArgumentException("unregistered attributed symbol");
        return found;
    }

    void freeze() {
        if (frozen) throw new IllegalStateException("method symbol table already frozen");
        frozen = true;
    }

    /** A fixed symbol population is required before frame slot allocation. */
    NullFlowFacts newFacts() {
        if (!frozen) throw new IllegalStateException("freeze attributed symbols before facts");
        return new NullFlowFacts(size);
    }

    void requireGeneration(String currentSourceSha256, String currentMethodIdentity) {
        if (!sourceSha256.equals(currentSourceSha256)
                || !methodIdentity.equals(currentMethodIdentity)) {
            throw new IllegalStateException("stale attributed method or source generation");
        }
    }

    private void grow() {
        if (symbols.length >= 1 << 29) {
            throw new IllegalStateException("attributed symbol table capacity exhausted");
        }
        Object[] expandedSymbols = new Object[symbols.length << 1];
        int[] expandedIds = new int[expandedSymbols.length];
        for (int i = 0; i < symbols.length; i++) {
            Object symbol = symbols[i];
            if (symbol != null) {
                int slot = slotOf(symbol, expandedSymbols);
                expandedSymbols[slot] = symbol;
                expandedIds[slot] = ids[i];
            }
        }
        // Publish both arrays only after a complete rehash.
        symbols = expandedSymbols;
        ids = expandedIds;
    }

    private static int slotOf(Object symbol, Object[] array) {
        int hash = System.identityHashCode(symbol);
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        int slot = hash & (array.length - 1);
        while (array[slot] != null && array[slot] != symbol) {
            slot = (slot + 1) & (array.length - 1);
        }
        return slot;
    }
}
