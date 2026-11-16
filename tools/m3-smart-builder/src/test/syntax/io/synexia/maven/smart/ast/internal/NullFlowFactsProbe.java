// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Independent scalar, seeded contract oracle; executable without Maven/JUnit. */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/test/java/jdk/internal/m3/ast/NullFlowFactsProbe.java at b7539452eb6c
public final class NullFlowFactsProbe {
    private static long checks;

    private NullFlowFactsProbe() { }

    public static void main(String[] args) {
        long total = prove();
        System.out.println("PASS NULL_FLOW_FACTS scalar_contract_assertions=" + total);
    }

    public static long prove() {
        checks = 0;
        SplittableRandom random = new SplittableRandom(0x5EED_2026_1031L);
        int[] sizes = {0, 1, 2, 7, 31, 32, 33, 63, 64, 65, 127, 128, 129, 511, 513, 1025};
        for (int count : sizes) {
            for (int trial = 0; trial < 180; trial++) {
                NullFlowFacts left = new NullFlowFacts(count);
                NullFlowFacts right = new NullFlowFacts(count);
                int[] expected = new int[count];
                for (int i = 0; i < count; i++) {
                    int a = random.nextInt(3);
                    int b = random.nextInt(3);
                    left.set(i, a);
                    right.set(i, b);
                    expected[i] = a == b ? a : NullFlowFacts.UNKNOWN;
                    same(a, left.state(i));
                    same(b, right.state(i));
                }

                NullFlowFacts result = new NullFlowFacts(count);
                result.joinFrom(left, right);
                verify(result, expected);

                NullFlowFacts aliasLeft = left.copy();
                aliasLeft.joinFrom(aliasLeft, right);
                same(result, aliasLeft);
                NullFlowFacts aliasRight = right.copy();
                aliasRight.joinFrom(left, aliasRight);
                same(result, aliasRight);

                NullFlowFacts repeat = result.copy();
                repeat.joinFrom(repeat, repeat);
                same(result, repeat);
                same(result.hashCode(), repeat.hashCode());

                NullFlowFacts dead = right.copy();
                dead.makeUnreachable();
                result.joinFrom(left, dead);
                same(left, result);
                result.joinFrom(dead, right);
                same(right, result);
                result.joinFrom(dead, dead);
                same(false, result.reachable());
                same(dead, result);

                if (count > 0) {
                    NullFlowFacts refined = new NullFlowFacts(count);
                    int slot = random.nextInt(count);
                    same(true, refined.assume(slot, NullFlowFacts.NONNULL));
                    same(NullFlowFacts.NONNULL, refined.state(slot));
                    same(false, refined.assume(slot, NullFlowFacts.NULL));
                    same(false, refined.reachable());
                    same(false, refined.assume(slot, NullFlowFacts.NONNULL));
                    fails(IllegalStateException.class,
                            () -> refined.set(slot, NullFlowFacts.UNKNOWN));
                }

                long[] exposed = left.snapshotWords();
                int expectedWords = (int) (((long) count + 31L) >>> 5);
                same(expectedWords, exposed.length);
                Arrays.fill(exposed, -1L);
                same(false, Arrays.equals(exposed, left.snapshotWords()) && count > 0);
            }
        }

        NullFlowFacts empty = new NullFlowFacts(0);
        same(true, empty.reachable());
        empty.makeUnreachable();
        same(false, empty.reachable());
        same(false, empty.copy().reachable());

        NullFlowFacts single = new NullFlowFacts(1);
        fails(IllegalArgumentException.class, () -> new NullFlowFacts(-1));
        fails(IndexOutOfBoundsException.class, () -> single.state(-1));
        fails(IndexOutOfBoundsException.class, () -> single.state(1));
        fails(IllegalArgumentException.class, () -> single.set(0, 3));
        fails(IllegalArgumentException.class, () -> single.assume(0, NullFlowFacts.UNKNOWN));
        fails(IllegalArgumentException.class, () -> single.joinFrom(single, new NullFlowFacts(2)));
        fails(NullPointerException.class, () -> single.joinFrom(null, single));
        fails(NullPointerException.class, () -> single.joinFrom(single, null));
        same(true, single.reachable());
        same(NullFlowFacts.UNKNOWN, single.state(0));
        return checks;
    }

    private static void verify(NullFlowFacts result, int[] expected) {
        same(true, result.reachable());
        same(expected.length, result.variableCount());
        for (int i = 0; i < expected.length; i++) same(expected[i], result.state(i));
    }

    private static void same(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void fails(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("wrong refusal type", error);
        }
        throw new AssertionError("expected refusal " + type.getName());
    }
}
