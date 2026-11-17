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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomBuilder;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.clang.AtomDiff.Row;

/**
 * Checks that need no clang: command splitting and rewriting, the stream reader on a hand-written dump (file
 * tracking through {@code includedFrom} and macro locations, owners of out-of-line members, skipped instantiations,
 * the planes of one walk), compilation database lookup and header borrowing, settings without clang, the lexical
 * fallback, stub headers and the difference classes.
 */
public final class ClangUnitTests {
    private static final Checks CHECKS = new Checks();

    private ClangUnitTests() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/clang-unit").toAbsolutePath();
        Files.createDirectories(dir);
        words();
        commands(dir);
        stream();
        sourceText();
        databases(dir);
        settingsAndFallback(dir);
        stubs(dir);
        differences();
        CHECKS.end("ClangUnitTests");
    }

    private static void check(String name, boolean ok) {
        CHECKS.check(name, ok);
    }

    private static void words() {
        check("posix words", CommandWords.split("gcc -DX=\"a b\" -I'/p q' a\\ b.c  -DS=\\\"s\\\"", false)
                .equals(List.of("gcc", "-DX=a b", "-I/p q", "a b.c", "-DS=\"s\"")));
        check("windows words: backslashes literal, quotes group",
                CommandWords.split("\"C:\\Program Files\\LLVM\\bin\\clang.exe\" -IC:\\x\\y -DQ=\\\"v\\\" C:\\s\\a.c", true)
                        .equals(List.of("C:\\Program Files\\LLVM\\bin\\clang.exe", "-IC:\\x\\y", "-DQ=\"v\"", "C:\\s\\a.c")));
        check("windows words: 2n backslashes before a quote", CommandWords.split("a\\\\\"b c\" d", true)
                .equals(List.of("a\\b c", "d")));
    }

    private static void commands(Path dir) {
        Path clang = Path.of("clang");
        Path src = dir.resolve("src/a.cpp");
        CompileCommand gnu = new CompileCommand(dir, src, List.of("ccache", "/usr/bin/g++", "-Iinc", "-DX=1",
                "-std=c++17", "-o", "a.o", "-c", "src/a.cpp", "-MD", "-MF", "a.d", "-MTa.o"), Optional.empty());
        List<String> g = AstCommand.of(clang, gnu, src, List.of(dir.resolve("stubs")));
        check("gnu command: launcher, outputs, dependency files and source dropped; flags kept " + g,
                g.equals(List.of("clang", "--driver-mode=g++", "-Iinc", "-DX=1", "-std=c++17", "-I" + dir.resolve("stubs"),
                        "-fsyntax-only", "-Xclang", "-ast-dump=json", "-fno-color-diagnostics", "-w", "-ferror-limit=0",
                        "-fno-delayed-template-parsing", src.toString())));
        sideOutputs(dir, clang, src);
        borrowedAndCl(dir, clang, src, gnu);
    }

    /** Options that make clang write into the build folder: -MJ (a database fragment) and -Wp,-M... (a .d file). */
    private static void sideOutputs(Path dir, Path clang, Path src) {
        CompileCommand side = new CompileCommand(dir, src, List.of("clang", "-MJ", "mj.json", "-MJjoined.json",
                "-Wp,-MMD,side.d", "-Wp,-MD,other.d", "-Wp,-D_FORTIFY_SOURCE=2", "-c", "src/a.cpp", "-o", "a.o"),
                Optional.empty());
        List<String> s = AstCommand.of(clang, side, src, List.of());
        check("side outputs: -MJ <file>, -MJ<file> and -Wp,-M... dropped; other -Wp, kept " + s,
                s.equals(List.of("clang", "-Wp,-D_FORTIFY_SOURCE=2", "-fsyntax-only", "-Xclang", "-ast-dump=json",
                        "-fno-color-diagnostics", "-w", "-ferror-limit=0", "-fno-delayed-template-parsing", src.toString())));
    }

    private static void borrowedAndCl(Path dir, Path clang, Path src, CompileCommand gnu) {
        Path header = dir.resolve("src/a.h");
        List<String> h = AstCommand.of(clang, gnu.forHeader("c-header"), header, List.of());
        check("borrowed command: donor source dropped, header language set " + h,
                h.containsAll(List.of("-x", "c-header")) && !h.contains("src/a.cpp") && h.get(h.size() - 1).equals(header.toString()));
        CompileCommand forced = new CompileCommand(dir, src, List.of("clang", "-include", src.toString(), "-DY"), Optional.empty());
        List<String> f = AstCommand.of(clang, forced, src, List.of());
        check("an option's value is never taken for the source file " + f, f.subList(1, 4).equals(List.of("-include",
                src.toString(), "-DY")));
        clCommand(dir, clang);
    }

    private static void clCommand(Path dir, Path clang) {
        CompileCommand cl = new CompileCommand(dir, dir.resolve("a.c"), List.of("C:/VS/bin/cl.exe", "/nologo", "/Iinc", "/DX",
                "/MD", "/Foobj\\a.obj", "/Fdx.pdb", "/c", "a.c"), Optional.empty());
        List<String> c = AstCommand.of(clang, cl, dir.resolve("a.c"), List.of());
        check("cl command: cl driver mode, /MD (runtime library) kept, /Fo /Fd /c dropped " + c,
                c.equals(List.of("clang", "--driver-mode=cl", "/nologo", "/Iinc", "/DX", "/MD", "/Zs", "-Xclang",
                        "-ast-dump=json", "/w", "-ferror-limit=0", "-fno-delayed-template-parsing", "--",
                        dir.resolve("a.c").toString())));
    }

    /** A hand-written dump: a header record, a header function, an out-of-line member, a macro-made function. */
    private static void stream() throws Exception {
        String text = "struct P { int norm(); };\nint P::norm() {\n  return 0x10;\n}\nGET7(7)\n";
        int begin = text.indexOf("int P::norm");
        int close = text.indexOf("}\nGET7");
        int macro = text.indexOf("GET7(7)");
        String json = DUMP.replace("@BEGIN@", "" + begin).replace("@NAME@", "" + (begin + 7))
                .replace("@BODY@", "" + text.indexOf('{', begin)).replace("@RET@", "" + text.indexOf("return"))
                .replace("@LIT@", "" + text.indexOf("0x10")).replace("@CLOSE@", "" + close).replace("@MACRO@", "" + macro);
        streamChecks(text, json, begin, close);
    }

    private static void streamChecks(String text, String json, int begin, int close) throws Exception {
        AstAtoms atoms = new AstAtoms("main.c", SourceText.of(text.getBytes(StandardCharsets.UTF_8)), "main.c"::equals);
        List<String> kept = new ArrayList<>();
        AstStream.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "main.c"::equals, atoms.records(),
                n -> {
                    kept.add(n.name);
                    atoms.visit(n);
                });
        check("stream: includedFrom does not change the file; header nodes dropped " + kept,
                kept.equals(List.of("norm", "get_7", "norm")));
        check("stream: records with owners " + atoms.records(),
                atoms.records().equals(Map.of("0x2", "Outer", "0x3", "Outer.Point")));
        streamAtoms(atoms.atoms(), text, begin, close);
    }

    private static void streamAtoms(List<Atom> atoms, String text, int begin, int close) {
        check("atoms: out-of-line member owned, instantiation skipped " + atoms.stream().map(Atom::symbol).toList(),
                atoms.size() == 2 && atoms.get(0).symbol().equals("Outer.Point.norm(0)")
                        && atoms.get(1).symbol().equals("get_7(0)"));
        Atom norm = atoms.get(0);
        Atom expected = AtomBuilder.atom("main.c", "METHOD", "x", text.substring(begin, close + 1)).declare("norm")
                .name("CXXMethodDecl", "norm").literal("type", "int ()").node("CompoundStmt").node("ReturnStmt")
                .literal("IntegerLiteral", "16").build();
        check("planes: one pre-order walk, clang's literal value", norm.logic().equals(expected.logic())
                && norm.structure().equals(expected.structure()) && norm.exact().equals(expected.exact()));
        check("lines: " + norm.line() + "-" + norm.endLine(), norm.line() == 2 && norm.endLine() == 4);
        Atom get = atoms.get(1);
        check("macro-made function: text is the macro call", get.exact().equals(
                AtomBuilder.atom("main.c", "METHOD", "x", "GET7(7)").build().exact()) && get.line() == 5);
    }

    private static final String DUMP = """
            {"id":"0x1","kind":"TranslationUnitDecl","loc":{},"range":{"begin":{},"end":{}},"inner":[
             {"id":"0x2","kind":"CXXRecordDecl","loc":{"offset":10,"file":"h.h","line":1,"col":8,"tokLen":5,
               "includedFrom":{"file":"main.c"}},
              "range":{"begin":{"offset":3,"col":1,"tokLen":6,"includedFrom":{"file":"main.c"}},
               "end":{"offset":40,"col":1,"tokLen":1,"includedFrom":{"file":"main.c"}}},
              "name":"Outer","completeDefinition":true,"inner":[
               {"id":"0x3","kind":"CXXRecordDecl","loc":{"offset":20,"col":10,"tokLen":5,"includedFrom":{"file":"main.c"}},
                "range":{"begin":{"offset":15,"tokLen":6,"includedFrom":{"file":"main.c"}},
                 "end":{"offset":35,"tokLen":1,"includedFrom":{"file":"main.c"}}},"name":"Point","completeDefinition":true}]},
             {"id":"0x4","kind":"FunctionDecl","loc":{"offset":4,"col":5,"tokLen":4,"includedFrom":{"file":"main.c"}},
              "range":{"begin":{"offset":0,"tokLen":3,"includedFrom":{"file":"main.c"}},
               "end":{"offset":20,"tokLen":1,"includedFrom":{"file":"main.c"}}},"name":"hdr",
              "inner":[{"id":"0x40","kind":"CompoundStmt","range":{"begin":{"offset":18,"tokLen":1},"end":{"offset":20,"tokLen":1}}}]},
             {"id":"0x5","kind":"CXXMethodDecl","loc":{"offset":@NAME@,"file":"main.c","line":2,"col":8,"tokLen":4},
              "range":{"begin":{"offset":@BEGIN@,"col":1,"tokLen":3},"end":{"offset":@CLOSE@,"line":4,"col":1,"tokLen":1}},
              "parentDeclContextId":"0x3","name":"norm","type":{"qualType":"int ()"},"inner":[
               {"id":"0x6","kind":"CompoundStmt","range":{"begin":{"offset":@BODY@,"tokLen":1},"end":{"offset":@CLOSE@,"tokLen":1}},
                "inner":[{"id":"0x7","kind":"ReturnStmt","range":{"begin":{"offset":@RET@,"tokLen":6},
                 "end":{"offset":@LIT@,"tokLen":4}},"inner":[{"id":"0x8","kind":"IntegerLiteral",
                 "range":{"begin":{"offset":@LIT@,"tokLen":4},"end":{"offset":@LIT@,"tokLen":4}},
                 "type":{"qualType":"int"},"valueCategory":"prvalue","value":"16"}]}]}]},
             {"id":"0x9","kind":"FunctionDecl",
              "loc":{"spellingLoc":{"offset":2,"file":"<scratch space>","line":1,"col":1,"tokLen":5},
               "expansionLoc":{"offset":@MACRO@,"file":"main.c","line":5,"col":1,"tokLen":4}},
              "range":{"begin":{"spellingLoc":{"offset":0,"file":"m.h","line":1,"col":1,"tokLen":3},
                "expansionLoc":{"offset":@MACRO@,"file":"main.c","tokLen":4}},
               "end":{"spellingLoc":{"offset":20,"file":"m.h","tokLen":1},"expansionLoc":{"offset":@MACRO@,"file":"main.c","tokLen":4}}},
              "name":"get_7","inner":[{"id":"0xa","kind":"CompoundStmt",
               "range":{"begin":{"spellingLoc":{"offset":10,"file":"m.h","tokLen":1},"expansionLoc":{"offset":@MACRO@,"file":"main.c","tokLen":4}},
                "end":{"spellingLoc":{"offset":20,"file":"m.h","tokLen":1},"expansionLoc":{"offset":@MACRO@,"file":"main.c","tokLen":4}}}}]},
             {"id":"0xb","kind":"FunctionDecl","loc":{"offset":@NAME@,"tokLen":4},
              "range":{"begin":{"offset":@BEGIN@,"tokLen":3},"end":{"offset":@CLOSE@,"tokLen":1}},"name":"norm",
              "TemplateInstantiationPattern":"0x5","inner":[{"id":"0xc","kind":"CompoundStmt",
               "range":{"begin":{"offset":@BODY@,"tokLen":1},"end":{"offset":@CLOSE@,"tokLen":1}}}]}
            ]}
            """;

    private static void sourceText() {
        SourceText t = SourceText.of("a\nM(x, (y), \")\")+1\n".getBytes(StandardCharsets.UTF_8));
        check("source text: lines", t.line(0) == 1 && t.line(1) == 1 && t.line(2) == 2);
        check("source text: macro call end skips nested parentheses and strings", t.macroCallEnd(3) == 16);
        check("source text: no argument list", SourceText.of("M + 1".getBytes(StandardCharsets.UTF_8)).macroCallEnd(1) == 1);
    }

    private static void databases(Path dir) throws Exception {
        Path project = dir.resolve("db");
        Checks.write(project, "src/foo.c", "int foo(void) { return 0; }\n");
        Checks.write(project, "src/bar.cpp", "int bar() { return 0; }\n");
        Checks.write(project, "src/foo.h", "int foo(void);\n");
        Checks.write(project, "src/deep/z.hpp", "int z();\n");
        String root = project.toString().replace('\\', '/');
        Checks.write(project, "build/compile_commands.json", "[{\"directory\":\"" + root + "/src\",\"file\":\"foo.c\","
                + "\"command\":\"gcc -DFOO -c foo.c\"},{\"directory\":\"" + root + "\",\"file\":\"src/bar.cpp\","
                + "\"arguments\":[\"g++\",\"-DBAR\",\"-c\",\"src/bar.cpp\"]}]");
        lookups(project);
    }

    private static void lookups(Path project) {
        CompileDatabases dbs = new CompileDatabases(List.of(), project.getFileSystem().getSeparator().equals("\\"), m -> { });
        Optional<CompileCommand> foo = dbs.forFile(project.resolve("src/foo.c"));
        check("database found in build/ of a parent folder; command string split",
                foo.isPresent() && foo.get().arguments().equals(List.of("gcc", "-DFOO", "-c", "foo.c")));
        Optional<CompileCommand> header = dbs.forFile(project.resolve("src/foo.h"));
        check("header borrows the same-stem source, parsed as a C header", header.isPresent()
                && header.get().file().endsWith(Path.of("src/foo.c")) && header.get().headerLanguage().equals(Optional.of("c-header")));
        Optional<CompileCommand> hpp = dbs.forFile(project.resolve("src/deep/z.hpp"));
        check("a .hpp header is parsed as C++", hpp.isPresent() && hpp.get().headerLanguage().equals(Optional.of("c++-header")));
    }

    private static void settingsAndFallback(Path dir) throws Exception {
        Map<String, String> props = new HashMap<>(Map.of("clustering.clang.executable", dir.resolve("none/clang").toString()));
        ClangSettings missing = ClangSettings.from(props::get, k -> null);
        check("settings: a missing clang gives no executable", missing.clang().isEmpty());
        check("front end without clang takes no file", !new ClangFrontEnd(missing, o -> { }).accepts(Path.of("a.c")));
        ClangSettings off = ClangSettings.from(Map.of("clustering.clang.enabled", "false")::get, k -> null);
        check("settings: disabled", off.clang().isEmpty());
        Path src = Checks.write(dir, "fb/a.c", "int one(int x) { return x; }\n");
        List<Atom> scanned = AtomPlanes.scan(List.of(dir.resolve("fb")), List.of(new ClangFrontEnd(missing, o -> { }),
                new AtomFrontEnd.LexicalFrontEnd()));
        check("without clang the lexical front end atomizes", scanned.size() == 1 && scanned.get(0).symbol().equals("one(1)"));
        fallback(dir, src);
    }

    private static void fallback(Path dir, Path src) throws Exception {
        Path notClang = Checks.write(dir, "fb/not-clang.txt", "x");
        ClangSettings broken = new ClangSettings(Optional.of(notClang), List.of(), List.of("-DX"), Optional.empty(), 1,
                Duration.ofSeconds(30), true);
        List<ClangFrontEnd.Outcome> outcomes = new ArrayList<>();
        List<Atom> fallback = new ClangFrontEnd(broken, outcomes::add).atoms(dir.resolve("fb"), List.of(src));
        check("a clang that cannot run: lexical atoms, FAILED outcome " + outcomes, fallback.size() == 1
                && outcomes.size() == 1 && outcomes.get(0).status() == ClangRun.Status.FAILED);
        ClangSettings noFlags = new ClangSettings(Optional.of(notClang), List.of(), List.of(), Optional.empty(), 1,
                Duration.ofSeconds(30), true);
        outcomes.clear();
        new ClangFrontEnd(noFlags, outcomes::add).atoms(dir.resolve("fb"), List.of(src));
        check("no database and no flags: NO_COMMAND", outcomes.get(0).status() == ClangRun.Status.NO_COMMAND);
    }

    private static void stubs(Path dir) throws Exception {
        String errors = "a.c:1:10: fatal error: 'gen/java_io_File.h' file not found\n";
        check("missing header parsed", MissingIncludes.missing(errors).equals(Optional.of("gen/java_io_File.h")));
        Path stubs = dir.resolve("stubs");
        check("stub written", MissingIncludes.stub(stubs, "gen/java_io_File.h") && Files.isRegularFile(stubs.resolve("gen/java_io_File.h")));
        check("unsafe names refused", !MissingIncludes.stub(stubs, "../x.h") && !MissingIncludes.stub(stubs,
                dir.resolve("abs.h").toString()));
    }

    private static final String DIFF_SOURCE = String.join("\n", "#if 0", "int old(void) { return 0; }", "#endif",
            "template <typename T>", "T twice(T v) { return v + v; }", "Point::~Point() {}", "int f(void) { return 1; }",
            "int Point::norm() const { return 1; }", "DEFINE_GET(7)",
            "int outer() { struct L { int g() { return 2; } }; return 0; }", "int same(int a) { return a; }", "MAKE(x)") + "\n";

    private static void differences() {
        List<Row> rows = AtomDiff.compare(diffClang(), diffLexical(), p -> SourceLines.of(DIFF_SOURCE));
        Map<String, Long> classes = rows.stream().collect(Collectors.groupingBy(Row::diffClass, Collectors.counting()));
        Map<String, Long> expected = Map.of("SAME", 1L, "TEXT_DIFFERS", 1L, "START_TEMPLATE_HEADER", 1L, "NAME_DESTRUCTOR", 1L,
                "ARITY_VOID", 1L, "OWNER_DIFFERS", 1L, "NAME_MACRO_PASTED", 1L, "CLANG_ONLY_MACRO_GENERATED", 1L,
                "LEXICAL_ONLY_IN_CONDITIONAL", 1L, "LEXICAL_ONLY_NESTED_IN_FUNCTION", 1L);
        check("difference classes " + classes, classes.equals(expected));
        moreDifferences();
    }

    private static List<Atom> diffClang() {
        return List.of(a("METHOD", "twice(1)", 5, 5, "c"), a("METHOD", "Point.~Point(0)", 6, 6, "c"),
                a("METHOD", "f(0)", 7, 7, "c"), a("METHOD", "Point.norm(0)", 8, 8, "c"), a("METHOD", "get_7(0)", 9, 9, "c"),
                a("METHOD", "outer(0)", 10, 10, "same"), a("METHOD", "same(1)", 11, 11, "c"), a("METHOD", "gen_x(0)", 12, 12, "c"));
    }

    private static List<Atom> diffLexical() {
        return List.of(a("METHOD", "old(1)", 2, 2, "l"), a("METHOD", "twice(1)", 4, 5, "l"),
                a("METHOD", "Point(0)", 6, 6, "l"), a("METHOD", "f(1)", 7, 7, "l"), a("METHOD", "norm(0)", 8, 8, "l"),
                a("METHOD", "DEFINE_GET(1)", 9, 9, "l"), a("METHOD", "outer(0)", 10, 10, "same"), a("TYPE", "L", 10, 10, "l"),
                a("METHOD", "same(1)", 11, 11, "l"));
    }

    private static void moreDifferences() {
        check("symbol split: operator names keep their dots and parentheses", AtomDiff.Symbol.of(a("METHOD",
                "Point.operator()(2)", 1, 1, "x")).equals(new AtomDiff.Symbol("Point", "operator()", 2)));
        check("whole-word names: JNI_OnLoad is not in DEF_JNI_OnLoad", !AtomDiff.mentions("DEF_JNI_OnLoad(vm)", "JNI_OnLoad")
                && AtomDiff.mentions("jint JNI_OnLoad(vm)", "JNI_OnLoad"));
        List<Row> access = AtomDiff.compare(List.of(a("METHOD", "C.C(0)", 2, 2, "c")), List.of(a("METHOD", "C.C(0)", 1, 2, "l")),
                p -> SourceLines.of("protected:\n  C() {}\n"));
        check("access specifier before a member", access.size() == 1 && access.get(0).diffClass().equals("START_ACCESS_SPECIFIER"));
        List<Row> export = AtomDiff.compare(List.of(a("TYPE", "SkM", 1, 3, "c"), a("METHOD", "SkM.get(0)", 2, 2, "c")),
                List.of(a("TYPE", "SK_API", 1, 3, "l"), a("METHOD", "SK_API.get(0)", 2, 2, "l")),
                p -> SourceLines.of("class SK_API SkM {\n  int get() { return 1; }\n};\n"));
        check("export macro taken for the class name " + export, export.stream().map(Row::diffClass).toList()
                .equals(List.of("OWNER_EXPORT_MACRO", "NAME_EXPORT_MACRO")));
    }

    private static Atom a(String kind, String symbol, int line, int endLine, String text) {
        return AtomBuilder.atom("a.cpp", kind, symbol, text).node("X").lines(line, endLine).build();
    }
}
