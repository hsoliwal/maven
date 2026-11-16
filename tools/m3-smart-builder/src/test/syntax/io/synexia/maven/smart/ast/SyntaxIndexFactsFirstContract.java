// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executed by the Maven verification crate against its exact, pinned
 * current-owner SyntaxIndex.java postimage, not against a rewritten facade.
 */
public final class SyntaxIndexFactsFirstContract {
    private SyntaxIndexFactsFirstContract() { }

    private static String reference(SyntaxIndex.Facts facts, int node, String name) {
        Objects.requireNonNull(name, "name");
        for (int i = 0; i < facts.count(node); i++) {
            if (facts.key(node, i).equals(name)) return facts.value(node, i);
        }
        return null;
    }

    private static void check(SyntaxIndex.Facts facts, int node, String key) {
        String expected = reference(facts, node, key);
        String actual = facts.first(node, key);
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("first duplicate changed: node=" + node
                    + " key=" + key + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void refusal(SyntaxIndex.Facts facts) {
        try {
            facts.first(-1, "unknown");
            throw new AssertionError("negative node was accepted");
        } catch (IndexOutOfBoundsException expected) { }
        try {
            facts.first(0, null);
            throw new AssertionError("null name was accepted");
        } catch (NullPointerException expected) { }
        try {
            facts.first(100, null);
            throw new AssertionError("null name did not precede invalid node check");
        } catch (NullPointerException expected) { }
    }

    public static void main(String[] args) throws Exception {
        SyntaxTree.Builder builder = SyntaxTree.builder("first-lookup", new byte[] {'x'});
        builder.open("file", "", 0, SyntaxTree.NAMED);
        builder.close(1);
        SyntaxTree tree = builder.build();
        SyntaxIndex.FactBuilder writer = SyntaxIndex.facts(tree);

        final int size = 100_000;
        for (int i = 0; i < size; i++) {
            writer.add(0, "owner", "key" + (i % 41), "v" + i);
        }
        SyntaxIndex.Facts facts = writer.freeze();
        facts.requireSource(tree);
        if (facts.count(0) != size || facts.size() != size) {
            throw new AssertionError("sidecar facts count drift");
        }
        refusal(facts);
        long assertions = 0;
        for (int i = 0; i < 25_000; i++) {
            check(facts, 0, "owner:key" + (i % 41));
            if (i % 7 == 0) check(facts, 0, "missing:" + i);
            assertions++;
        }
        if (!"v0".equals(facts.first(0, "owner:key0"))
                || !"v40".equals(facts.first(0, "owner:key40"))) {
            throw new AssertionError("earliest duplicate value changed");
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int id = t;
            threads[t] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        check(facts, 0, "owner:key" + ((id + i * 3) % 41));
                    }
                } catch (Throwable x) { problem.compareAndSet(null, x); }
            });
        }
        start.countDown();
        for (Thread t : threads) t.join();
        if (problem.get() != null) throw new AssertionError("concurrent product first lookup", problem.get());
        ProbeGuardContract.main(args);
        System.out.println("SYNTAX_INDEX_FACTS_FIRST_PASS facts=" + size
                + " serialChecks=" + assertions + " parallelChecks=16000");
    }
}
