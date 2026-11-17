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
package org.apache.maven.recipes.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FOSS_REUSE_DECISION.tsv names every dependency the POM declares and the licences of what they bring. */
class FossReuseDecisionTest {
    private static final Pattern DEPENDENCY =
            Pattern.compile("<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    private static final List<String> TRANSITIVE_LICENSES = List.of("EPL", "LGPL", "MIT", "BSD", "CC0");
    private static final int COLUMNS = 8;

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    @Test
    void namesEveryDeclaredDependency() throws IOException {
        String decisions = read("FOSS_REUSE_DECISION.tsv");
        Matcher dependency = DEPENDENCY.matcher(read("pom.xml"));
        List<String> missing = dependency
                .results()
                .map(match -> match.group(2).trim())
                .filter(artifactId -> !decisions.contains(artifactId))
                .toList();
        assertEquals(List.of(), missing);
    }

    @Test
    void everyRowHasEightColumnsAndTheTransitiveRowNamesItsLicences() throws IOException {
        List<String> rows = read("FOSS_REUSE_DECISION.tsv").lines().toList();
        rows.forEach(row -> assertEquals(COLUMNS, row.split("\t", -1).length, row));
        String transitive = rows.stream()
                .filter(row -> row.startsWith("transitive dependencies"))
                .findFirst()
                .orElse("");
        assertTrue(transitive.contains("not bundled"), transitive);
        TRANSITIVE_LICENSES.forEach(license -> assertTrue(transitive.contains(license), license));
    }

    @Test
    void theExcludedLombokProcessorIsRecorded() throws IOException {
        assertTrue(read("pom.xml").contains("<artifactId>rewrite-java-lombok</artifactId>"));
        assertTrue(read("FOSS_REUSE_DECISION.tsv").contains("excluded from rewrite-java-21"));
    }
}
