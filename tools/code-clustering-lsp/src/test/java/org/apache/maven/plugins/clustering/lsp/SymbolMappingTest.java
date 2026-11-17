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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Per-language symbol mapping with LSP4J symbol objects: the symbol trees are the ones the real servers returned for
 * the {@link Fixtures} (JDT LS, clangd, pylsp, rust-analyzer, LemMinX, and the HTML, CSS and JSON servers), with
 * their ranges (lines written 1-based here, as the probes printed them).
 */
public final class SymbolMappingTest {
    private static final Checks CHECKS = new Checks("symbol mapping");

    private SymbolMappingTest() { }

    public static void main(String[] args) {
        kinds();
        code();
        markup();
        style();
        data();
        CHECKS.finish();
    }

    private static void kinds() {
        CHECKS.check("languages: xml/html markup, css/scss/less style, json data, others code",
                SymbolMapping.forLanguage("xml") == SymbolMapping.MARKUP && SymbolMapping.forLanguage("html") == SymbolMapping.MARKUP
                && SymbolMapping.forLanguage("scss") == SymbolMapping.STYLE && SymbolMapping.forLanguage("json") == SymbolMapping.DATA
                && List.of("java", "c", "cpp", "python", "rust", "javascript").stream()
                        .allMatch(l -> SymbolMapping.forLanguage(l) == SymbolMapping.CODE));
        CHECKS.check("code never asks for children", SymbolMapping.CODE.kind(SymbolKind.Class, SymbolMappingTest::never)
                .equals(Optional.of("TYPE")) && SymbolMapping.CODE.kind(SymbolKind.Field, SymbolMappingTest::never).isEmpty());
        CHECKS.check("markup: element with children TYPE, leaf element and Property not atoms",
                SymbolMapping.MARKUP.kind(SymbolKind.Field, () -> true).equals(Optional.of("TYPE"))
                && SymbolMapping.MARKUP.kind(SymbolKind.Field, () -> false).isEmpty()
                && SymbolMapping.MARKUP.kind(SymbolKind.Property, () -> true).isEmpty());
        CHECKS.check("data: object with members TYPE, array and empty object not atoms",
                SymbolMapping.DATA.kind(SymbolKind.Module, () -> true).equals(Optional.of("TYPE"))
                && SymbolMapping.DATA.kind(SymbolKind.Array, () -> true).isEmpty()
                && SymbolMapping.DATA.kind(SymbolKind.Module, () -> false).isEmpty());
        CHECKS.check("style: rule TYPE with or without children, at-rule not an atom",
                SymbolMapping.STYLE.kind(SymbolKind.Class, SymbolMappingTest::never).equals(Optional.of("TYPE"))
                && SymbolMapping.STYLE.kind(SymbolKind.Module, SymbolMappingTest::never).isEmpty());
    }

    /** Code keeps the mapping it had: these are the atoms the plugin produced from the same servers before. */
    private static void code() {
        List<String> jdt = names(Fixtures.JAVA, SymbolMapping.CODE, sym("demo", SymbolKind.Package, 1, 0, 1, 13),
                sym("Calc", SymbolKind.Class, 3, 0, 27, 1, sym("total", SymbolKind.Field, 4, 4, 4, 22),
                        sym("Calc(int)", SymbolKind.Constructor, 6, 4, 8, 5), sym("sum(int[])", SymbolKind.Method, 10, 4, 14, 5),
                        sym("add(int[])", SymbolKind.Method, 16, 4, 20, 5),
                        sym("Op", SymbolKind.Interface, 22, 4, 24, 5, sym("apply(int, int)", SymbolKind.Method, 23, 8, 23, 32)),
                        sym("Mode", SymbolKind.Enum, 26, 4, 26, 28, sym("FAST", SymbolKind.EnumMember, 26, 16, 26, 20))));
        CHECKS.check("jdt " + jdt, jdt.equals(List.of("TYPE#Calc", "METHOD#Calc.Calc(1)", "METHOD#Calc.sum(1)",
                "METHOD#Calc.add(1)", "TYPE#Calc.Op", "METHOD#Calc.Op.apply(2)", "TYPE#Calc.Mode")));
        List<String> cdt = names(Fixtures.CPP, SymbolMapping.CODE, sym("geo", SymbolKind.Namespace, 3, 0, 25, 1,
                sym("Point", SymbolKind.Class, 4, 0, 8, 1, sym("x", SymbolKind.Field, 5, 4, 5, 12),
                        sym("norm", SymbolKind.Method, 7, 4, 7, 23)),
                sym("Point::norm", SymbolKind.Method, 10, 0, 12, 1), sym("dist", SymbolKind.Function, 14, 0, 18, 1),
                sym("distance", SymbolKind.Function, 20, 0, 24, 1)));
        CHECKS.check("cdt " + cdt, cdt.equals(List.of("TYPE#Point", "METHOD#Point.norm(0)", "METHOD#Point.norm(0)",
                "METHOD#dist(2)", "METHOD#distance(2)")));
        List<String> rust = names(Fixtures.RUST, SymbolMapping.CODE,
                sym("Point", SymbolKind.Struct, 1, 0, 4, 1, sym("x", SymbolKind.Field, 2, 4, 2, 14)),
                sym("impl Point", SymbolKind.Object, 6, 0, 14, 1, sym("new", SymbolKind.Function, 7, 4, 9, 5),
                        sym("norm", SymbolKind.Method, 11, 4, 13, 5)),
                sym("dist", SymbolKind.Function, 16, 0, 20, 1), sym("distance", SymbolKind.Function, 22, 0, 26, 1),
                sym("Shape", SymbolKind.Interface, 28, 0, 30, 1, sym("area", SymbolKind.Method, 29, 4, 29, 26)),
                sym("Mode", SymbolKind.Enum, 32, 0, 35, 1, sym("Fast", SymbolKind.EnumMember, 33, 4, 33, 8)));
        CHECKS.check("rust " + rust, rust.equals(List.of("TYPE#Point", "TYPE#impl Point", "METHOD#impl Point.new(2)",
                "METHOD#impl Point.norm(1)", "METHOD#dist(2)", "METHOD#distance(2)", "TYPE#Shape", "METHOD#Shape.area(1)",
                "TYPE#Mode")));
        List<String> pydev = flatNames(Fixtures.PYTHON, SymbolMapping.CODE, true, flat("LIMIT", null, SymbolKind.Variable, 1, 0, 1, 10),
                flat("Shape", null, SymbolKind.Class, 4, 0, 11, 0), flat("__init__", "Shape", SymbolKind.Method, 5, 4, 8, 0),
                flat("area", "Shape", SymbolKind.Method, 9, 4, 11, 0), flat("width", "__init__", SymbolKind.Field, 6, 8, 6, 26),
                flat("total", null, SymbolKind.Function, 13, 0, 18, 0), flat("sum_all", null, SymbolKind.Function, 20, 0, 25, 0));
        CHECKS.check("pydev (flat pylsp form) " + pydev, pydev.equals(List.of("TYPE#Shape", "METHOD#Shape.__init__(3)",
                "METHOD#Shape.area(1)", "METHOD#total(1)", "METHOD#sum_all(1)")));
        languages();
    }

    /** gopls 0.24.0, typescript-language-server 6.1.0 (TypeScript 6.0.3) and csharp-ls 0.28.0 on the new fixtures. */
    private static void languages() {
        List<String> go = names(Fixtures.GO, SymbolMapping.CODE,
                sym("Point", SymbolKind.Struct, 5, 5, 8, 1, sym("X", SymbolKind.Field, 6, 4, 6, 13)),
                sym("(Point).Norm", SymbolKind.Method, 10, 0, 12, 1), sym("Dist", SymbolKind.Function, 14, 0, 18, 1),
                sym("Distance", SymbolKind.Function, 20, 0, 24, 1),
                sym("Shape", SymbolKind.Interface, 26, 5, 28, 1, sym("Area", SymbolKind.Method, 27, 4, 27, 18)));
        CHECKS.check("go (gopls): a method is owned by its receiver type " + go, go.equals(List.of("TYPE#Point",
                "METHOD#Point.Norm(0)", "METHOD#Dist(2)", "METHOD#Distance(2)", "TYPE#Shape", "METHOD#Shape.Area(0)")));
        String pointer = "func (l *List[T]) Push(v T) {\n}\nfunc (p *Point) Scale(k float64) {\n}\n";
        List<String> receivers = names(pointer, SymbolMapping.CODE, sym("(*List[T]).Push", SymbolKind.Method, 1, 0, 2, 1),
                sym("(*Point).Scale", SymbolKind.Method, 3, 0, 4, 1));
        CHECKS.check("go: pointer and generic receivers " + receivers, receivers.equals(List.of("METHOD#List.Push(1)",
                "METHOD#Point.Scale(1)")));
        List<String> ts = names(Fixtures.TYPESCRIPT, SymbolMapping.CODE,
                sym("dist", SymbolKind.Function, 9, 0, 13, 1, sym("dx", SymbolKind.Constant, 10, 10, 10, 24)),
                sym("distance", SymbolKind.Function, 15, 0, 19, 1),
                sym("Mode", SymbolKind.Enum, 25, 0, 28, 1, sym("Fast", SymbolKind.Constant, 26, 4, 26, 8)),
                sym("Point", SymbolKind.Class, 1, 0, 7, 1, sym("constructor", SymbolKind.Constructor, 2, 4, 2, 54),
                        sym("norm", SymbolKind.Method, 4, 4, 6, 5), sym("x", SymbolKind.Property, 2, 16, 2, 32)),
                sym("Shape", SymbolKind.Interface, 21, 0, 23, 1, sym("area", SymbolKind.Method, 22, 4, 22, 19)));
        CHECKS.check("typescript (typescript-language-server) " + ts, ts.equals(List.of("METHOD#dist(2)", "METHOD#distance(2)",
                "TYPE#Mode", "TYPE#Point", "METHOD#Point.constructor(2)", "METHOD#Point.norm(0)", "TYPE#Shape",
                "METHOD#Shape.area(0)")));
        List<String> cs = names(Fixtures.CSHARP, SymbolMapping.CODE, sym("Calc.cs", SymbolKind.File, 1, 0, 33, 0,
                sym("Demo", SymbolKind.Namespace, 1, 0, 33, 0, sym("Calc", SymbolKind.Class, 2, 0, 33, 0,
                        sym("total", SymbolKind.Field, 5, 16, 5, 21), sym("Calc(int start)", SymbolKind.Constructor, 6, 0, 11, 0),
                        sym("Sum(int[] values)", SymbolKind.Method, 11, 0, 18, 0),
                        sym("Add(int[] items)", SymbolKind.Method, 18, 0, 25, 0),
                        sym("IOp", SymbolKind.Interface, 25, 0, 30, 0, sym("Apply(int a, int b)", SymbolKind.Method, 28, 0, 29, 0)),
                        sym("Mode", SymbolKind.Enum, 30, 0, 32, 0, sym("Fast", SymbolKind.Field, 31, 23, 31, 27))))));
        CHECKS.check("csharp (csharp-ls): File and Namespace symbols are not atoms, their children are " + cs,
                cs.equals(List.of("TYPE#Calc", "METHOD#Calc.Calc(1)", "METHOD#Calc.Sum(1)", "METHOD#Calc.Add(1)",
                        "TYPE#Calc.IOp", "METHOD#Calc.IOp.Apply(2)", "TYPE#Calc.Mode")));
    }

    private static void markup() {
        List<Atom> xml = SymbolAtoms.atoms(List.of(Either.forRight(sym("xml", SymbolKind.Property, 1, 0, 1, 38)),
                Either.forRight(sym("project", SymbolKind.Field, 2, 0, 15, 10, sym("name", SymbolKind.Field, 3, 2, 3, 19),
                        sym("dependencies", SymbolKind.Field, 4, 2, 9, 17, sym("dependency", SymbolKind.Field, 5, 4, 8, 17,
                                sym("groupId", SymbolKind.Field, 6, 6, 6, 36), sym("artifactId", SymbolKind.Field, 7, 6, 7, 34))),
                        sym("build", SymbolKind.Field, 10, 2, 14, 10, sym("plugins", SymbolKind.Field, 11, 4, 13, 14,
                                sym("plugin", SymbolKind.Field, 12, 6, 12, 23)))))), "p.xml", Fixtures.XML, false, SymbolMapping.MARKUP);
        CHECKS.check("wtp xml (LemMinX): elements with children " + Checks.names(xml), Checks.names(xml).equals(List.of(
                "TYPE#project", "TYPE#project.dependencies", "TYPE#project.dependencies.dependency", "TYPE#project.build",
                "TYPE#project.build.plugins")));
        CHECKS.check("wtp xml lines: dependency 5-8, plugins 11-13", xml.get(2).line() == 5 && xml.get(2).endLine() == 8
                && xml.get(4).line() == 11 && xml.get(4).endLine() == 13);
        List<String> html = flatNames(Fixtures.HTML, SymbolMapping.MARKUP, false,
                flat("greet", null, SymbolKind.Function, 14, 4, 14, 49), flat("html", "", SymbolKind.Field, 2, 0, 17, 7),
                flat("head", "html", SymbolKind.Field, 3, 0, 5, 7), flat("title", "head", SymbolKind.Field, 4, 2, 4, 21),
                flat("body", "html", SymbolKind.Field, 6, 0, 16, 7), flat("div#main.card", "body", SymbolKind.Field, 7, 2, 12, 8),
                flat("ul", "div#main.card", SymbolKind.Field, 8, 4, 11, 9), flat("li", "ul", SymbolKind.Field, 9, 6, 9, 18),
                flat("li", "ul", SymbolKind.Field, 10, 6, 10, 18), flat("script", "body", SymbolKind.Field, 13, 2, 15, 11));
        CHECKS.check("wtp html (flat form): containing elements, script function, names kept " + html, html.equals(List.of(
                "METHOD#greet(1)", "TYPE#html", "TYPE#html.head", "TYPE#html.body", "TYPE#body.div#main.card",
                "TYPE#div#main.card.ul", "TYPE#body.script")));
    }

    private static void style() {
        List<String> css = names(Fixtures.CSS, SymbolMapping.STYLE, sym("body", SymbolKind.Class, 1, 0, 4, 1),
                sym(".card", SymbolKind.Class, 6, 0, 8, 1), sym("@media (max-width: 600px)", SymbolKind.Module, 10, 0, 14, 1,
                        sym(".card .title", SymbolKind.Class, 11, 2, 13, 3)));
        CHECKS.check("wtp css: every rule, selectors kept " + css, css.equals(List.of("TYPE#body", "TYPE#.card",
                "TYPE#.card .title")));
        List<String> asCode = names(Fixtures.CSS, SymbolMapping.CODE, sym(".card", SymbolKind.Class, 6, 0, 8, 1));
        CHECKS.check("discriminator: the code mapping would split '.card' as a qualified name " + asCode,
                asCode.equals(List.of("TYPE#card")));
    }

    private static void data() {
        List<String> json = names(Fixtures.JSON, SymbolMapping.DATA, sym("name", SymbolKind.String, 2, 2, 2, 16),
                sym("version", SymbolKind.String, 3, 2, 3, 20), sym("scripts", SymbolKind.Module, 4, 2, 7, 3,
                        sym("build", SymbolKind.String, 5, 4, 5, 18), sym("test", SymbolKind.String, 6, 4, 6, 26)),
                sym("dependencies", SymbolKind.Module, 8, 2, 10, 3, sym("left-pad", SymbolKind.String, 9, 4, 9, 23)),
                sym("files", SymbolKind.Array, 11, 2, 11, 26, sym("0", SymbolKind.String, 11, 12, 11, 18)));
        CHECKS.check("wtp json: objects with members " + json, json.equals(List.of("TYPE#scripts", "TYPE#dependencies")));
    }

    private static List<String> names(String text, SymbolMapping mapping, DocumentSymbol... symbols) {
        List<Either<SymbolInformation, DocumentSymbol>> list = Arrays.stream(symbols)
                .<Either<SymbolInformation, DocumentSymbol>>map(Either::forRight).toList();
        return Checks.names(SymbolAtoms.atoms(list, "f", text, false, mapping));
    }

    private static List<String> flatNames(String text, SymbolMapping mapping, boolean indented, SymbolInformation... symbols) {
        List<Either<SymbolInformation, DocumentSymbol>> list = Arrays.stream(symbols)
                .<Either<SymbolInformation, DocumentSymbol>>map(Either::forLeft).toList();
        return Checks.names(SymbolAtoms.atoms(list, "f", text, indented, mapping));
    }

    private static DocumentSymbol sym(String name, SymbolKind kind, int line, int col, int endLine, int endCol,
            DocumentSymbol... children) {
        Range range = range(line, col, endLine, endCol);
        DocumentSymbol symbol = new DocumentSymbol(name, kind, range, range);
        if (children.length > 0) symbol.setChildren(List.of(children));
        return symbol;
    }

    @SuppressWarnings("deprecation")
    private static SymbolInformation flat(String name, String container, SymbolKind kind, int line, int col, int endLine,
            int endCol) {
        return new SymbolInformation(name, kind, new Location("file:///f", range(line, col, endLine, endCol)), container);
    }

    private static Range range(int line, int col, int endLine, int endCol) {
        return new Range(new Position(line - 1, col), new Position(endLine - 1, endCol));
    }

    private static boolean never() {
        throw new AssertionError("children asked for");
    }
}
