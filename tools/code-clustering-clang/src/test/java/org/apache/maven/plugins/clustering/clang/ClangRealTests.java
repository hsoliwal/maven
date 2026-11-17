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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomBuilder;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.clang.ClangFrontEnd.Outcome;
import org.apache.maven.plugins.clustering.clang.ClangRun.Status;

/**
 * Runs the installed clang (PATH, {@code CLANG}, {@code -Dclustering.clang.executable} or the LLVM folders) on
 * fixtures with a compile_commands.json: C++ (constructor, destructor, operator, out-of-line member, macro-made
 * function, template with two instantiations, lambda and local class, {@code #if 0}, a branch chosen by a
 * {@code -D} flag of the database), a header that borrows its source's entry, C ({@code (void)}, variadic),
 * Objective-C, and a file whose header is missing (lexical fallback, then stub headers). Prints SKIP without clang.
 */
public final class ClangRealTests {
    private static final Checks CHECKS = new Checks();

    private ClangRealTests() { }

    public static void main(String[] args) throws Exception {
        ClangSettings configured = ClangSettings.configured();
        if (configured.clang().isEmpty()) {
            System.err.println("SKIP ClangRealTests: no clang (set CLANG or -Dclustering.clang.executable)");
            return;
        }
        System.err.println("clang: " + configured.clang().get());
        run(configured, fixtures(Path.of(args.length > 0 ? args[0] : "target/clang-real").toAbsolutePath()));
        CHECKS.end("ClangRealTests");
    }

    private static void run(ClangSettings configured, Path dir) throws Exception {
        ClangSettings settings = with(configured, Optional.empty());
        List<Outcome> outcomes = new ArrayList<>();
        List<Atom> atoms = new ClangFrontEnd(settings, outcomes::add).atoms(dir, files(dir));
        cpp(atoms);
        planes(atoms);
        others(atoms, outcomes);
        stubs(dir, configured);
        discovery();
        compare(dir, settings);
    }

    private static ClangSettings with(ClangSettings s, Optional<Path> stubs) {
        return new ClangSettings(s.clang(), List.of(), List.of(), stubs, 4, s.timeout(), s.windows());
    }

    private static void check(String name, boolean ok) {
        CHECKS.check(name, ok);
    }

    private static List<Path> files(Path dir) {
        return List.of("shapes.cpp", "shapes.h", "plain.c", "obj.m", "broken.c").stream().map(dir::resolve).toList();
    }

    private static Path fixtures(Path dir) throws Exception {
        Checks.write(dir, "shapes.cpp", SHAPES);
        Checks.write(dir, "shapes.h", "#pragma once\ninline int hdr(int v) { return v * 2; }\n"
                + "struct Box { int w; int area() const { return w * w; } };\n"
                + "template <typename T> T thrice(T v) { return v + v + v; }\n");
        Checks.write(dir, "plain.c", "int f(void) { return 1; }\nstatic int g(int a, ...) { return a; }\n");
        Checks.write(dir, "obj.m", OBJC);
        Checks.write(dir, "broken.c", "#include \"gen/java_io_File.h\"\nint keep(int x) { return x + 1; }\n");
        return database(dir);
    }

    private static Path database(Path dir) throws Exception {
        String d = dir.toString().replace("\\", "\\\\");
        Checks.write(dir, "compile_commands.json", "[\n"
                + "{\"directory\":\"" + d + "\",\"file\":\"shapes.cpp\",\"command\":\"clang++ -std=c++17 -DFEATURE=1 -o shapes.o"
                + " -c shapes.cpp -MD -MF shapes.d\"},\n"
                + "{\"directory\":\"" + d + "\",\"file\":\"plain.c\",\"arguments\":[\"gcc\",\"-c\",\"plain.c\"]},\n"
                + "{\"directory\":\"" + d + "\",\"file\":\"obj.m\",\"arguments\":[\"clang\",\"-c\",\"obj.m\"]},\n"
                + "{\"directory\":\"" + d + "\",\"file\":\"broken.c\",\"arguments\":[\"gcc\",\"-c\",\"broken.c\"]}\n]\n");
        return dir;
    }

    private static final String SHAPES = String.join("\n",
            "#include \"shapes.h\"",
            "#define DEFINE_GET(n) int get_##n() { return n; }",
            "struct Point {",
            "  int x;",
            "  Point(int a) : x(a) {}",
            "  ~Point() {}",
            "  bool operator==(const Point& o) const { return x == o.x; }",
            "  int norm() const;",
            "};",
            "int Point::norm() const { return x * x; }",
            "DEFINE_GET(7)",
            "template <typename T> T twice(T v) { return v + v; }",
            "int use() { auto l = [](int y) { return y + 1; }; struct L { int g() { return 2; } };"
                    + " return twice(3) + (int) twice(4L) + l(1) + L().g(); }",
            "#if 0",
            "int inactive(void) { return 0; }",
            "#endif",
            "#if FEATURE",
            "int feature_on() { return 1; }",
            "#else",
            "int feature_off() { return 0; }",
            "#endif",
            "int sum(int a, int b) { int t = a + b; return t * 16; }",
            "int add(int x, int y) { int s = x + y; return s * 0x10; }",
            "int add3(int x, int y) { int s = x + y + 3; return s * 16; }",
            "int sub(int x, int y) { int s = x + y; return s * 17; }") + "\n";

    private static final String OBJC = String.join("\n", "@interface Root { int v; }", "- (int)value;",
            "- (int)add:(int)a to:(int)b;", "@end", "@implementation Root", "- (int)value { return v; }",
            "- (int)add:(int)a to:(int)b { return a + b; }", "@end") + "\n";

    private static void cpp(List<Atom> atoms) {
        Atom point = Checks.find(atoms, "shapes.cpp", "Point");
        check("TYPE Point 3-9", point != null && point.kind().equals("TYPE") && point.line() == 3 && point.endLine() == 9);
        check("constructor", line(atoms, "Point.Point(1)") == 5);
        check("destructor named ~Point", line(atoms, "Point.~Point(0)") == 6);
        check("operator==", line(atoms, "Point.operator==(1)") == 7);
        check("out-of-line member owned by its class", line(atoms, "Point.norm(0)") == 10);
        cppPreprocessor(atoms);
    }

    private static void cppPreprocessor(List<Atom> atoms) {
        Atom get = Checks.find(atoms, "shapes.cpp", "get_7(0)");
        check("macro-made function: pasted name, text is the call", get != null && get.line() == 11
                && get.exact().equals(AtomBuilder.atom("x", "METHOD", "x", "DEFINE_GET(7)").build().exact()));
        check("template once, instantiations skipped", Checks.count(atoms, "twice(1)") == 1 && line(atoms, "twice(1)") == 12);
        Set<String> symbols = atoms.stream().map(Atom::symbol).collect(Collectors.toSet());
        check("lambda and local class stay inside use()", line(atoms, "use(0)") == 13
                && symbols.stream().noneMatch(s -> s.contains("operator()") || s.equals("L") || s.endsWith(".g(0)")));
        check("#if 0 not compiled", !symbols.contains("inactive(0)"));
        check("database flag -DFEATURE=1 used", symbols.contains("feature_on(0)") && !symbols.contains("feature_off(0)"));
    }

    private static void planes(List<Atom> atoms) {
        Atom sum = Checks.find(atoms, "shapes.cpp", "sum(2)");
        Atom add = Checks.find(atoms, "shapes.cpp", "add(2)");
        Atom add3 = Checks.find(atoms, "shapes.cpp", "add3(2)");
        Atom sub = Checks.find(atoms, "shapes.cpp", "sub(2)");
        check("renamed copy (16 written as 0x10): same LOGIC and STRUCTURE, other EXACT", sum.logic().equals(add.logic())
                && sum.structure().equals(add.structure()) && !sum.exact().equals(add.exact()));
        check("an extra operand changes STRUCTURE", !sum.structure().equals(add3.structure()));
        check("another literal changes LOGIC, not STRUCTURE", !sum.logic().equals(sub.logic())
                && sum.structure().equals(sub.structure()));
    }

    private static void others(List<Atom> atoms, List<Outcome> outcomes) {
        check("header borrows the source's entry", line(atoms, "hdr(1)") == 2 && Checks.find(atoms, "shapes.h", "Box") != null
                && Checks.find(atoms, "shapes.h", "Box.area(0)") != null);
        check("a template never instantiated still has its body (no delayed template parsing)", line(atoms, "thrice(1)") == 4);
        check("C: (void) has no parameter, ... is not one", line(atoms, "f(0)") == 1 && line(atoms, "g(1)") == 2);
        check("Objective-C: interface and implementation, selectors", Checks.count(atoms, "Root") == 2
                && line(atoms, "Root.value(0)") == 6 && line(atoms, "Root.add:to:(2)") == 7);
        Outcome broken = outcomes.stream().filter(o -> o.file().endsWith("broken.c")).findFirst().orElseThrow();
        check("missing header: FATAL, lexical atoms used " + broken, broken.status() == Status.FATAL && broken.atoms() == 1
                && Checks.find(atoms, "broken.c", "keep(1)") != null);
        check("other files parsed by clang " + ClangFrontEnd.count(outcomes), ClangFrontEnd.count(outcomes).get(Status.AST) == 4);
    }

    private static void stubs(Path dir, ClangSettings configured) throws Exception {
        Path stubDir = dir.resolve("stubs");
        List<Outcome> outcomes = new ArrayList<>();
        List<Atom> atoms = new ClangFrontEnd(with(configured, Optional.of(stubDir)), outcomes::add)
                .atoms(dir, List.of(dir.resolve("broken.c")));
        check("stub header: clang parses the rest " + outcomes, outcomes.get(0).status() == Status.AST
                && Checks.find(atoms, "broken.c", "keep(1)") != null && Files.isRegularFile(stubDir.resolve("gen/java_io_File.h")));
    }

    private static void discovery() {
        List<String> names = AtomFrontEnd.all().stream().map(AtomFrontEnd::name).toList();
        check("discovered before the built-in front ends " + names, names.equals(List.of("clang", "javac", "lexical")));
    }

    private static void compare(Path dir, ClangSettings settings) throws Exception {
        FrontEndDiff.Report report = FrontEndDiff.run(dir, files(dir), settings);
        report.write(dir.resolve("diff"));
        System.err.print(report.summary());
        Set<String> classes = report.byClass().keySet();
        check("difference classes on the fixtures " + classes, classes.containsAll(List.of("OWNER_DIFFERS",
                "CLANG_ONLY_MACRO_GENERATED", "LEXICAL_ONLY_IN_CONDITIONAL", "ARITY_VOID")));
    }

    private static int line(List<Atom> atoms, String symbol) {
        return atoms.stream().filter(a -> a.symbol().equals(symbol)).mapToInt(Atom::line).findFirst().orElse(-1);
    }
}
