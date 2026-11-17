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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes;
import org.apache.maven.plugins.clustering.PlaneCompare;

/**
 * The five steps, each on a base directory, an output directory and the patch files. {@code plan}, {@code preview}
 * and {@code verify} never touch the base directory. {@code apply} and {@code rollback} hold {@link PatchLock}.
 * Output layout: {@code PLAN.tsv}, {@code PLAN_ID}, {@code plans/<id>/PLAN.tsv}, {@code stage/<id>/},
 * {@code verify/<id>/before/}, {@code verify/<id>/COMPARE.tsv}, {@code receipts/<id>/}.
 */
public final class CodePatch {
    private CodePatch() { }

    /**
     * The outcome of {@code verify}: the plan, where COMPARE.tsv is, the comparison of the atoms, the NO_ATOMS rows of
     * the changed files that give no atoms, and whether those rows fail.
     */
    public record Verification(Plan plan, Path report, PlaneCompare.Result result, List<String> noAtoms,
            boolean noAtomsFail) {
        public Verification {
            noAtoms = List.copyOf(noAtoms);
        }

        /** The rows in a failing state: atoms in a failOn state and, with NO_ATOMS in failOn, files without atoms. */
        public List<String> failing() {
            List<String> failing = new ArrayList<>(result.failing());
            if (noAtomsFail) failing.addAll(noAtoms);
            return failing;
        }

        public String summary() {
            return result.summary() + (noAtoms.isEmpty() ? "" : " " + FailOn.NO_ATOMS + " " + noAtoms.size());
        }
    }

    /** Computes the plan and writes PLAN.tsv, PLAN_ID and a copy of PLAN.tsv under {@code plans/<id>/}. */
    public static Plan plan(Path base, Path out, List<Path> patches) throws IOException, PatchException {
        Plan plan = PatchPlan.compute(root(base), out, patches);
        byte[] tsv = plan.tsv().getBytes(StandardCharsets.UTF_8);
        FileWrites.write(out.resolve("PLAN.tsv"), tsv, null);
        FileWrites.write(out.resolve("plans").resolve(plan.id()).resolve("PLAN.tsv"), tsv, null);
        FileWrites.write(out.resolve("PLAN_ID"), (plan.id() + "\n").getBytes(StandardCharsets.UTF_8), null);
        return plan;
    }

    /** Plans, then writes the after-images to {@code stage/<id>/}; returns that directory. */
    public static Path preview(Path base, Path out, List<Path> patches) throws IOException, PatchException {
        Plan plan = plan(base, out, patches);
        return Staging.write(out.resolve("stage").resolve(plan.id()), plan.after());
    }

    /** As {@link #verify(Path, Path, List, FailOn)} with the given atom states and NO_ATOMS rows that only report. */
    public static Verification verify(Path base, Path out, List<Path> patches, Set<PlaneCompare.State> failOn)
            throws IOException, PatchException {
        return verify(base, out, patches, new FailOn(failOn, false));
    }

    /**
     * Plans, stages the after-images and the current content of the same files, atomizes both and compares them:
     * COMPARE.tsv in {@code verify/<id>/}, followed by a NO_ATOMS row for every changed file that gives no atoms. The
     * caller fails on {@code failing()}.
     */
    public static Verification verify(Path base, Path out, List<Path> patches, FailOn failOn)
            throws IOException, PatchException {
        Plan plan = plan(base, out, patches);
        Path after = Staging.write(out.resolve("stage").resolve(plan.id()), plan.after());
        Path dir = out.resolve("verify").resolve(plan.id());
        Path before = Staging.write(dir.resolve("before"), plan.before());
        List<AtomPlanes.Atom> was = AtomPlanes.scan(List.of(before));
        List<AtomPlanes.Atom> now = AtomPlanes.scan(List.of(after));
        PlaneCompare.Result result = PlaneCompare.compare(was, now, Set.of(), failOn.states(), dir);
        List<String> noAtoms = NoAtomRows.rows(plan, was, now);
        Path report = dir.resolve("COMPARE.tsv");
        Files.writeString(report, noAtoms.stream().map(r -> r + "\n").collect(Collectors.joining()),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return new Verification(plan, report, result, noAtoms, failOn.noAtoms());
    }

    /**
     * Recomputes the plan under the lock and applies it only if its id is the approved one; otherwise refuses and
     * names the files whose rows differ from the approved plan's (when {@code plans/<approved>/PLAN.tsv} exists).
     */
    public static Plan apply(Path base, Path out, List<Path> patches, String approved) throws IOException, PatchException {
        String id = Digests.id(approved, "patch.approvedPlan");
        PatchLock lock = PatchLock.acquire(out);
        try {
            Plan plan = PatchPlan.compute(root(base), out, patches);
            if (!plan.id().equals(id)) throw stale(out, id, plan);
            ApplyTransaction.apply(root(base), out, plan);
            return plan;
        } finally {
            lock.close();
        }
    }

    /** Restores the files of {@code receipts/<id>/} under the lock; returns the receipt's rows. */
    public static List<PlanRow> rollback(Path base, Path out, String receipt) throws IOException, PatchException {
        String id = Digests.id(receipt, "patch.receipt");
        PatchLock lock = PatchLock.acquire(out);
        try {
            return RollbackTransaction.rollback(root(base), out, id);
        } finally {
            lock.close();
        }
    }

    /** The directory of a receipt. */
    public static Path receipt(Path out, String id) {
        return new Receipt(out, id).directory();
    }

    private static PatchException stale(Path out, String approved, Plan plan) throws IOException {
        String message = "stale approval: approved plan " + approved + ", but the patches and the files now give plan "
                + plan.id();
        Path approvedTsv = out.resolve("plans").resolve(approved).resolve("PLAN.tsv");
        if (!Files.isRegularFile(approvedTsv)) return new PatchException(message + "; refused");
        Set<String> was = new HashSet<>(Files.readAllLines(approvedTsv, StandardCharsets.UTF_8));
        Set<String> now = new HashSet<>(plan.tsv().lines().toList());
        Set<String> changed = new TreeSet<>();
        was.stream().filter(r -> !now.contains(r)).forEach(r -> changed.add(r.split("\t", 2)[0]));
        now.stream().filter(r -> !was.contains(r)).forEach(r -> changed.add(r.split("\t", 2)[0]));
        return new PatchException(message + "; changed since approval: "
                + (changed.isEmpty() ? "the patch files" : String.join(", ", changed)) + "; refused");
    }

    private static Path root(Path base) {
        return base.toAbsolutePath().normalize();
    }
}
