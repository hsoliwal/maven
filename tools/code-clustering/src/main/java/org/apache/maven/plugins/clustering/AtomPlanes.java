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
package org.apache.maven.plugins.clustering;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Atoms of Java sources with their hash planes, from the compiler's own syntax tree (parse only: no class path, no
 * attribution, no second parser). An atom is a type or a method. Its planes:
 * <ul>
 *   <li>{@code exact}: SHA-256 of the atom's source text, line endings normalized;</li>
 *   <li>{@code logic}: SHA-256 of the tree in pre-order with every name the atom declares (parameters, locals, the
 *       atom's own name) replaced by its first-use index, literals and outside names kept: equal logic after a rename;</li>
 *   <li>{@code structure}: SHA-256 of the node kinds only: equal shape;</li>
 *   <li>{@code simhash}: 64-bit SimHash over 3-token shingles of the logic tokens: near duplicates.</li>
 * </ul>
 * Plane values are this facility's own; compare them only with values it produced. Other languages get the same four
 * planes from {@link LexicalAtoms}.
 */
public final class AtomPlanes {
    private AtomPlanes() { }

    /**
     * One atom: where it is, what it is, its size in logic tokens, its four planes, and its first and last source
     * lines ({@code line}, {@code endLine}: 1-based, 0 when the front end does not know them).
     */
    public record Atom(String path, String kind, String symbol, int tokens, String exact, String logic,
            String structure, String simhash, int line, int endLine) { }

    /**
     * Atomizes every source file under the roots with the {@link AtomFrontEnd front ends}: discovered ones first, then
     * javac for {@code .java} and the lexical front end for the brace and indentation languages. Returns the atoms in
     * path order.
     */
    public static List<Atom> scan(List<Path> roots) throws IOException {
        return scan(roots, AtomFrontEnd.all());
    }

    /** As {@link #scan(List)} with an explicit, ordered list of front ends; each file goes to the first that accepts it. */
    public static List<Atom> scan(List<Path> roots, List<AtomFrontEnd> frontEnds) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (Path given : roots) {
            Path root = given.toAbsolutePath().normalize();
            Map<AtomFrontEnd, List<Path>> assigned = new LinkedHashMap<>();
            for (Path file : sourceFiles(root, frontEnds)) {
                frontEnds.stream().filter(f -> f.accepts(file)).findFirst()
                        .ifPresent(f -> assigned.computeIfAbsent(f, k -> new ArrayList<>()).add(file));
            }
            List<Atom> found = new ArrayList<>();
            for (Map.Entry<AtomFrontEnd, List<Path>> e : assigned.entrySet()) found.addAll(e.getKey().atoms(root, e.getValue()));
            found.sort(Comparator.comparing(Atom::path));
            atoms.addAll(found);
        }
        return atoms;
    }

    /** Files under the root that a front end accepts, skipping build output, dependency caches and version control. */
    static List<Path> sourceFiles(Path root, List<AtomFrontEnd> frontEnds) throws IOException {
        return sourceFiles(root, p -> frontEnds.stream().anyMatch(f -> f.accepts(p)));
    }

    /** Files under the root that the filter accepts, skipping the same directories. */
    static List<Path> sourceFiles(Path root, Predicate<Path> accepted) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p) && !skipped(root.relativize(p))).filter(accepted).sorted()
                    .collect(Collectors.toList());
        }
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIPPED.contains(part.toString())) return true;
        }
        return false;
    }

    private static final java.util.Set<String> SKIPPED = java.util.Set.of("node_modules", ".git", "target", "build",
            "dist", "out", "vendor", "__pycache__", ".venv", "venv", ".gradle", ".idea");

    /**
     * Atomizes the Java files, adds every method declared {@code native} to {@code natives} and the binary name of
     * every named class to {@code classes}, for the JNI linker.
     */
    static List<Atom> parse(Path root, List<Path> files, List<JniMethod> natives, Set<String> classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("planes need a JDK (no system Java compiler)");
        DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, ignored, List.of("-proc:none"), null,
                    fm.getJavaFileObjectsFromPaths(files));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            JniTypes types = new JniTypes(units);
            classes.addAll(types.binaries());
            return atoms(root, units, positions, natives, types);
        }
    }

    /** The atoms of the parsed units; the types they declare resolve the argument types of their native methods. */
    private static List<Atom> atoms(Path root, List<CompilationUnitTree> units, SourcePositions positions,
            List<JniMethod> natives, JniTypes types) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            String path = root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\', '/');
            new Collector(unit, positions, path, atoms, types, natives).scan(unit, null);
        }
        return atoms;
    }

    /** Walks one compilation unit and records an atom for every type and method, and every native method. */
    private static final class Collector extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String path;
        private final List<Atom> atoms;
        private final JniTypes types;
        private final List<JniMethod> natives;
        private final String text;
        private final List<String> owners = new ArrayList<>();

        Collector(CompilationUnitTree unit, SourcePositions positions, String path, List<Atom> atoms, JniTypes types,
                List<JniMethod> natives) throws IOException {
            this.unit = unit;
            this.positions = positions;
            this.path = path;
            this.atoms = atoms;
            this.types = types;
            this.natives = natives;
            this.text = unit.getSourceFile().getCharContent(true).toString();
        }

        @Override public Void visitClass(ClassTree node, Void unused) {
            owners.add(node.getSimpleName().toString());
            record("TYPE", String.join(".", owners), node);
            super.visitClass(node, unused);
            owners.remove(owners.size() - 1);
            return null;
        }

        @Override public Void visitMethod(MethodTree node, Void unused) {
            String params = node.getParameters().stream().map(p -> p.getType().toString())
                    .collect(Collectors.joining(","));
            String symbol = String.join(".", owners) + "." + node.getName() + "(" + params + ")";
            record("METHOD", symbol, node);
            if (node.getModifiers().getFlags().contains(Modifier.NATIVE)) {
                types.method(path, symbol, getCurrentPath()).ifPresent(natives::add);
            }
            return super.visitMethod(node, unused);
        }

        private void record(String kind, String symbol, Tree node) {
            long start = positions.getStartPosition(unit, node);
            long end = positions.getEndPosition(unit, node);
            if (start < 0 || end <= start || end > text.length()) return;
            LineMap lines = unit.getLineMap();
            atoms.add(PlaneTokens.atom(node, path, kind, symbol, text.substring((int) start, (int) end))
                    .lines((int) lines.getLineNumber(start), (int) lines.getLineNumber(end - 1)).build());
        }
    }
}
