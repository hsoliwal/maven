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

import static org.apache.maven.plugins.codepatch.Checks.check;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The spelling check lists each directory once per plan, and once per apply, not once per touched file: checked
 * through the FINE {@code list-directory} records of the package logger, for a plan of many files in one directory.
 * An apply that creates directories, then files in them, still finds them (a name missing from a listing taken
 * earlier is listed again before it is refused).
 */
final class ListingCacheTest {
    private static final int FILES = 120;
    private static final int CHANGED = 40;

    private ListingCacheTest() { }

    static void run(Path dir) throws Exception {
        Map<String, String> files = new TreeMap<>();
        IntStream.range(0, FILES).forEach(i -> files.put(name(i), "a\n"));
        Path base = Fixtures.tree(dir.resolve("tree"), files);
        Path out = base.resolve("target/patch");
        String patch = IntStream.range(0, CHANGED).mapToObj(i -> "--- a/" + name(i) + "\n+++ b/" + name(i)
                + "\n@@ -1 +1 @@\n-a\n+b\n").collect(Collectors.joining()) + PlanTargetsTest.add("n/a.txt")
                + PlanTargetsTest.add("n/m/b.txt") + PlanTargetsTest.add("n/m/c.txt");
        Path file = Fixtures.write(dir.resolve("many.diff"), patch);
        List<String> planned = DurabilityTest.recorded(() -> CodePatch.plan(base, out, List.of(file)));
        check("plan lists the directory of " + CHANGED + " changed files once: " + listings(planned),
                count(planned, base.resolve("many")) == 1);
        cycle(base, out, file, files);
    }

    /** Apply lists the directory again only to plan again and to promote; new directories are found; rollback. */
    private static void cycle(Path base, Path out, Path file, Map<String, String> files) throws Exception {
        String id = CodePatch.plan(base, out, List.of(file)).id();
        List<String> applied = DurabilityTest.recorded(() -> CodePatch.apply(base, out, List.of(file), id));
        long many = count(applied, base.resolve("many"));
        check("apply lists it at most twice (plan again, then promote): " + listings(applied), many >= 1 && many <= 2);
        check("apply created the new directories and files", Fixtures.snapshot(base).size() == FILES + 3);
        CodePatch.rollback(base, out, id);
        check("rollback restored the tree", Fixtures.snapshot(base).equals(Fixtures.digests(files)));
    }

    private static String name(int i) {
        return String.format(Locale.ROOT, "many/f%03d.txt", i);
    }

    private static long count(List<String> records, Path directory) {
        return records.stream().filter(("list-directory\t" + directory)::equals).count();
    }

    private static String listings(List<String> records) {
        return records.stream().filter(r -> r.startsWith("list-directory\t")).count() + " listings";
    }
}
