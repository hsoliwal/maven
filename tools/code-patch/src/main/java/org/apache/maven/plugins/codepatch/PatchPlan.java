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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Computes a {@link Plan}: reads the patch files, applies every section in memory against the base directory, and
 * hashes the patches and the resulting rows into the plan id. Nothing is written.
 */
public final class PatchPlan {
    private PatchPlan() { }

    /**
     * The plan of {@code patches} (files, or directories whose {@code *.patch} and {@code *.diff} files are taken in
     * name order) against {@code base}. Patches apply in the given order, each to the result of the ones before it.
     */
    public static Plan compute(Path base, List<Path> patches) throws IOException, PatchException {
        return compute(base, null, patches);
    }

    /** As {@link #compute(Path, List)}, refusing every target in {@code outputDirectory} (null: no such check). */
    public static Plan compute(Path base, Path outputDirectory, List<Path> patches) throws IOException, PatchException {
        WorkTree tree = new WorkTree(base.toAbsolutePath().normalize(), outputDirectory);
        MessageDigest id = Digests.sha256();
        for (Path file : patchFiles(patches)) {
            byte[] bytes = Files.readAllBytes(file);
            id.update((bytes.length + "\n").getBytes(StandardCharsets.UTF_8));
            id.update(bytes);
            String source = file.getFileName().toString();
            for (FilePatch section : PatchParser.parse(bytes, source)) tree.apply(section, source);
        }
        List<PlanRow> rows = tree.rows();
        if (rows.isEmpty()) throw new PatchException("the patches change no file");
        rows.forEach(r -> id.update((r.tsv() + "\n").getBytes(StandardCharsets.UTF_8)));
        return new Plan(Digests.hex(id), rows, tree.images(rows, false), tree.images(rows, true));
    }

    static List<Path> patchFiles(List<Path> patches) throws IOException, PatchException {
        List<Path> files = new ArrayList<>();
        for (Path given : patches) files.addAll(patchFiles(given));
        if (files.isEmpty()) throw new PatchException("no patch files (*.patch, *.diff) in " + patches);
        return files;
    }

    /** A patch file, or the patch files of a directory in name order. */
    private static List<Path> patchFiles(Path given) throws IOException, PatchException {
        if (Files.isRegularFile(given)) return List.of(given);
        if (!Files.isDirectory(given)) throw new PatchException(given + ": no such patch file or directory");
        try (Stream<Path> list = Files.list(given)) {
            return list.filter(f -> Files.isRegularFile(f) && isPatch(f))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString())).collect(Collectors.toList());
        }
    }

    private static boolean isPatch(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".patch") || name.endsWith(".diff");
    }
}
