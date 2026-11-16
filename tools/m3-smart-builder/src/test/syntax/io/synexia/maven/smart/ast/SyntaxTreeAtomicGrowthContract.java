// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone Java21 contract for aligned, primitive M3 AST builder columns. */
public final class SyntaxTreeAtomicGrowthContract {
    private static final String[] COLUMN_NAMES = {
            "kind", "field", "flags", "parent", "firstChild", "nextSibling",
            "lastChild", "lastEnd", "start", "end"
    };
    private static int checks;
    private SyntaxTreeAtomicGrowthContract() { }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void aligned(SyntaxTree.Builder builder, Field[] columns)
            throws ReflectiveOperationException {
        int length = ((int[]) columns[0].get(builder)).length;
        for (Field column : columns) {
            check(((int[]) column.get(builder)).length == length,
                    "unaligned builder column " + column.getName());
        }
    }

    private static Field[] columns() throws ReflectiveOperationException {
        Field[] result = new Field[COLUMN_NAMES.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = SyntaxTree.Builder.class.getDeclaredField(COLUMN_NAMES[i]);
            result[i].setAccessible(true);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        byte[] source = "x".getBytes(StandardCharsets.UTF_8);
        Field[] columns = columns();
        for (int leaves : new int[] {0, 1, 14, 15, 16, 17, 24, 25, 26, 39, 40, 41, 63, 64, 65, 5000}) {
            SyntaxTree.Builder builder = SyntaxTree.builder("atomic-growth", source);
            builder.open("root", "", 0, SyntaxTree.NAMED);
            aligned(builder, columns);
            for (int i = 0; i < leaves; i++) {
                builder.leaf("leaf", "item", 0, 0, SyntaxTree.TOKEN);
                if ((i & 31) == 0 || i == leaves - 1) aligned(builder, columns);
            }
            builder.close(source.length);
            SyntaxTree tree = builder.build();
            check(tree.size() == leaves + 1, "preorder node count");
            check(tree.firstChild(0) == (leaves == 0 ? -1 : 1), "first child");
            if (leaves > 0) {
                check(tree.parent(leaves) == 0, "last child parent");
                check(tree.nextSibling(leaves) == -1, "last sibling terminator");
                check(tree.kind(leaves).equals("leaf"), "node kind after growth");
            }
            check(Arrays.equals(tree.sourceBytes(), source), "source snapshot");
            check(tree.projectionSha256().matches("[0-9a-f]{64}"), "source-bound digest");
        }
        System.out.println("SYNTAX_TEN_PLANE_GROWTH_PASS checks=" + checks);
    }
}
