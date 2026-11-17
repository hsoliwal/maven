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
package org.apache.maven.plugins.clustering.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Duplicated atoms as LSP diagnostics. Atoms of the same kind with at least {@code minTokens} logic tokens that share
 * the EXACT plane, or else the LOGIC plane, are duplicates. Each duplicate gets one Information diagnostic with source
 * {@value #SOURCE} and code {@code EXACT} or {@code LOGIC}, on the atom's lines, naming the other members, for example
 * {@code Same logic as q/B.java#B.sum(int[]) and 2 more}. An atom in both kinds of group gets the EXACT one only.
 */
final class DuplicateDiagnostics {
    static final String SOURCE = "code-clustering";

    /** An atom and the workspace root its path is relative to. */
    record Located(Path root, Atom atom) {
        Path file() {
            return root.resolve(atom.path());
        }

        String name() {
            return atom.path() + "#" + atom.symbol();
        }
    }

    private DuplicateDiagnostics() { }

    /** The diagnostics per file URI; files without duplicates are absent. */
    static Map<String, List<Diagnostic>> of(List<Located> atoms, int minTokens) {
        List<Located> sized = atoms.stream().filter(a -> a.atom().tokens() >= minTokens).collect(Collectors.toList());
        Map<String, List<Located>> exact = groups(sized, a -> a.kind() + ":" + a.exact());
        Map<String, List<Located>> logic = groups(sized, a -> a.kind() + ":" + a.logic());
        Map<Path, String[]> texts = new HashMap<>();
        Map<String, List<Diagnostic>> byUri = new TreeMap<>();
        for (Located a : sized) {
            List<Located> same = exact.get(a.atom().kind() + ":" + a.atom().exact());
            boolean isExact = same.size() > 1;
            if (!isExact) same = logic.get(a.atom().kind() + ":" + a.atom().logic());
            if (same.size() < 2) continue;
            String message = message(isExact ? "Same text as " : "Same logic as ", a, same);
            Range range = range(texts.computeIfAbsent(a.file(), DuplicateDiagnostics::lines), a.atom());
            byUri.computeIfAbsent(a.file().toUri().toString(), k -> new ArrayList<>())
                    .add(new Diagnostic(range, message, DiagnosticSeverity.Information, SOURCE, isExact ? "EXACT" : "LOGIC"));
        }
        return byUri;
    }

    private static Map<String, List<Located>> groups(List<Located> atoms, Function<Atom, String> key) {
        Map<String, List<Located>> groups = new LinkedHashMap<>();
        atoms.forEach(a -> groups.computeIfAbsent(key.apply(a.atom()), k -> new ArrayList<>()).add(a));
        return groups;
    }

    /** The first other member by name, then how many more there are. */
    private static String message(String prefix, Located atom, List<Located> group) {
        List<Located> others = group.stream().filter(o -> o != atom).collect(Collectors.toList());
        return prefix + others.get(0).name() + (others.size() > 1 ? " and " + (others.size() - 1) + " more" : "");
    }

    /** From the first non-blank character of the atom's first line to the end of its last line (0-based lines). */
    static Range range(String[] lines, Atom atom) {
        int first = Math.max(1, atom.line()) - 1;
        int last = Math.max(first, atom.endLine() - 1);
        String start = first < lines.length ? lines[first] : "";
        String end = last < lines.length ? lines[last] : "";
        return new Range(new Position(first, start.length() - start.stripLeading().length()), new Position(last, end.length()));
    }

    /** The file's lines, split as LSP counts them; none when the file cannot be read any more. */
    private static String[] lines(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\r\n|\r|\n", -1);
        } catch (IOException gone) {
            return new String[0];
        }
    }
}
