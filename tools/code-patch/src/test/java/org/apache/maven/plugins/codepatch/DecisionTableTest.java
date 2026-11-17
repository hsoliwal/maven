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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * FOSS_REUSE_DECISION.tsv: every row has the eight columns of its header, and the table records the search of the
 * OpenClaw donor surface and the evaluation of {@code git apply} and GNU patch as command-line appliers, each with
 * its probe result.
 */
final class DecisionTableTest {
    private static final int COLUMNS = 8;

    private DecisionTableTest() { }

    static void run(Path module) throws IOException {
        List<String[]> rows = Files.readAllLines(module.resolve("FOSS_REUSE_DECISION.tsv"), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank()).skip(1).map(l -> l.split("\t", -1))
                .collect(Collectors.toList());
        check("every decision row has " + COLUMNS + " columns", rows.stream().allMatch(r -> r.length == COLUMNS));
        check("OpenClaw donor surface search recorded", any(rows, r -> r[2].contains("github.com/orgs/openclaw/repositories")
                && r[6].contains("searched")));
        check("git apply evaluated as a CLI with a probe", any(rows, r -> r[1].contains("git apply") && r[6].contains("probe")));
        check("GNU patch evaluated as a CLI with a probe", any(rows, r -> r[1].contains("GNU patch") && r[6].contains("probe")));
    }

    private static boolean any(List<String[]> rows, Predicate<String[]> test) {
        return rows.stream().anyMatch(r -> r.length == COLUMNS && test.test(r));
    }
}
