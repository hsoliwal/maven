/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Facts computed at ingest from more than one source: the build status, what changed since the last green build
 * (sources, code atoms, test outcomes, polyglot task digests) and which tests are flaky (a Surefire rerun that passed
 * after failing, or an outcome that flipped between builds while the module's code atoms stayed identical).
 */
final class Derived {
    private Derived() {}

    /** Red when Maven reported BUILD FAILURE or any test failed or errored; green otherwise. */
    static String status(List<Chunk> chunks) {
        boolean red = chunks.stream().anyMatch(c -> "build-failure".equals(c.facet("signal")) || "test".equals(c.kind())
                && ("failed".equals(c.facet("status")) || "error".equals(c.facet("status"))));
        return red ? "red" : "green";
    }

    static List<Chunk> of(ProjectDb db, Map<String, Catalog.Entry> entries, String id, List<Catalog.Build> builds)
            throws IOException {
        List<Chunk> derived = new ArrayList<>();
        Catalog.Build green = null;
        for (Catalog.Build build : builds) if ("green".equals(build.status())) green = build;
        if (green != null) derived.addAll(changes(db, db.catalog(green.id()), entries, green.id(), id));
        derived.addAll(flaky(db, entries, id, builds));
        return derived;
    }

    private static List<Chunk> changes(ProjectDb db, Map<String, Catalog.Entry> before,
            Map<String, Catalog.Entry> after, String green, String id) throws IOException {
        List<Chunk> changes = new ArrayList<>();
        for (String key : keys(before, after)) {
            Catalog.Entry old = before.get(key);
            Catalog.Entry now = after.get(key);
            if (old != null && now != null && old.sha().equals(now.sha())) continue;
            List<Chunk> details = old != null && now != null ? detail(db.chunks(old), db.chunks(now), green, id)
                    : List.of();
            if (details.isEmpty()) changes.add(sourceChange(old, now, green, id));
            else changes.addAll(details);
        }
        return changes;
    }

    /** Sources of either build; every build has its own console log, an answer to "why" but not a change. */
    private static TreeSet<String> keys(Map<String, Catalog.Entry> before, Map<String, Catalog.Entry> after) {
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> "build-log".equals((after.containsKey(key) ? after : before).get(key).kind()));
        return keys;
    }

    private static Chunk sourceChange(Catalog.Entry old, Catalog.Entry now, String green, String id) {
        Catalog.Entry entry = now != null ? now : old;
        String what = old == null ? "is new" : now == null ? "is gone" : "changed";
        return new Chunk("change", entry.module(), entry.path(), 1, entry.kind() + " data " + entry.path() + " of module "
                + entry.module() + " " + what + " since green build " + green, Map.of("since", green,
                "source", entry.kind()), id);
    }

    /** Atom, test and task level differences between two versions of one source. */
    private static List<Chunk> detail(List<Chunk> before, List<Chunk> after, String green, String id) {
        List<Chunk> changes = new ArrayList<>();
        for (String[] facet : new String[][] {{"atom", "exact", "code"}, {"test", "status", "test outcome"},
            {"task", "input", "polyglot task"}}) {
            Map<String, Chunk> old = index(before, facet[0]);
            for (Map.Entry<String, Chunk> now : index(after, facet[0]).entrySet()) {
                Chunk previous = old.get(now.getKey());
                if (previous != null && previous.facet(facet[1]).equals(now.getValue().facet(facet[1]))) continue;
                changes.add(detailChange(now.getValue(), previous, facet, green, id));
            }
        }
        return changes;
    }

    private static Chunk detailChange(Chunk now, Chunk previous, String[] facet, String green, String id) {
        String what = previous == null ? "is new" : "test".equals(facet[0])
                ? "changed from " + previous.facet("status") + " to " + now.facet("status") : "changed";
        String text = facet[2] + " " + now.facet(facet[0]) + " in " + now.file() + " line " + now.line() + " " + what
                + " since green build " + green;
        return new Chunk("change", now.module(), now.file(), now.line(), text, Map.of("since", green,
                "detail", facet[2], facet[0], now.facet(facet[0])), id);
    }

    private static Map<String, Chunk> index(List<Chunk> chunks, String facet) {
        Map<String, Chunk> index = new TreeMap<>();
        for (Chunk chunk : chunks) if (!chunk.facet(facet).isEmpty()) index.putIfAbsent(chunk.facet(facet), chunk);
        return index;
    }

    private static List<Chunk> flaky(ProjectDb db, Map<String, Catalog.Entry> entries, String id,
            List<Catalog.Build> builds) throws IOException {
        List<Chunk> flaky = new ArrayList<>();
        Outcomes current = Outcomes.of(db, entries);
        for (Chunk test : current.tests().values()) {
            if ("flaky".equals(test.facet("status"))) flaky.add(rerun(test, id));
        }
        Outcomes later = current;
        for (int i = builds.size() - 1; i >= 0; i--) {
            Outcomes earlier = Outcomes.of(db, db.catalog(builds.get(i).id()));
            flaky.addAll(later.flips(earlier, builds.get(i).id(), id));
            later = earlier;
        }
        return flaky;
    }

    private static Chunk rerun(Chunk test, String id) {
        return new Chunk("flaky", test.module(), test.file(), test.line(), "flaky test " + test.facet("test")
                + " failed and then passed when Surefire reran it in build " + id + ": " + test.text(),
                Map.of("test", test.facet("test"), "evidence", "rerun"), id);
    }

    /** Test outcomes of one build and each module's code fingerprint (its clustering atoms source hash). */
    private record Outcomes(Map<String, Chunk> tests, Map<String, String> code) {
        static Outcomes of(ProjectDb db, Map<String, Catalog.Entry> entries) throws IOException {
            Map<String, Chunk> tests = new TreeMap<>();
            Map<String, String> code = new TreeMap<>();
            for (Catalog.Entry entry : entries.values()) {
                if ("clustering".equals(entry.kind())) code.put(entry.module(), entry.sha());
                if (!"surefire".equals(entry.kind())) continue;
                for (Chunk chunk : db.chunks(entry)) tests.put(chunk.facet("test"), chunk);
            }
            return new Outcomes(tests, code);
        }

        /** Pass/fail flips against an earlier build of the same module code. */
        List<Chunk> flips(Outcomes earlier, String then, String id) {
            List<Chunk> flips = new ArrayList<>();
            for (Map.Entry<String, Chunk> test : tests.entrySet()) {
                Chunk before = earlier.tests().get(test.getKey());
                String code = this.code.get(test.getValue().module());
                if (before == null || code == null || !code.equals(earlier.code().get(before.module()))) continue;
                if (passed(before) != passed(test.getValue())) flips.add(flip(test.getValue(), before, then, id));
            }
            return flips;
        }

        private static boolean passed(Chunk test) {
            return !"failed".equals(test.facet("status")) && !"error".equals(test.facet("status"));
        }

        private static Chunk flip(Chunk now, Chunk before, String then, String id) {
            return new Chunk("flaky", now.module(), now.file(), now.line(), "flaky test " + now.facet("test") + " was "
                    + before.facet("status") + " in build " + then + " and " + now.facet("status") + " later with "
                    + "identical module code", Map.of("test", now.facet("test"), "evidence", "flip", "since", then), id);
        }
    }
}
