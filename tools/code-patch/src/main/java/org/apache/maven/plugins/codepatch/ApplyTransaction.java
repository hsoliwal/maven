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
import java.util.List;
import org.apache.maven.plugins.codepatch.FilePatch.Operation;
import org.apache.maven.plugins.codepatch.PlanRow.Slot;

/**
 * Promotes a plan that was just recomputed and approved: writes the receipt and backups first, then each file through
 * {@link FileWrites}, one at a time, in row order. If a promotion fails, the files promoted so far are restored from
 * the receipt at once; if that fails too, the receipt stays PENDING for {@code rollback}. A multi-file promotion is
 * journaled and serialized, not atomic as a whole.
 */
final class ApplyTransaction {
    private ApplyTransaction() { }

    static Receipt apply(Path base, Path outputDirectory, Plan plan) throws IOException, PatchException {
        Receipt receipt = new Receipt(outputDirectory, plan.id());
        replaceable(receipt, plan.id());
        receipt.create(base, plan);
        List<Slot> promoted = new ArrayList<>();
        try {
            promoteAll(base, plan, promoted);
        } catch (IOException | PatchException | RuntimeException e) {
            throw new PatchException("apply of plan " + plan.id() + " stopped: " + PatchException.describe(e) + "; "
                    + undo(base, receipt, promoted), e);
        }
        receipt.status(Receipt.Status.APPLIED);
        return receipt;
    }

    /**
     * An earlier receipt of the same plan may be replaced only when it is ROLLED_BACK, or when it never got as far as
     * RECEIPT.tsv (no source was touched then). Its directory is removed first, read-only backups included, so they
     * cannot block the new backups.
     */
    private static void replaceable(Receipt receipt, String id) throws IOException, PatchException {
        if (receipt.exists() && receipt.status() != Receipt.Status.ROLLED_BACK) {
            throw new PatchException("plan " + id + " already has a " + receipt.status() + " receipt in "
                    + receipt.directory() + "; roll it back first");
        }
        receipt.clear();
    }

    /** Promotes every slot in row order and records each one in {@code promoted} once it is in place. */
    private static void promoteAll(Path base, Plan plan, List<Slot> promoted) throws IOException, PatchException {
        for (PlanRow row : plan.rows()) {
            Path permissions = TargetPaths.resolve(base, row.operation() == Operation.RENAME ? row.fromPath() : row.path());
            for (Slot slot : row.slots()) {
                promote(base, plan, slot, permissions);
                promoted.add(slot);
            }
        }
    }

    private static void promote(Path base, Plan plan, Slot slot, Path permissions) throws IOException, PatchException {
        Path target = TargetPaths.resolve(base, slot.path());
        if (!Digests.ofFile(target).equals(slot.beforeSha256())) {
            throw new PatchException(slot.path() + ": changed while the plan was being applied");
        }
        if (Digests.ABSENT.equals(slot.afterSha256())) {
            FileWrites.delete(target);
        } else {
            FileWrites.write(target, plan.after().get(slot.path()), permissions);
        }
    }

    /** Restores the promoted files, newest first; says what happened, for the message. */
    private static String undo(Path base, Receipt receipt, List<Slot> promoted) {
        try {
            for (int i = promoted.size() - 1; i >= 0; i--) RollbackTransaction.restore(base, receipt, promoted.get(i));
            receipt.status(Receipt.Status.ROLLED_BACK);
            return "the " + promoted.size() + " files promoted so far were restored";
        } catch (IOException | PatchException | RuntimeException e) {
            return "restoring the promoted files failed (" + PatchException.describe(e) + "); the receipt in "
                    + receipt.directory() + " is PENDING: run rollback with -Dpatch.receipt=" + receipt.directory().getFileName();
        }
    }
}
