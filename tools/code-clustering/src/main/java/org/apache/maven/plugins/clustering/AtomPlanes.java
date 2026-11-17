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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * Plane values are this facility's own; compare them only with values it produced.
 */
public final class AtomPlanes {
    private AtomPlanes() { }

    /** One atom: where it is, what it is, its size in logic tokens, and its four planes. */
    public record Atom(String path, String kind, String symbol, int tokens, String exact, String logic,
            String structure, String simhash) { }

    /** Parses every {@code .java} file under the roots and returns their atoms, in path order. */
    public static List<Atom> scan(List<Path> roots) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (Path given : roots) {
            Path root = given.toAbsolutePath().normalize();
            List<Path> files = javaFiles(root);
            if (!files.isEmpty()) atoms.addAll(parse(root, files));
        }
        return atoms;
    }

    static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<Atom> parse(Path root, List<Path> files) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("planes need a JDK (no system Java compiler)");
        DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, ignored, List.of("-proc:none"), null,
                    fm.getJavaFileObjectsFromPaths(files));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            List<Atom> atoms = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                String path = root.relativize(Path.of(unit.getSourceFile().toUri())).toString().replace('\\', '/');
                new Collector(unit, positions, path, atoms).scan(unit, null);
            }
            return atoms;
        }
    }

    /** Walks one compilation unit and records an atom for every type and method. */
    private static final class Collector extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String path;
        private final List<Atom> atoms;
        private final String text;
        private final List<String> owners = new ArrayList<>();

        Collector(CompilationUnitTree unit, SourcePositions positions, String path, List<Atom> atoms) throws IOException {
            this.unit = unit;
            this.positions = positions;
            this.path = path;
            this.atoms = atoms;
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
            record("METHOD", String.join(".", owners) + "." + node.getName() + "(" + params + ")", node);
            return super.visitMethod(node, unused);
        }

        private void record(String kind, String symbol, Tree node) {
            long start = positions.getStartPosition(unit, node);
            long end = positions.getEndPosition(unit, node);
            if (start < 0 || end <= start || end > text.length()) return;
            String source = text.substring((int) start, (int) end).replace("\r\n", "\n");
            PlaneTokens tokens = PlaneTokens.of(node);
            atoms.add(new Atom(path, kind, symbol, tokens.logicTokens().size(), PlaneHash.sha256(source),
                    PlaneHash.sha256(tokens.logic()), PlaneHash.sha256(tokens.structure()),
                    PlaneHash.simhash(tokens.logicTokens())));
        }
    }
}
