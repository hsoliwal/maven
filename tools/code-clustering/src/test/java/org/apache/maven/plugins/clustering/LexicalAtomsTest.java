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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Lexical front end checks on JavaScript, Python, Go, C, Rust and Kotlin fixtures: which blocks become atoms and
 * under which symbol; that a renamed copy shares the logic hash, a changed literal shares only the structure hash,
 * and that braces inside strings and comments do not open blocks; and the atoms' source lines in JavaScript and Python.
 */
final class LexicalAtomsTest {
    private static final String JS = String.join("\n",
            "// a { in a comment",
            "function sum(values) {",
            "  let total = 0;",
            "  for (const v of values) { total += v; }",
            "  return total;",
            "}",
            "function add(xs) {",
            "  let acc = 0;",
            "  for (const x of xs) { acc += x; }",
            "  return acc;",
            "}",
            "function start(values) {",
            "  let total = 1;",
            "  for (const v of values) { total += v; }",
            "  return total;",
            "}",
            "class Bag {",
            "  size(items) { const s = \"{\"; return items.length + s.length; }",
            "}",
            "const twice = (n) => { if (n > 0) { return n * 2; } return 0; };",
            "");
    private static final String PY = String.join("\n",
            "class Stats:",
            "    def total(self, values):",
            "        acc = 0",
            "        for v in values:",
            "            acc += v",
            "        return acc",
            "",
            "def total(values):",
            "    acc = 0",
            "    for v in values:",
            "        acc += v",
            "    return acc",
            "",
            "@cached",
            "def summe(items):",
            "    s = 0",
            "    for x in items:",
            "        s += x",
            "    return s",
            "");
    private static final String GO = String.join("\n",
            "package ring",
            "type Ring struct {",
            "  items []int",
            "}",
            "func (r *Ring) Push(v int) {",
            "  if v > 0 {",
            "    r.items = append(r.items, v)",
            "  }",
            "}",
            "");
    private static final String C = "struct point { int x; int y; };\nstatic int add(int a, int b) {\n  return a + b;\n}\n";
    private static final String RS = "impl Point {\n    fn norm(&self) -> f64 {\n        (self.x * self.x).sqrt()\n    }\n}\n";
    private static final String KT = "class Foo(val a: Int) {\n    fun twice(): Int {\n        return a * 2\n    }\n}\n";
    private static int failures;

    private LexicalAtomsTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        Map<String, String> files = Map.of("a.js", JS, "s.py", PY, "r.go", GO, "p.c", C, "n.rs", RS, "f.kt", KT);
        for (Map.Entry<String, String> e : files.entrySet()) Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        List<Atom> list = AtomPlanes.scan(List.of(dir));
        Map<String, Atom> atoms = list.stream().collect(Collectors.toMap(a -> a.path() + "#" + a.kind() + "#" + a.symbol(), Function.identity()));
        String names = String.join(" ", atoms.keySet().stream().sorted().collect(Collectors.toList()));
        for (String expected : List.of("a.js#METHOD#sum(1)", "a.js#METHOD#add(1)", "a.js#METHOD#start(1)",
                "a.js#TYPE#Bag", "a.js#METHOD#Bag.size(1)", "a.js#METHOD#twice(1)", "s.py#TYPE#Stats",
                "s.py#METHOD#Stats.total(2)", "s.py#METHOD#total(1)", "s.py#METHOD#summe(1)", "r.go#TYPE#Ring",
                "r.go#METHOD#Push(1)", "p.c#TYPE#point", "p.c#METHOD#add(2)", "n.rs#TYPE#Point",
                "n.rs#METHOD#Point.norm(1)", "f.kt#TYPE#Foo", "f.kt#METHOD#Foo.twice(0)")) {
            check("atom " + expected, atoms.containsKey(expected));
        }
        check("no control-statement atoms in " + names, atoms.size() == 18);
        Atom sum = atoms.get("a.js#METHOD#sum(1)");
        Atom add = atoms.get("a.js#METHOD#add(1)");
        Atom start = atoms.get("a.js#METHOD#start(1)");
        check("js logic: renamed copy", sum.logic().equals(add.logic()) && !sum.exact().equals(add.exact()));
        check("js structure: literal changed", sum.structure().equals(start.structure()) && !sum.logic().equals(start.logic()));
        Atom total = atoms.get("s.py#METHOD#total(1)");
        Atom summe = atoms.get("s.py#METHOD#summe(1)");
        check("py logic: renamed copy with a decorator differs", !total.logic().equals(summe.logic()));
        check("py structure: same shape apart from the decorator", total.tokens() + 2 == summe.tokens());
        check("py near: decorated copy within radius", PlaneHash.hamming(total.simhash(), summe.simhash()) <= 12);
        check("py method in class: own symbol", !atoms.get("s.py#METHOD#Stats.total(2)").logic().equals(total.logic()));
        check("js lines: sum 2-6, Bag 17-19, Bag.size 18, twice 20", lines(sum, 2, 6)
                && lines(atoms.get("a.js#TYPE#Bag"), 17, 19) && lines(atoms.get("a.js#METHOD#Bag.size(1)"), 18, 18)
                && lines(atoms.get("a.js#METHOD#twice(1)"), 20, 20));
        check("py lines: Stats 1-6, Stats.total 2-6, total 8-12, decorated summe 14-19",
                lines(atoms.get("s.py#TYPE#Stats"), 1, 6) && lines(atoms.get("s.py#METHOD#Stats.total(2)"), 2, 6)
                && lines(total, 8, 12) && lines(summe, 14, 19));
    }

    private static boolean lines(Atom atom, int line, int endLine) {
        return atom != null && atom.line() == line && atom.endLine() == endLine;
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
