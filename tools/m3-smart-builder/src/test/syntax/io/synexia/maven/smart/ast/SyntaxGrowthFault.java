// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

/** Test-only deterministic failure before an actual grow() array copy. */
public final class SyntaxGrowthFault {
    public static final OutOfMemoryError FAILURE = new OutOfMemoryError("injected AST growth failure");
    private static int countdown;
    private static boolean armed;
    private static int visits;

    private SyntaxGrowthFault() { }
    public static void arm(int at) {
        if (at < 1 || at > 10) throw new IllegalArgumentException("site");
        countdown = at;
        visits = 0;
        armed = true;
    }
    public static void beforeCopy() {
        if (!armed) return;
        visits++;
        if (--countdown == 0) {
            armed = false;
            throw FAILURE;
        }
    }
    public static int visits() { return visits; }
    public static void disarm() { armed = false; }
}
