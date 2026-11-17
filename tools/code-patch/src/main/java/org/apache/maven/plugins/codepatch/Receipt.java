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
import java.util.ArrayList;
import java.util.List;

/**
 * The transaction record of one applied plan, in {@code receipts/<plan-id>/}: {@code backup/<path>} holds the content
 * every promoted file had before (copied with its attributes), {@code RECEIPT.tsv} the plan rows, and {@code STATUS}
 * the state. Apply writes the backups (each forced to disk), then RECEIPT.tsv with STATUS PENDING, and only then touches
 * a source file, so an interrupted apply always leaves a receipt that rollback can use. Rollback writes ROLLING_BACK
 * before it restores the first file, so an interrupted rollback can be resumed.
 */
final class Receipt {
    /**
     * PENDING: promotion started and did not finish; APPLIED: every file promoted; ROLLING_BACK: a rollback started
     * and did not finish; ROLLED_BACK: restored.
     */
    enum Status { PENDING, APPLIED, ROLLING_BACK, ROLLED_BACK }

    private final Path dir;

    Receipt(Path outputDirectory, String id) {
        this.dir = outputDirectory.resolve("receipts").resolve(id);
    }

    Path directory() {
        return dir;
    }

    boolean exists() {
        return Files.isRegularFile(dir.resolve("RECEIPT.tsv"));
    }

    /** Removes the receipt directory (backups, read-only ones included, and records), if there is one. */
    void clear() throws IOException {
        Trees.delete(dir);
    }

    /** Backs up every file the plan reads, checks each backup against the plan, then records the rows as PENDING. */
    void create(Path base, Plan plan) throws IOException, PatchException {
        for (PlanRow row : plan.rows()) {
            for (PlanRow.Slot slot : row.slots()) {
                if (Digests.ABSENT.equals(slot.beforeSha256())) continue;
                FileWrites.copy(TargetPaths.resolve(base, slot.path()), backup(slot.path()));
                backupOf(slot);
            }
        }
        FileWrites.write(dir.resolve("RECEIPT.tsv"), plan.tsv().getBytes(StandardCharsets.UTF_8), null);
        status(Status.PENDING);
    }

    List<PlanRow> rows() throws IOException, PatchException {
        List<String> lines = Files.readAllLines(dir.resolve("RECEIPT.tsv"), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(PlanRow.HEADER)) throw new PatchException(dir + ": not a receipt");
        List<PlanRow> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) rows.add(PlanRow.parse(line));
        return rows;
    }

    /** The recorded state; a receipt without STATUS counts as PENDING. */
    Status status() throws IOException, PatchException {
        Path file = dir.resolve("STATUS");
        if (!Files.isRegularFile(file)) return Status.PENDING;
        String value = Files.readString(file, StandardCharsets.UTF_8).strip();
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new PatchException(file + ": unknown status " + value);
        }
    }

    void status(Status status) throws IOException {
        FileWrites.write(dir.resolve("STATUS"), (status.name() + "\n").getBytes(StandardCharsets.UTF_8), null);
    }

    /** The backup of a slot's file, refused when it no longer has the slot's before content. */
    Path backupOf(PlanRow.Slot slot) throws IOException, PatchException {
        Path backup = backup(slot.path());
        if (!Digests.ofFile(backup).equals(slot.beforeSha256())) {
            throw new PatchException(backup + ": the backup of " + slot.path() + " does not have its recorded content");
        }
        return backup;
    }

    private Path backup(String path) {
        return dir.resolve("backup").resolve(path);
    }
}
