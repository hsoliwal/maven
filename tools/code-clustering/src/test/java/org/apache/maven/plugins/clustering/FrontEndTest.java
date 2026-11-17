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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Front-end SPI checks. The service-registered {@link ToyFrontEnd} is discovered first, and its renamed copies share
 * the logic plane. A front end earlier in an explicit list takes files away from the built-in ones. The range
 * entry point used by language-server adapters gives the same logic for a renamed copy.
 */
final class FrontEndTest {
    private static int failures;

    private FrontEndTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.toy"), "fn double x = x plus x\nfn twice y = y plus y\nfn bump x = x plus z\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.js"), "function f(a) { return a + 1; }\n", StandardCharsets.UTF_8);
        List<String> names = AtomFrontEnd.all().stream().map(AtomFrontEnd::name).collect(Collectors.toList());
        check("discovery order " + names, names.equals(List.of("toy", "javac", "lexical")));
        List<Atom> atoms = AtomPlanes.scan(List.of(dir));
        Atom d = find(atoms, "a.toy", "double(1)");
        Atom t = find(atoms, "a.toy", "twice(1)");
        Atom b = find(atoms, "a.toy", "bump(1)");
        check("toy atoms + js atom: " + atoms.size(), atoms.size() == 4 && d != null && t != null && b != null);
        check("toy logic: renamed copy", d.logic().equals(t.logic()) && !d.exact().equals(t.exact()));
        check("toy structure: outside name for a parameter", d.structure().equals(b.structure()) && !d.logic().equals(b.logic()));
        AtomFrontEnd takeover = new AtomFrontEnd() {
            @Override public String name() {
                return "takeover";
            }

            @Override public boolean accepts(Path file) {
                return file.toString().endsWith(".js");
            }

            @Override public List<Atom> atoms(Path root, List<Path> files) {
                return List.of(AtomBuilder.atom("b.js", "TYPE", "Whole", "x").node("FILE").build());
            }
        };
        List<Atom> taken = AtomPlanes.scan(List.of(dir), List.of(takeover, new AtomFrontEnd.LexicalFrontEnd()));
        check("earlier front end takes the file", taken.size() == 1 && taken.get(0).symbol().equals("Whole"));
        String js = "function sum(v) { let t = 0; for (const x of v) { t += x; } return t; }\n"
                + "function add(w) { let s = 0; for (const y of w) { s += y; } return s; }\n";
        int split = js.indexOf("function add");
        Atom r1 = LexicalAtoms.rangeAtom("r.js", js, 0, split - 1, "METHOD", "sum", "", false);
        Atom r2 = LexicalAtoms.rangeAtom("r.js", js, split, js.length() - 1, "METHOD", "add", "", false);
        check("range atoms: renamed copy shares logic", r1.logic().equals(r2.logic()) && r1.symbol().equals("sum(1)"));
    }

    private static Atom find(List<Atom> atoms, String path, String symbol) {
        return atoms.stream().filter(a -> a.path().equals(path) && a.symbol().equals(symbol)).findFirst().orElse(null);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
