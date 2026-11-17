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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.tree.J;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** The bridge on javac's own trees: exact print-back, one parse per file, every call attributed. */
class CompilerTreeLstTest {
    static final String BYFILE = "-XDcompilePolicy=byfile";

    @TestFactory
    Stream<DynamicTest> everyFilePrintsItsExactBytesAfterOneParseWithEveryCallAttributed() {
        return Fixtures.compiling().stream()
                .map(fixture -> dynamicTest(fixture.name(), () -> assertConverted(fixture)));
    }

    @Test
    void packageInfoIsBuiltAtItsOwnAnalyzeEventAndAFileWithoutTypesAtTheEnd() throws IOException {
        Fixture fixture = Fixtures.compiling().stream()
                .filter(f -> f.name().equals("package-info"))
                .findFirst()
                .orElseThrow();
        Map<String, CensusRow> rows = rows(Javac.compile("bridge", fixture, Javac.Mode.DIRECT, BYFILE));
        assertEquals("ANALYZE", rows.get("p/package-info.java").builtAt());
        assertEquals("ANALYZE", rows.get("q/package-info.java").builtAt());
        assertEquals("ANALYZE", rows.get("q/B.java").builtAt());
        assertEquals("COMPILATION", rows.get("q/Empty.java").builtAt());
        assertEquals(LstStatus.OK.label(), rows.get("q/Empty.java").status());
    }

    @Test
    void aKnownRewrite8890DefectIsRefusedAsAPrintMismatchAndNoTreeIsHandedOut() throws IOException {
        Javac.Compilation compilation =
                Javac.compile("bridge", Fixtures.rewrite8890CommentDefect(), Javac.Mode.DIRECT, BYFILE);
        assertTrue(compilation.success(), compilation.diagnostics()::toString);
        List<FileLst> files = compilation.collector().orElseThrow().files();
        assertEquals(2, files.size());
        files.forEach(file ->
                assertEquals(LstStatus.PRINT_MISMATCH.label(), file.row().status()));
        files.forEach(file -> assertTrue(file.lst().isEmpty()));
        files.forEach(file -> assertEquals(1, file.row().parseEvents()));
    }

    @Test
    void moduleInfoIsCompiledButHasNoTreeLikeTheClassicParserWhichExcludesIt() throws IOException {
        Javac.Compilation compilation = Javac.compile("bridge", Fixtures.module(), Javac.Mode.DIRECT, BYFILE);
        assertTrue(compilation.success(), compilation.diagnostics()::toString);
        Map<String, CensusRow> rows = rows(compilation);
        assertEquals(LstStatus.MODULE_INFO.label(), rows.get("module-info.java").status());
        assertEquals(1, rows.get("module-info.java").parseEvents());
        assertEquals(LstStatus.OK.label(), rows.get("p/Log.java").status());
    }

    @Test
    void filesUnderTheBuildDirectoryAreSkippedAsRewriteMavenPluginSkipsTheirRoots() throws IOException {
        Map<String, CensusRow> rows = rows(Javac.compile("bridge", Fixtures.generated(), Javac.Mode.DIRECT, BYFILE));
        assertEquals(
                LstStatus.GENERATED.label(),
                rows.get("target/generated-sources/annotations/demo/Gen.java").status());
        assertEquals(LstStatus.OK.label(), rows.get("demo/User.java").status());
    }

    @Test
    void aBuildDirectoryOptionDecidesWhatIsGeneratedNotADirectoryName() throws IOException {
        Map<String, CensusRow> ancestor =
                rows(Javac.compile("bridge", EdgeFixtures.generatedSourcesAncestor(), Javac.Mode.DIRECT, BYFILE));
        assertEquals(LstStatus.OK.label(), ancestor.get("p/A.java").status());
        Fixture generated = Fixtures.generated().named("generated-build-dir-option");
        Javac.PluginArgs elsewhere = (census, src) -> "root=" + src + " buildDir=" + src.resolve("demo");
        Map<String, CensusRow> rows = rows(Javac.compile("bridge", generated, Javac.Mode.DIRECT, elsewhere, BYFILE));
        assertEquals(LstStatus.GENERATED.label(), rows.get("demo/User.java").status());
        assertEquals(
                LstStatus.OK.label(),
                rows.get("target/generated-sources/annotations/demo/Gen.java").status());
    }

    @Test
    void theStaticEntryPointConvertsAUnitOfAnyRunningTaskWithoutThePlugin() throws IOException {
        Path src = Fixtures.compiling()
                .get(0)
                .write(Javac.WORK.resolve("entry-point").toAbsolutePath());
        Path file = src.resolve("demo/Plain.java");
        List<J.CompilationUnit> converted = compileWithOwnListener(src, file);
        assertEquals(1, converted.size());
        assertEquals(
                Files.readString(file, StandardCharsets.UTF_8), converted.get(0).printAll());
        assertEquals(
                "demo/Plain.java", converted.get(0).getSourcePath().toString().replace('\\', '/'));
    }

    /** Compiles one file with a listener of its own, not the plugin's, that converts at every ANALYZE event. */
    private static List<J.CompilationUnit> compileWithOwnListener(Path src, Path file) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<J.CompilationUnit> converted = new ArrayList<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options =
                    List.of("-proc:none", "-d", src.resolveSibling("classes").toString());
            JavacTask task =
                    (JavacTask) javac.getTask(null, files, null, options, null, files.getJavaFileObjects(file));
            task.addTaskListener(convertingAtAnalyze(task, src, converted));
            assertTrue(task.call());
        }
        return converted;
    }

    private static TaskListener convertingAtAnalyze(JavacTask task, Path src, List<J.CompilationUnit> converted) {
        return new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ANALYZE) {
                    converted.add(convert(task, event, src));
                }
            }
        };
    }

    private static J.CompilationUnit convert(JavacTask task, TaskEvent event, Path src) {
        try {
            return CompilerTreeLst.toLst(task, event.getCompilationUnit(), src, new InMemoryExecutionContext());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, CensusRow> rows(Javac.Compilation compilation) {
        return compilation.collector().orElseThrow().census().stream()
                .collect(Collectors.toMap(CensusRow::path, Function.identity()));
    }

    private static void assertConverted(Fixture fixture) throws IOException {
        Javac.Compilation compilation = Javac.compile("bridge", fixture, Javac.Mode.DIRECT, BYFILE);
        assertTrue(compilation.success(), () -> String.join("\n", compilation.diagnostics()));
        LstCollector collector = compilation.collector().orElseThrow();
        List<FileLst> files = collector.files();
        assertEquals(
                fixture.files().keySet().stream().sorted().toList(),
                files.stream().map(f -> f.row().path()).toList());
        files.forEach(file -> assertConverted(fixture, collector, file));
    }

    private static void assertConverted(Fixture fixture, LstCollector collector, FileLst file) {
        CensusRow row = file.row();
        String printed = collector
                .result(row.path())
                .map(result -> result.lst().printAll())
                .orElse("<no tree>");
        assertEquals(LstStatus.OK.label(), row.status(), () -> row.tsv() + "\n" + printed);
        assertEquals(1, row.parseEvents(), row::tsv);
        assertTrue(row.printEqual(), row::tsv);
        assertEquals(0, row.unattributedMethodCalls(), row::tsv);
        J.CompilationUnit lst = file.lst().orElseThrow();
        assertArrayEquals(fixture.files().get(row.path()), lst.printAllAsBytes(), row::tsv);
        assertEquals(row.path(), lst.getSourcePath().toString().replace('\\', '/'));
    }
}
