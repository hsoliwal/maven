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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * A language front end: turns source files into atoms through {@link AtomBuilder}. Implementations on the plugin's
 * class path register in {@code META-INF/services/org.apache.maven.plugins.clustering.AtomFrontEnd}, typically by
 * being added as a dependency of the plugin in the POM. Discovered front ends come first, in class-path order, then
 * the built-in javac and lexical front ends. Each file goes to the first front end that accepts it. This lets a
 * dedicated parser take over, for example an Eclipse language core such as JDT for Java or CDT for C and C++.
 */
public interface AtomFrontEnd {
    /** A short name for reports, for example {@code javac}, {@code lexical}, {@code jdt}. */
    String name();

    boolean accepts(Path file);

    /**
     * Atomizes the files, all accepted by this front end and all under {@code root}. Atom paths are relative to
     * the root and use {@code /} separators.
     */
    List<Atom> atoms(Path root, List<Path> files) throws IOException;

    /** The file's extension in lower case, without the dot; empty when it has none. Front ends choose files by it. */
    static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Discovered front ends, then javac, then lexical. */
    static List<AtomFrontEnd> all() {
        List<AtomFrontEnd> all = new ArrayList<>();
        ServiceLoader.load(AtomFrontEnd.class, AtomFrontEnd.class.getClassLoader()).forEach(all::add);
        all.add(new JavacFrontEnd());
        all.add(new LexicalFrontEnd());
        return all;
    }

    /**
     * The built-in Java front end: the compiler's own parser. It also keeps the methods declared {@code native} and
     * the binary names of the classes it parsed.
     */
    final class JavacFrontEnd implements AtomFrontEnd {
        private final List<JniMethod> natives = new ArrayList<>();
        private final Set<String> classes = new TreeSet<>();

        @Override public String name() {
            return "javac";
        }

        @Override public boolean accepts(Path file) {
            return file.getFileName().toString().endsWith(".java");
        }

        @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
            return AtomPlanes.parse(root, files, natives, classes);
        }

        /** The native methods of the files this front end has atomized so far, for {@link JniLinker}. */
        List<JniMethod> natives() {
            return List.copyOf(natives);
        }

        /** The binary names in internal form ({@code p/Outer$Inner}) of the named classes parsed so far. */
        Set<String> classes() {
            return Set.copyOf(classes);
        }
    }

    /** The built-in lexical front end for the brace and indentation languages. */
    final class LexicalFrontEnd implements AtomFrontEnd {
        @Override public String name() {
            return "lexical";
        }

        @Override public boolean accepts(Path file) {
            return LexicalAtoms.accepts(file);
        }

        @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
            return LexicalAtoms.scan(root, files);
        }
    }
}
