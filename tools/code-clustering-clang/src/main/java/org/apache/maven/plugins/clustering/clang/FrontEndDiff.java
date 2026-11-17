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
package org.apache.maven.plugins.clustering.clang;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.clang.AtomDiff.Row;
import org.apache.maven.plugins.clustering.clang.ClangFrontEnd.Outcome;
import org.apache.maven.plugins.clustering.clang.ClangRun.Status;

/**
 * Compares the clang front end with the lexical front end on a source tree:
 * {@code java -cp <plugin jar>:<this jar>:<jackson-core> org.apache.maven.plugins.clustering.clang.FrontEndDiff
 * <root> <output folder> [file list]}, with the front end's settings as {@code -D} system properties. Every C-family
 * file under the root (or each file named in the list, one per line, relative to the root) is atomized by both;
 * files clang could not read are listed with their reason and left out of the comparison. Writes
 * {@code differences.tsv} (one row per pair or lone atom, see {@link AtomDiff}), {@code classes.tsv} (count and
 * three examples per class) and {@code outcomes.tsv} (per file: clang's status, atoms, detail), and prints the
 * class counts.
 */
public final class FrontEndDiff {
    private FrontEndDiff() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: FrontEndDiff <root> <output folder> [file list]");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        List<Path> files = args.length > 2 ? listed(root, Path.of(args[2])) : walk(root);
        Report report = run(root, files, ClangSettings.configured());
        report.write(Path.of(args[1]));
        System.out.print(report.summary());
    }

    /** The comparison's rows and clang's outcome per file. */
    record Report(List<Row> rows, List<Outcome> outcomes, Path root) {
        Map<String, List<Row>> byClass() {
            return rows.stream().collect(Collectors.groupingBy(Row::diffClass, TreeMap::new, Collectors.toList()));
        }

        String summary() {
            long noted = outcomes.stream().filter(o -> o.status() == Status.AST && !o.detail().isEmpty()).count();
            StringBuilder s = new StringBuilder("files: " + ClangFrontEnd.count(outcomes) + ", parsed with errors or stub headers: "
                    + noted + "\n");
            byClass().forEach((k, v) -> s.append(k).append('\t').append(v.size()).append('\n'));
            return s.toString();
        }

        void write(Path dir) throws IOException {
            Files.createDirectories(dir);
            Files.write(dir.resolve("differences.tsv"), list(Stream.concat(Stream.of(
                    "class\tpath\tclang_symbol\tclang_lines\tlexical_symbol\tlexical_lines"), rows.stream().map(FrontEndDiff::row))));
            Files.write(dir.resolve("classes.tsv"), list(Stream.concat(Stream.of("class\tcount\texamples"),
                    byClass().entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue().size() + "\t" + examples(e.getValue())))));
            Files.write(dir.resolve("outcomes.tsv"), list(Stream.concat(Stream.of("file\tstatus\tatoms\tdetail"),
                    outcomes.stream().sorted((a, b) -> a.file().compareTo(b.file())).map(o -> outcome(root, o)))));
        }
    }

    /** Atomizes the files with both front ends and compares them. */
    static Report run(Path root, List<Path> files, ClangSettings settings) throws IOException {
        List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        ClangFrontEnd clang = new ClangFrontEnd(settings, outcomes::add);
        List<Path> accepted = files.stream().filter(clang::accepts).toList();
        List<Atom> clangAtoms = clang.atoms(root, accepted);
        Set<String> parsed = outcomes.stream().filter(o -> o.status() == Status.AST).map(o -> relative(root, o.file()))
                .collect(Collectors.toSet());
        AtomFrontEnd lexical = new AtomFrontEnd.LexicalFrontEnd();
        List<Path> compared = accepted.stream().filter(f -> parsed.contains(relative(root, f))).toList();
        List<Atom> lexicalAtoms = lexical.atoms(root, compared.stream().filter(lexical::accepts).toList());
        List<Atom> clangCompared = clangAtoms.stream().filter(a -> parsed.contains(a.path())).toList();
        return new Report(AtomDiff.compare(clangCompared, lexicalAtoms, p -> source(root.resolve(p))), List.copyOf(outcomes), root);
    }

    private static SourceLines source(Path file) {
        try {
            return SourceLines.read(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static List<Path> walk(Path root) throws IOException {
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(Files::isRegularFile).filter(f -> ClangFrontEnd.EXTENSIONS.contains(AtomFrontEnd.extension(f)))
                    .sorted().toList();
        }
    }

    private static List<Path> listed(Path root, Path list) throws IOException {
        return Files.readAllLines(list, StandardCharsets.UTF_8).stream().map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> root.resolve(s).normalize()).filter(Files::isRegularFile).toList();
    }

    private static String row(Row r) {
        return r.diffClass() + "\t" + r.path() + "\t" + symbol(r.clang()) + "\t" + range(r.clang()) + "\t"
                + symbol(r.lexical()) + "\t" + range(r.lexical());
    }

    private static String symbol(Atom a) {
        return a == null ? "" : a.kind() + " " + a.symbol();
    }

    private static String range(Atom a) {
        return a == null ? "" : a.line() + "-" + a.endLine();
    }

    private static String examples(List<Row> rows) {
        return rows.stream().limit(3).map(r -> r.path() + ":" + range(r.clang() != null ? r.clang() : r.lexical()) + " "
                + symbol(r.clang()) + " | " + symbol(r.lexical())).collect(Collectors.joining(" ; "));
    }

    private static String outcome(Path root, Outcome o) {
        return relative(root, o.file()) + "\t" + o.status() + "\t" + o.atoms() + "\t" + o.detail().replace('\t', ' ');
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static List<String> list(Stream<String> rows) {
        return rows.toList();
    }
}
