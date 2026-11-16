// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Java21 fault oracle: lossless retry after each of ten grow allocations. */
public final class SyntaxTreeAtomicFailureContract {
    private static final String[] NAMES = {
            "kind", "field", "flags", "parent", "firstChild", "nextSibling",
            "lastChild", "lastEnd", "start", "end"
    };
    private static int checks;
    private SyntaxTreeAtomicFailureContract() { }
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
    private static Field[] fields() throws ReflectiveOperationException {
        Field[] fields = new Field[NAMES.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = SyntaxTree.Builder.class.getDeclaredField(NAMES[i]);
            fields[i].setAccessible(true);
        }
        return fields;
    }
    private static void caseAt(int site, Field[] fields) throws ReflectiveOperationException {
        byte[] source = "x".getBytes(StandardCharsets.UTF_8);
        SyntaxTree.Builder builder = SyntaxTree.builder("failure-atomic", source);
        builder.open("root", "", 0, SyntaxTree.NAMED);
        for (int i = 0; i < 15; i++) builder.leaf("leaf", "item", 0, 0, SyntaxTree.TOKEN);
        Object[] original = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) original[i] = fields[i].get(builder);
        SyntaxGrowthFault.arm(site);
        try {
            builder.leaf("leaf", "item", 0, 0, SyntaxTree.TOKEN);
            throw new AssertionError("expected injected failure at " + site);
        } catch (OutOfMemoryError failure) {
            check(failure == SyntaxGrowthFault.FAILURE, "exact injected exception");
            check(SyntaxGrowthFault.visits() == site, "same failure site");
        } finally {
            SyntaxGrowthFault.disarm();
        }
        for (int i = 0; i < fields.length; i++) {
            check(fields[i].get(builder) == original[i], "unpublished original plane " + NAMES[i] + " at site " + site);
        }
        builder.leaf("leaf", "item", 0, 0, SyntaxTree.TOKEN);
        builder.close(source.length);
        SyntaxTree tree = builder.build();
        check(tree.size() == 17, "retry inserted one row");
        check(tree.parent(16) == 0, "retry parent");
        check(tree.nextSibling(16) == -1, "last sibling after retry");
    }
    public static void main(String[] args) throws ReflectiveOperationException {
        Field[] fields = fields();
        for (int site = 1; site <= 10; site++) caseAt(site, fields);
        check(SyntaxGrowthAgent.SITES.get() == 10, "ten original source array copies instrumented");
        System.out.println("SYNTAX_ATOMIC_FAILURE_PASS checks=" + checks);
    }
}
