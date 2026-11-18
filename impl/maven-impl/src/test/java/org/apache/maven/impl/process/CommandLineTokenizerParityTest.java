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
package org.apache.maven.impl.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.impl.model.DefaultOsService;
import org.apache.maven.shared.utils.cli.CommandLineUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.apache.maven.impl.process.ProcessLauncherTesting.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity of {@link ProcessLauncher#tokenize(String)} with {@code CommandLineUtils.translateCommandline} of
 * maven-shared-utils 3.5.0 (which surefire's {@code Commandline.translateCommandline} copies) and plexus-utils
 * 4.2.0, and with surefire's {@code argLine} handling. Writes {@code target/process-launcher-parity/TOKENIZE_PARITY.tsv}.
 */
class CommandLineTokenizerParityTest {

    private static final ProcessLauncher LAUNCHER = new DefaultProcessLauncher(new DefaultOsService());
    private static final List<String> ROWS = new ArrayList<>(List.of("reference\tcase\tequal\tours\ttheirs"));

    /** A corpus entry. */
    record Case(String name, String line) {}

    static final List<Case> CORPUS = List.of(
            new Case("null", null),
            new Case("empty", ""),
            new Case("blank", "   "),
            new Case("words", "a b c"),
            new Case("outer-spaces", "  lead  trail  "),
            new Case("single-quoted", "'single quoted' x"),
            new Case("double-quoted", "\"double quoted\" y"),
            new Case("mid-word-quotes", "mix'ed q'uote"),
            new Case("escaped-double", "esc\\\"aped"),
            new Case("escaped-single", "\\'escaped single"),
            new Case("escaped-space", "a\\ b"),
            new Case("double-backslash", "back\\\\slash"),
            new Case("tab", "tab\tsep"),
            new Case("newline", "new\nline"),
            new Case("property-with-space", "-Dx=\"a b\" -Xmx1g"),
            new Case("adjacent-single", "'it''s'"),
            new Case("empty-quotes", "\"\" ''"),
            new Case("nested-quotes", "a'b\"c'd"),
            new Case("javaagent", "-javaagent:\"C:\\path with space\\agent.jar\"=destfile=x -Xmx512m"),
            new Case("unbalanced-single", "unbalanced'"),
            new Case("unbalanced-double", "unbalanced\" x"));

    @AfterAll
    static void writeTable() {
        ProcessLauncherTesting.writeTable("TOKENIZE_PARITY.tsv", ROWS);
    }

    @Test
    void matchesSharedUtilsTranslateCommandline() {
        assertEquals(Set.of(), differing("maven-shared-utils", line -> CommandLineUtils.translateCommandline(line)));
    }

    /** plexus-utils also splits on tab, newline and carriage return, and gives a backslash no meaning. */
    @Test
    void differsFromPlexusUtilsOnlyOnWhitespaceAndBackslash() {
        Set<String> differing = differing(
                "plexus-utils", line -> org.codehaus.plexus.util.cli.CommandLineUtils.translateCommandline(line));
        assertEquals(Set.of("tab", "newline", "escaped-double", "escaped-single"), differing);
    }

    /** Surefire turns every whitespace character of {@code argLine} into a space, then tokenizes. */
    @Test
    void matchesSurefireArgLineHandling() throws Exception {
        String argLine = "-Xmx1g\n\t-Dfoo=\"bar baz\"\r\n-javaagent:agent.jar=a=b";
        String stripped = argLine.replaceAll("\\s", " ");
        assertEquals(Arrays.asList(CommandLineUtils.translateCommandline(stripped)), LAUNCHER.tokenize(stripped));
        assertEquals(List.of("-Xmx1g", "-Dfoo=bar baz", "-javaagent:agent.jar=a=b"), LAUNCHER.tokenize(stripped));
    }

    /** One reference tokenizer call. */
    @FunctionalInterface
    interface Reference {
        String[] translate(String line) throws Exception;
    }

    private static Set<String> differing(String reference, Reference theirs) {
        return CORPUS.stream()
                .filter(c -> !compare(reference, c, theirs))
                .map(Case::name)
                .collect(Collectors.toSet());
    }

    private static boolean compare(String reference, Case c, Reference theirs) {
        String ours = outcome(() -> LAUNCHER.tokenize(c.line()));
        String expected = outcome(() -> Arrays.asList(theirs.translate(c.line())));
        boolean equal = ours.equals(expected);
        ROWS.add(String.join("\t", reference, c.name(), String.valueOf(equal), cell(ours), cell(expected)));
        return equal;
    }

    private static String outcome(Callable<List<String>> tokenizer) {
        try {
            return tokenizer.call().toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
