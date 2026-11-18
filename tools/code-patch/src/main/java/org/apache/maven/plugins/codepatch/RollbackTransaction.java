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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.maven.plugins.codepatch.PlanRow.Slot;

/**
 * Restores the files of a receipt from its backups, in reverse promotion order. Before it touches anything it checks
 * every file: after a complete apply (APPLIED) each must still have its after content (a deleted file must still be
 * absent); after an interrupted apply (PENDING) or an interrupted rollback (ROLLING_BACK) a file may also have its
 * before content already. Any other content refuses the whole rollback and names the files. Each backup is checked
 * against its recorded hash. STATUS becomes ROLLING_BACK before the first file is restored and ROLLED_BACK after the
 * last, so a rollback that stops halfway can simply be run again.
 */
final class RollbackTransaction {
    private static final int SHORT = 12;

    private RollbackTransaction() { }

    static List<PlanRow> rollback(Path base, Path outputDirectory, String id) throws IOException, PatchException {
        Receipt receipt = new Receipt(outputDirectory, id);
        if (!receipt.exists()) throw new PatchException("no receipt for plan " + id + " in " + receipt.directory());
        Receipt.Status status = receipt.status();
        if (status == Receipt.Status.ROLLED_BACK) throw new PatchException("plan " + id + " is already rolled back");
        List<PlanRow> rows = receipt.rows();
        List<Slot> slots = new ArrayList<>();
        rows.forEach(r -> slots.addAll(r.slots()));
        Collections.reverse(slots);
        check(base, receipt, slots, status != Receipt.Status.APPLIED);
        receipt.status(Receipt.Status.ROLLING_BACK);
        for (Slot slot : slots) restore(base, receipt, slot);
        receipt.status(Receipt.Status.ROLLED_BACK);
        return rows;
    }

    private static void check(Path base, Receipt receipt, List<Slot> slots, boolean interrupted)
            throws IOException, PatchException {
        List<String> changed = new ArrayList<>();
        for (Slot slot : slots) {
            String actual = Digests.ofFile(TargetPaths.resolve(base, slot.path()));
            if (!actual.equals(slot.afterSha256()) && !(interrupted && actual.equals(slot.beforeSha256()))) {
                changed.add(slot.path() + " (has " + brief(actual) + ", applied " + brief(slot.afterSha256()) + ")");
            }
            if (!Digests.ABSENT.equals(slot.beforeSha256())) receipt.backupOf(slot);
        }
        if (!changed.isEmpty()) {
            throw new PatchException("rollback refused: changed since apply: " + String.join(", ", changed));
        }
    }

    /** Puts one file back to its before content: the backup copied over it, or the file removed if it had none. */
    static void restore(Path base, Receipt receipt, Slot slot) throws IOException, PatchException {
        Path target = TargetPaths.resolve(base, slot.path());
        if (Digests.ABSENT.equals(slot.beforeSha256())) {
            FileWrites.delete(target);
        } else if (!Digests.ofFile(target).equals(slot.beforeSha256())) {
            FileWrites.copy(receipt.backupOf(slot), target);
        }
    }

    private static String brief(String sha) {
        return sha.length() > SHORT ? sha.substring(0, SHORT) : sha.equals(Digests.ABSENT) ? "no file" : sha;
    }
}
