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

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failures inside the plugin never reach javac: an OutOfMemoryError or a StackOverflowError thrown while a file is
 * converted is contained (the isolation scope restores javac's state without allocating, the file is failed and the
 * plugin stops with one notice), the heap gate turns trees off with one notice, and below the heap floor the boot
 * entry registers no listener at all. In every case the compile is the same as without the plugin.
 */
class ContainmentTest {
    private static final String NOTICE = "[" + Javac.PLUGIN + "]";

    @Test
    void anOutOfMemoryErrorInTheConversionIsContained() throws IOException {
        assertContained(() -> new OutOfMemoryError("Java heap space (test)"), "java.lang.OutOfMemoryError");
    }

    @Test
    void aStackOverflowErrorInTheConversionIsContained() throws IOException {
        assertContained(StackOverflowError::new, "java.lang.StackOverflowError");
    }

    @Test
    void aClosedHeapGateTurnsTreesOffWithOneNotice() throws IOException {
        Fixture fixture = IsolationFixtures.completion(true).named("heap-gate");
        Javac.PluginArgs args = (census, src) -> "headroom=100 " + Javac.PluginArgs.DEFAULT.of(census, src);
        Javac.Compilation plain = Javac.compile("containment", fixture, Javac.Mode.NONE, "-Xlint:all");
        Javac.Compilation with = Javac.compile("containment", fixture, Javac.Mode.XPLUGIN, args, "-Xlint:all");
        assertEquals(plain.diagnostics(), with.diagnostics());
        assertEquals(plain.classes(), with.classes());
        assertEquals(
                1, with.output().lines().filter(line -> line.startsWith(NOTICE)).count(), with.output());
        with.census().lines().skip(1).forEach(row -> assertTrue(row.contains("\tskipped: heap headroom\t-"), row));
    }

    @Test
    void belowTheHeapFloorNoListenerIsRegistered() throws IOException {
        Fixture fixture = IsolationFixtures.completion(true).named("heap-floor");
        Javac.PluginArgs args = (census, src) -> "minHeap=100000000 " + Javac.PluginArgs.DEFAULT.of(census, src);
        Javac.Compilation plain = Javac.compile("containment", fixture, Javac.Mode.NONE, "-Xlint:all");
        Javac.Compilation with = Javac.compile("containment", fixture, Javac.Mode.XPLUGIN, args, "-Xlint:all");
        assertEquals(plain.diagnostics(), with.diagnostics());
        assertEquals(plain.classes(), with.classes());
        assertEquals(plain.output(), with.output());
        assertEquals("", with.census());
    }

    @Test
    void withoutKeepingNoTreeOutlivesItsRow() throws IOException {
        Fixture fixture = IsolationFixtures.many(20);
        Path src = fixture.write(Javac.WORK.resolve("containment-stream").toAbsolutePath());
        Outcome outcome = compile(sources(fixture, src, () -> null), Optional.of(path -> false), src);
        LstCollector collector = outcome.collector().orElseThrow();
        collector.census().forEach(row -> assertEquals(LstStatus.OK.label(), row.status(), row::tsv));
        collector.files().forEach(file -> assertTrue(file.lst().isEmpty(), file.row()::tsv));
        collector
                .census()
                .forEach(row -> assertTrue(collector.result(row.path()).isEmpty(), row::tsv));
    }

    /** Two files; reading the first one's text for the conversion throws the given error, javac's own read does not. */
    private static void assertContained(Supplier<Error> error, String name) throws IOException {
        Fixture fixture = Fixture.of("contained-" + name.substring(name.lastIndexOf('.') + 1), "own", BIG_AND_NEXT);
        Path src = fixture.write(
                Javac.WORK.resolve("containment").resolve(fixture.name()).toAbsolutePath());
        Outcome plain = compile(sources(fixture, src, () -> null), Optional.empty(), src);
        Outcome with = compile(sources(fixture, src, error), Optional.of(path -> true), src);
        assertSameCompile(plain, with);
        Map<String, CensusRow> rows = rows(with.collector().orElseThrow());
        assertTrue(with.output().startsWith(NOTICE) && with.output().lines().count() == 1, with.output() + rows);
        assertTrue(rows.get("p/Big.java").status().startsWith("failed: contained "), rows.toString());
        assertTrue(rows.get("p/Big.java").status().contains(name), rows.toString());
        assertEquals(LstStatus.STOPPED.label(), rows.get("p/Next.java").status());
    }

    private static final String[] BIG_AND_NEXT = {
        "p/Big.java", "package p;\npublic class Big { int f() { return 1; } }\n",
        "p/Next.java", "package p;\npublic class Next { int g() { return new Big().f(); } }\n"
    };

    private static void assertSameCompile(Outcome plain, Outcome with) {
        assertEquals(plain.success(), with.success());
        assertEquals(plain.diagnostics(), with.diagnostics());
        assertEquals(plain.classes(), with.classes());
    }

    private static Map<String, CensusRow> rows(LstCollector collector) {
        return collector.census().stream().collect(java.util.stream.Collectors.toMap(CensusRow::path, row -> row));
    }

    /** What one in-process compile produced. */
    private record Outcome(
            boolean success,
            List<String> diagnostics,
            Map<String, String> classes,
            String output,
            Optional<LstCollector> collector) {}

    private static Outcome compile(List<JavaFileObject> sources, Optional<Predicate<String>> keep, Path src)
            throws IOException {
        Path classes = classes(src, keep.isPresent());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavacTask task = (JavacTask) javac.getTask(
                    output, files, diagnostics, List.of("-proc:none", "-d", classes.toString()), null, sources);
            Optional<LstCollector> collector =
                    keep.flatMap(k -> LstCollector.register(task, k, "root=" + src, "check=true"));
            boolean success = task.call();
            return new Outcome(success, describe(diagnostics), Javac.hashes(classes), output.toString(), collector);
        }
    }

    private static Path classes(Path src, boolean plugin) throws IOException {
        Path classes = src.resolveSibling(plugin ? "classes-plugin" : "classes-plain");
        Javac.emptyDirectory(classes);
        return classes;
    }

    private static List<String> describe(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(d -> d.getKind() + "|" + d.getCode() + "|" + d.getMessage(Locale.ROOT))
                .toList();
    }

    private static List<JavaFileObject> sources(Fixture fixture, Path src, Supplier<Error> onConversionRead) {
        List<JavaFileObject> sources = new ArrayList<>();
        fixture.files()
                .keySet()
                .forEach(path ->
                        sources.add(new Source(src.resolve(path), sources.isEmpty() ? onConversionRead : () -> null)));
        return sources;
    }

    /**
     * A source file on disk whose text javac reads as usual ({@code getCharContent(false)}), while the conversion's
     * read ({@code getCharContent(true)}, inside the isolation scope) throws the given error, if any.
     */
    private static final class Source extends SimpleJavaFileObject {
        private final Path path;
        private final Supplier<Error> onConversionRead;

        Source(Path path, Supplier<Error> onConversionRead) {
            super(path.toUri(), Kind.SOURCE);
            this.path = path;
            this.onConversionRead = onConversionRead;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            Error error = ignoreEncodingErrors ? onConversionRead.get() : null;
            if (error != null) {
                throw error;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }

        @Override
        public URI toUri() {
            return path.toUri();
        }
    }
}
