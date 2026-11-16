// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Original-owner boundary proof for bounded optional first-fact lookup. */
public final class ProbeGuardContract {
    private ProbeGuardContract() { }

    private static String[] collisions(int depth) {
        String[] values = {""};
        for (int i = 0; i < depth; i++) {
            String[] next = new String[values.length * 2];
            for (int j = 0; j < values.length; j++) {
                next[j * 2] = values[j] + "Aa";
                next[j * 2 + 1] = values[j] + "BB";
            }
            values = next;
        }
        for (String value : values) {
            if (value.hashCode() != values[0].hashCode()) {
                throw new AssertionError("collision fixture is invalid");
            }
        }
        return values;
    }

    private static SyntaxTree tree() {
        SyntaxTree.Builder builder = SyntaxTree.builder("proof", new byte[]{'x'});
        builder.open("file", "", 0, SyntaxTree.NAMED);
        builder.close(1);
        return builder.build();
    }

    private static boolean flag(SyntaxIndex.Facts facts, String fieldName) throws Exception {
        Field field = SyntaxIndex.Facts.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(facts);
    }

    private static boolean hasIndex(SyntaxIndex.Facts facts) throws Exception {
        Field field = SyntaxIndex.Facts.class.getDeclaredField("firstLookup");
        field.setAccessible(true);
        return field.get(facts) != null;
    }

    public static void main(String[] args) throws Exception {
        String[] keys = collisions(8);
        SyntaxIndex.FactBuilder adversarial = SyntaxIndex.facts(tree());
        for (int i = 0; i < 4096; i++) {
            adversarial.add(0, "owner", keys[i % keys.length], "v" + i);
        }
        SyntaxIndex.Facts facts = adversarial.freeze();
        long checks = 0;
        for (int i = 0; i < 2000; i++) {
            int ordinal = i % keys.length;
            String actual = facts.first(0, "owner:" + keys[ordinal]);
            if (!("v" + ordinal).equals(actual)) {
                throw new AssertionError("earliest colliding duplicate changed: " + i);
            }
            checks++;
        }
        if (!flag(facts, "firstIndexRefused") || hasIndex(facts)) {
            throw new AssertionError("expensive index should have been refused");
        }
        if (facts.first(0, "owner:not-found") != null) {
            throw new AssertionError("missing colliding key must return null");
        }
        // A normal workload must still build and use the optional index.
        SyntaxIndex.FactBuilder ordinary = SyntaxIndex.facts(tree());
        for (int i = 0; i < 4096; i++) ordinary.add(0, "owner", "key" + (i % 1024), "v" + i);
        SyntaxIndex.Facts normal = ordinary.freeze();
        for (int i = 0; i < 1000; i++) {
            String result = normal.first(0, "owner:key" + (i % 4096));
            if (!("v" + (i % 4096)).equals(result)) {
                throw new AssertionError("normal index returned wrong value");
            }
            checks++;
        }
        if (flag(normal, "firstIndexRefused") || !hasIndex(normal)) {
            throw new AssertionError("normal lookup index failed to initialize");
        }
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] readers = new Thread[8];
        for (int id = 0; id < readers.length; id++) {
            final int reader = id;
            readers[id] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        int key = (reader * 37 + i) % keys.length;
                        if (!("v" + key).equals(facts.first(0, "owner:" + keys[key]))) {
                            throw new AssertionError("concurrent fallback drift");
                        }
                    }
                } catch (Throwable e) { error.compareAndSet(null, e); }
            });
        }
        start.countDown();
        for (Thread reader : readers) reader.join();
        if (error.get() != null) throw new AssertionError("concurrent fallback", error.get());
        if (!flag(facts, "firstIndexRefused") || hasIndex(facts)) {
            throw new AssertionError("refused index unexpectedly rebuilt");
        }
        System.out.println("PASS AST_PROBE_GUARD_CURRENT facts=4096 checks=" + checks
                + " colliding_keys=" + keys.length + " concurrent_checks=8000");
    }
}
