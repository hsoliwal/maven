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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.plugins.codepatch.FilePatch.Operation;

/**
 * The base directory with the patches applied in memory, section after section, so a series of patches can change
 * the same file more than once. It reads each touched file once and never writes. Every touched path is resolved
 * by {@link TargetPaths}, checked by {@link WritableTargets}, and on a case-insensitive volume must not collide
 * ignoring case with another touched path or its directories ({@link CaseKeys}). {@link #rows()} compares the original
 * and final content of every touched path.
 */
final class WorkTree {
    private final Path base;
    private final Path outputDirectory;
    private final CaseKeys caseKeys;
    private final Map<String, Optional<byte[]>> original = new TreeMap<>();
    private final Map<String, Optional<byte[]>> current = new TreeMap<>();
    private final Map<String, String> renamedFrom = new HashMap<>();
    private final Map<String, Integer> hunks = new HashMap<>();

    /** {@code outputDirectory} (may be null) is refused as a target location, with everything under it. */
    WorkTree(Path base, Path outputDirectory) {
        this.base = base;
        this.outputDirectory = outputDirectory;
        this.caseKeys = new CaseKeys(base);
    }

    void apply(FilePatch section, String source) throws PatchException, IOException {
        Operation operation = section.operation();
        String from = operation == Operation.ADD ? null : section.oldPath();
        String to = operation == Operation.DELETE ? from : section.newPath();
        byte[] before = from == null ? new byte[0] : existing(from, source);
        if (operation == Operation.ADD || operation == Operation.RENAME) absent(to, source);
        byte[] after = HunkApplier.apply(source + ": " + section.path(), before, section.hunks());
        if (operation == Operation.DELETE && after.length > 0) {
            throw new PatchException(source + ": " + from + ": the patch deletes the file, but " + after.length
                    + " bytes would remain");
        }
        record(operation, from, to, after, section.hunks().size());
    }

    /** Moves the content from {@code from} (null for ADD) to {@code to}, with the hunk count and rename origin. */
    private void record(Operation operation, String from, String to, byte[] after, int sectionHunks) {
        int count = sectionHunks + (from == null ? 0 : hunks.getOrDefault(from, 0));
        if (from != null) {
            current.put(from, Optional.empty());
            hunks.remove(from);
        }
        hunks.put(to, count);
        if (operation != Operation.DELETE) current.put(to, Optional.of(after));
        if (operation == Operation.RENAME) {
            String origin = renamedFrom.remove(from);
            renamedFrom.put(to, origin == null ? from : origin);
        }
    }

    /** One row per path whose content changes, sorted by path; a rename whose ends both survive is one RENAME row. */
    List<PlanRow> rows() {
        List<PlanRow> rows = new ArrayList<>();
        Set<String> done = new HashSet<>();
        renameRows(rows, done);
        plainRows(rows, done);
        rows.sort(Comparator.comparing(PlanRow::path));
        return rows;
    }

    /** RENAME rows: the new path exists only now and the old path only before; both paths are then done. */
    private void renameRows(List<PlanRow> rows, Set<String> done) {
        renamedFrom.forEach((path, from) -> {
            if (current.get(path).isPresent() && original.get(path).isEmpty() && original.get(from).isPresent()
                    && current.get(from).isEmpty()) {
                rows.add(new PlanRow(path, Operation.RENAME, hunks.get(path), sha(original.get(from)),
                        sha(current.get(path)), from));
                done.add(path);
                done.add(from);
            }
        });
    }

    /** ADD, DELETE and MODIFY rows for every other path whose content differs between before and now. */
    private void plainRows(List<PlanRow> rows, Set<String> done) {
        current.forEach((path, now) -> {
            Optional<byte[]> was = original.get(path);
            if (done.contains(path) || was.isEmpty() && now.isEmpty()) return;
            Operation operation = was.isEmpty() ? Operation.ADD : now.isEmpty() ? Operation.DELETE : Operation.MODIFY;
            rows.add(new PlanRow(path, operation, hunks.getOrDefault(path, 0), sha(was), sha(now), Digests.ABSENT));
        });
    }

    /** The before-images ({@code after} false) or after-images of the rows' files, by path. */
    Map<String, byte[]> images(List<PlanRow> rows, boolean after) {
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (PlanRow row : rows) {
            for (PlanRow.Slot slot : row.slots()) {
                String sha = after ? slot.afterSha256() : slot.beforeSha256();
                if (!Digests.ABSENT.equals(sha)) images.put(slot.path(), (after ? current : original).get(slot.path()).get());
            }
        }
        return images;
    }

    private byte[] existing(String path, String source) throws PatchException, IOException {
        Optional<byte[]> content = load(path);
        if (content.isEmpty()) throw new PatchException(source + ": " + path + ": no such file");
        return content.get();
    }

    private void absent(String path, String source) throws PatchException, IOException {
        if (load(path).isPresent()) throw new PatchException(source + ": " + path + ": already exists");
    }

    private Optional<byte[]> load(String path) throws PatchException, IOException {
        if (!current.containsKey(path)) {
            Path file = TargetPaths.resolve(base, path);
            if (Files.isDirectory(file)) throw new PatchException(PathNames.show(path) + ": is a directory");
            WritableTargets.check(base, file, path, outputDirectory);
            caseKeys.add(path);
            Optional<byte[]> disk = Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
            original.put(path, disk);
            current.put(path, disk);
        }
        return current.get(path);
    }

    private static String sha(Optional<byte[]> content) {
        return content.map(Digests::sha256).orElse(Digests.ABSENT);
    }
}
