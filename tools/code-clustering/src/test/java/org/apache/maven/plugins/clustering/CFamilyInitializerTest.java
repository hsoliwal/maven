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
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Member-initializer lists beyond {@link CFamilyBlocksTest}: a class head with a macro call before its base clause is
 * a type, not a constructor; a macro call on the line above a constructor is not its specifier; lower-case and
 * underscore specifier macros before the {@code :}; template arguments with a comma in a brace-initialized base;
 * {@code :} in both {@code #if} branches; a constructor after a macro line and {@code #define} lines starts on its own
 * line; and a lone macro word as the last element keeps the constructor body.
 */
final class CFamilyInitializerTest {
    private static final Map<String, String> FILES = Map.of(
            "heads.cpp", String.join("\n", "struct ALIGNED(16) VEC : Base {", "    int f() { return 1; }",
                    "    int g() { return 2; }", "};", "struct DECLARE_STRUCT(Foo) : Base {", "    int f() { return 3; }",
                    "};", ""),
            "macros.cpp", String.join("\n", "struct DOM {", "  DECLARE_THING(DOM)", "  DOM(int a) : v(a) { }", "  int v;",
                    "};", "struct LSR { DECLARE_THING(LSR)", "  LSR(int a) : v{a} {} int v; };", "struct K {",
                    "  K(short a) my_noexcept : v(a) { }", "  K(char a) _MY_NOEXCEPT : v(a) { }",
                    "  K(long a) __THROW : v(a) { }", "  K(float a) noexcept(true) SK_ACQUIRE(lk) : v(a) { }", "  int v;",
                    "};", ""),
            "elements.cpp", String.join("\n", "struct D : Pair2<int, int> {", "  D() : Pair2<int, int>{1, 2}, z(0) {}",
                    "  D(int a)", "    : Pair2<int, int>{a, a}", "    , z(a)", "  {", "    use(a);", "  }", "  int z;", "};",
                    "struct Nest : Wrap<Pair<int, int>> {", "  Nest() : Wrap<Pair<int, int>>{}, k(0) {}", "  int k;", "};",
                    ""),
            "branches.cpp", String.join("\n", "struct E {", "  E(bool c)", "#if 1", "    : a{1}", "#else", "    : a{2}",
                    "#endif", "  { (void)c; }", "  int a;", "};", ""),
            "joined.cpp", String.join("\n", "U_NAMESPACE_BEGIN", "", "#define MILLIS_PER_YEAR (365*24*60*60*1000.0)",
                    "", "BasicTimeZone::BasicTimeZone()", "    : TimeZone() {", "}", "Foo::Foo()", "#if X", "    noexcept",
                    "#endif", "    : a(0) {", "}", "U_NAMESPACE_END", ""),
            "lone.cpp", String.join("\n", "Foo::Foo() : Base(), INIT_MEMBERS {", "    x = 1;", "}", "void Foo::f() {",
                    "}", "struct Op {", "  Op() : INHERITED{ClassID()}, fA(0) {}",
                    "  Op(int a) : INHERITED{ClassID()} { use(a); }", "};", ""));
    private static int failures;

    private CFamilyInitializerTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        for (Map.Entry<String, String> file : FILES.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
        List<String> atoms = AtomPlanes.scan(List.of(dir)).stream().map(CFamilyInitializerTest::key)
                .collect(Collectors.toList());
        heads(atoms);
        specifiers(atoms);
        elements(atoms);
    }

    private static void heads(List<String> atoms) {
        check("a class head with a macro call before its base clause is a type: " + atoms,
                matches(atoms, "heads.cpp TYPE \\S+ 1-4") && matches(atoms, "heads.cpp METHOD \\S+\\.f\\(0\\) 2-2")
                && matches(atoms, "heads.cpp METHOD \\S+\\.g\\(0\\) 3-3"));
        check("a class head with a name macro before its base clause is a type",
                matches(atoms, "heads.cpp TYPE \\S+ 5-7") && matches(atoms, "heads.cpp METHOD \\S+\\.f\\(0\\) 6-6"));
        check("a constructor after a macro line and #define lines starts on its own line",
                has(atoms, "joined.cpp METHOD BasicTimeZone(0) 5-7"));
        check("#if inside a constructor header before its initializer list", has(atoms, "joined.cpp METHOD Foo(0) 8-13"));
    }

    private static void specifiers(List<String> atoms) {
        check("a macro call on the line above a constructor is not its specifier: " + atoms,
                has(atoms, "macros.cpp METHOD DOM.DOM(1) 3-3") && has(atoms, "macros.cpp METHOD LSR.LSR(1) 7-7")
                && none(atoms, "DECLARE_THING"));
        check("lower-case and underscore specifier macros before the initializer list",
                has(atoms, "macros.cpp METHOD K.K(1) 9-9") && has(atoms, "macros.cpp METHOD K.K(1) 10-10")
                && has(atoms, "macros.cpp METHOD K.K(1) 11-11") && none(atoms, "K.v("));
        check("an upper-case specifier macro with an argument on the parameter line",
                has(atoms, "macros.cpp METHOD K.K(1) 12-12"));
    }

    private static void elements(List<String> atoms) {
        check("a brace-initialized base with template arguments that have a comma: " + atoms,
                has(atoms, "elements.cpp METHOD D.D(0) 2-2") && has(atoms, "elements.cpp METHOD D.D(1) 3-8")
                && has(atoms, "elements.cpp METHOD Nest.Nest(0) 12-12") && none(atoms, ".z(", ".k("));
        check("both #if branches start the initializer list with ':'", has(atoms, "branches.cpp METHOD E.E(1) 2-8"));
        check("a lone macro word as the last element keeps the constructor body",
                has(atoms, "lone.cpp METHOD Foo(0) 1-3") && has(atoms, "lone.cpp METHOD f(0) 4-5"));
        check("a brace-initialized upper-case base before ',' and before the body",
                has(atoms, "lone.cpp METHOD Op.Op(0) 7-7") && has(atoms, "lone.cpp METHOD Op.Op(1) 8-8")
                && none(atoms, "INIT_MEMBERS", "INHERITED", ".fA("));
    }

    private static String key(Atom a) {
        return a.path() + " " + a.kind() + " " + a.symbol() + " " + a.line() + "-" + a.endLine();
    }

    private static boolean none(List<String> atoms, String... names) {
        return atoms.stream().noneMatch(a -> List.of(names).stream().anyMatch(a::contains));
    }

    private static boolean has(List<String> atoms, String key) {
        boolean found = atoms.contains(key);
        if (!found) System.err.println("  missing atom: " + key);
        return found;
    }

    private static boolean matches(List<String> atoms, String regex) {
        boolean found = atoms.stream().anyMatch(a -> a.matches(regex));
        if (!found) System.err.println("  no atom matches: " + regex);
        return found;
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
