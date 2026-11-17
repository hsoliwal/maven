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

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Options;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * The plugin keeps javac's string folding, so javac compiles what it compiles without the plugin (JavacOutputParityTest
 * and ForkedJavacTest compare result, diagnostics and class files for the stress fixtures). A folded chain the bridge
 * cannot give back is a print mismatch: the file gets no tree, and the compile is not touched.
 */
class StringFoldingTest {
    @Test
    void thePluginChangesNoCompilerOption() throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(null, files, null, List.of(), null, List.of());
            new MavenRecipesPlugin().init(task);
            assertNull(Options.instance(((BasicJavacTask) task).getContext()).get("allowStringFolding"));
        }
    }

    @TestFactory
    Stream<DynamicTest> aChainLongerThanTheBoundIsAPrintMismatchWithoutATree() {
        return Stream.concat(FoldingFixtures.stress().stream(), Stream.of(FoldingFixtures.surrogates()))
                .map(fixture -> dynamicTest(fixture.name(), () -> assertRefused(fixture)));
    }

    private static void assertRefused(Fixture fixture) throws IOException {
        Javac.Compilation compilation =
                Javac.compile("folding", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        assertTrue(compilation.success(), compilation::output);
        List<FileLst> files = compilation.collector().orElseThrow().files();
        assertEquals(1, files.size());
        CensusRow row = files.get(0).row();
        assertTrue(row.status().startsWith(LstStatus.PRINT_MISMATCH.label() + ": "), row::tsv);
        assertTrue(row.status().contains("folded"), row::tsv);
        assertFalse(row.printEqual(), row::tsv);
        assertTrue(files.get(0).lst().isEmpty(), row::tsv);
    }
}
