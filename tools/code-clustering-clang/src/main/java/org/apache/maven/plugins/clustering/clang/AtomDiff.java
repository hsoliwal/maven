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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Compares two front ends' atoms of the same files (clang's against the lexical front end's) and puts every
 * difference into a class. Pairs are made per file in passes, each pass on what the earlier ones left:
 * <ol>
 *   <li>same kind, symbol and lines: {@code SAME}, {@code TEXT_EXTERN_C_PREFIX} (the lexical atom starts at
 *       {@code extern "C"}, clang's declaration after it), or {@code TEXT_DIFFERS} when the exact text differs
 *       otherwise (the atoms start or end in another column);</li>
 *   <li>same kind, name and last line: {@code OWNER_DIFFERS} (for example a member defined outside its class, which
 *       clang qualifies), {@code OWNER_EXPORT_MACRO} (the lexical owner is an export macro, as in
 *       {@code class SK_API SkMatrix}), {@code ARITY_VOID} ({@code f(void)} has no parameter),
 *       {@code ARITY_VARIADIC} ({@code ...} is not a parameter), {@code ARITY_OTHER}, {@code START_PREPROCESSOR_LINES}
 *       (the lexical atom takes in directive lines before the definition, such as a {@code #define} with
 *       continuation lines), {@code START_TEMPLATE_HEADER} (it includes {@code template <...>}),
 *       {@code START_ACCESS_SPECIFIER} (it includes a {@code public:} label), {@code START_LEXICAL_EARLIER} or
 *       {@code START_CLANG_EARLIER};</li>
 *   <li>same kind and last line, another name: {@code NAME_DESTRUCTOR}, {@code NAME_OPERATOR},
 *       {@code NAME_MACRO_PASTED} (clang's name is not in the text: a macro made it), {@code NAME_MACRO_WRAPPER}
 *       (the lexical name is a macro's), {@code NAME_EXPORT_MACRO} (the lexical name is the export macro between
 *       {@code class} and the name), {@code NAME_DIRECTIVE_IN_HEADER} (the lexical atom starts on a preprocessor
 *       line, for example {@code #if defined(X)}), {@code NAME_OTHER};</li>
 *   <li>same kind and name, overlapping lines: {@code RANGE_DIFFERS};</li>
 *   <li>same last line, another kind: {@code KIND_DIFFERS_DIRECTIVE_IN_HEADER} (the lexical atom starts on a
 *       preprocessor line, whose tokens it read as code) or {@code KIND_DIFFERS}.</li>
 * </ol>
 * Atoms left over are {@code CLANG_ONLY_*} ({@code MACRO_GENERATED}, {@code DESTRUCTOR}, {@code OPERATOR},
 * {@code OBJC}, {@code IN_CONDITIONAL}, {@code SPANS_CONDITIONAL}: an {@code #if} block opens or closes inside
 * it, {@code ENUM}, {@code TYPE}, {@code OTHER}) or {@code LEXICAL_ONLY_*}
 * ({@code NESTED_IN_FUNCTION}: inside a function clang reports as one atom, such as a local class or a lambda;
 * {@code IN_CONDITIONAL}: inside an {@code #if} region, typically the branch this configuration does not compile;
 * {@code MACRO_CALL}: a block after a macro call; {@code DIRECTIVE_IN_HEADER}; {@code SPANS_CONDITIONAL};
 * {@code OTHER}).
 */
final class AtomDiff {
    /** One compared pair, or a lone atom (the other side null). */
    record Row(String diffClass, String path, Atom clang, Atom lexical) { }

    /** A symbol split as the front ends write it: {@code Owner.name(arity)}. */
    record Symbol(String owner, String name, int arity) {
        static Symbol of(Atom atom) {
            String s = atom.symbol();
            int arity = -1;
            int open = s.lastIndexOf('(');
            if ("METHOD".equals(atom.kind()) && s.endsWith(")") && open > 0) {
                arity = parse(s.substring(open + 1, s.length() - 1));
                s = s.substring(0, open);
            }
            int dot = s.lastIndexOf('.', s.contains("operator") ? s.indexOf("operator") : s.length());
            return new Symbol(dot < 0 ? "" : s.substring(0, dot), s.substring(dot + 1), arity);
        }

        private static int parse(String digits) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException notArity) {
                return -1;
            }
        }
    }

    private AtomDiff() { }

    /** Rows for every file of either list, in path order; {@code sources} gives each path's lines. */
    static List<Row> compare(List<Atom> clang, List<Atom> lexical, Function<String, SourceLines> sources) {
        Map<String, List<Atom>> c = byPath(clang);
        Map<String, List<Atom>> l = byPath(lexical);
        TreeSet<String> paths = new TreeSet<>(c.keySet());
        paths.addAll(l.keySet());
        List<Row> rows = new ArrayList<>();
        paths.forEach(p -> rows.addAll(file(p, c.getOrDefault(p, List.of()), l.getOrDefault(p, List.of()), sources.apply(p))));
        return rows;
    }

    private static Map<String, List<Atom>> byPath(List<Atom> atoms) {
        return atoms.stream().collect(Collectors.groupingBy(Atom::path, TreeMap::new, Collectors.toList()));
    }

    private static List<Row> file(String path, List<Atom> clang, List<Atom> lexical, SourceLines lines) {
        List<Atom> c = new ArrayList<>(clang);
        List<Atom> l = new ArrayList<>(lexical);
        List<Row> rows = new ArrayList<>();
        passes(path, c, l, rows, lines);
        c.forEach(a -> rows.add(new Row("CLANG_ONLY_" + clangOnly(a, lines), path, a, null)));
        l.forEach(b -> rows.add(new Row("LEXICAL_ONLY_" + lexicalOnly(b, clang, lines), path, null, b)));
        return rows;
    }

    private static void passes(String path, List<Atom> c, List<Atom> l, List<Row> rows, SourceLines lines) {
        pair(path, c, l, rows, (a, b) -> a.kind().equals(b.kind()) && a.symbol().equals(b.symbol()) && sameLines(a, b),
                (a, b) -> a.exact().equals(b.exact()) ? "SAME" : textClass(lines, b));
        pair(path, c, l, rows, (a, b) -> a.kind().equals(b.kind()) && a.endLine() == b.endLine() && sameName(a, b),
                (a, b) -> sameNameClass(a, b, lines));
        pair(path, c, l, rows, (a, b) -> a.kind().equals(b.kind()) && a.endLine() == b.endLine(),
                (a, b) -> renamedClass(a, b, lines));
        pair(path, c, l, rows, (a, b) -> a.kind().equals(b.kind()) && sameName(a, b) && overlap(a, b), (a, b) -> "RANGE_DIFFERS");
        pair(path, c, l, rows, (a, b) -> !a.kind().equals(b.kind()) && a.endLine() == b.endLine(),
                (a, b) -> directive(lines, b) ? "KIND_DIFFERS_DIRECTIVE_IN_HEADER" : "KIND_DIFFERS");
    }

    /** Same symbol and lines, other text: {@code extern "C"} before the lexical atom's header, or other columns. */
    private static String textClass(SourceLines lines, Atom lexical) {
        return lines.line(lexical.line()).strip().startsWith("extern \"C\"") ? "TEXT_EXTERN_C_PREFIX" : "TEXT_DIFFERS";
    }

    private static void pair(String path, List<Atom> c, List<Atom> l, List<Row> rows, BiPredicate<Atom, Atom> match,
            BiFunction<Atom, Atom, String> classify) {
        for (Iterator<Atom> it = c.iterator(); it.hasNext();) {
            Atom a = it.next();
            Atom b = l.stream().filter(x -> match.test(a, x)).findFirst().orElse(null);
            if (b == null) continue;
            it.remove();
            l.remove(b);
            rows.add(new Row(classify.apply(a, b), path, a, b));
        }
    }

    private static boolean sameLines(Atom a, Atom b) {
        return a.line() == b.line() && a.endLine() == b.endLine();
    }

    private static boolean sameName(Atom a, Atom b) {
        return Symbol.of(a).name().equals(Symbol.of(b).name());
    }

    private static boolean overlap(Atom a, Atom b) {
        return a.line() <= b.endLine() && b.line() <= a.endLine();
    }

    private static String sameNameClass(Atom c, Atom l, SourceLines lines) {
        Symbol sc = Symbol.of(c);
        Symbol sl = Symbol.of(l);
        if (!sc.owner().equals(sl.owner())) return macroOwner(sc.owner(), sl.owner()) ? "OWNER_EXPORT_MACRO" : "OWNER_DIFFERS";
        if (sc.arity() != sl.arity()) return arityClass(sc, sl, lines.text(l.line(), l.endLine()));
        if (l.line() < c.line()) return startClass(lines, l.line(), c.line() - 1);
        return l.line() > c.line() ? "START_CLANG_EARLIER" : "TEXT_DIFFERS";
    }

    private static String arityClass(Symbol clang, Symbol lexical, String text) {
        if (clang.arity() == 0 && lexical.arity() == 1 && text.contains("(void)")) return "ARITY_VOID";
        return text.contains("...") ? "ARITY_VARIADIC" : "ARITY_OTHER";
    }

    /** Lines the lexical atom has before clang's: preprocessor lines, a template header, or other. */
    private static String startClass(SourceLines lines, int from, int to) {
        String extra = lines.text(from, to);
        boolean directives = extra.lines().map(String::strip).anyMatch(line -> line.startsWith("#"));
        if (directives || extra.lines().map(String::strip).allMatch(AtomDiff::directiveOrComment)) return "START_PREPROCESSOR_LINES";
        if (extra.contains("template")) return "START_TEMPLATE_HEADER";
        return extra.strip().matches("(?s).*\\b(public|protected|private)\\s*:") ? "START_ACCESS_SPECIFIER"
                : "START_LEXICAL_EARLIER";
    }

    /** True when the text has the name as a whole identifier, not inside a longer one ({@code DEF_JNI_OnLoad}). */
    static boolean mentions(String text, String name) {
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
            boolean before = at > 0 && isIdentifier(text.charAt(at - 1));
            int end = at + name.length();
            if (!before && (end >= text.length() || !isIdentifier(text.charAt(end)))) return true;
        }
        return false;
    }

    private static boolean isIdentifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean directiveOrComment(String line) {
        return line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("/*")
                || line.startsWith("*");
    }

    private static String renamedClass(Atom c, Atom l, SourceLines lines) {
        String name = Symbol.of(c).name();
        if (name.startsWith("~")) return "NAME_DESTRUCTOR";
        if (name.startsWith("operator")) return "NAME_OPERATOR";
        if (!mentions(lines.text(c.line(), c.endLine()), name)) return "NAME_MACRO_PASTED";
        if (directive(lines, l)) return "NAME_DIRECTIVE_IN_HEADER";
        String lexicalName = Symbol.of(l).name();
        if (!isMacroName(lexicalName)) return "NAME_OTHER";
        boolean export = lines.text(l.line(), c.line()).matches("(?s).*\\b" + lexicalName + "\\s+" + Pattern.quote(name) + "\\b.*");
        return export ? "NAME_EXPORT_MACRO" : "NAME_MACRO_WRAPPER";
    }

    /** True when an owner chain differs only by an export macro the lexical front end read as the class name. */
    private static boolean macroOwner(String clang, String lexical) {
        return Arrays.stream(lexical.split("\\.")).anyMatch(AtomDiff::isMacroName) && !clang.isEmpty();
    }

    private static boolean isMacroName(String name) {
        return name.matches("[A-Z_][A-Z0-9_]*");
    }

    /** True when the lexical atom starts on a preprocessor line: its header took in a directive's tokens. */
    private static boolean directive(SourceLines lines, Atom lexical) {
        return lines.line(lexical.line()).strip().startsWith("#");
    }

    /** True when a preprocessor conditional opens, switches or closes inside the atom. */
    private static boolean spansConditional(SourceLines lines, Atom atom) {
        return lines.text(atom.line(), atom.endLine()).lines().map(String::strip)
                .anyMatch(s -> s.startsWith("#if") || s.startsWith("#el") || s.startsWith("#endif"));
    }

    private static String clangOnly(Atom a, SourceLines lines) {
        String name = Symbol.of(a).name();
        String text = lines.text(a.line(), a.endLine());
        if (name.contains(":") || a.path().endsWith(".m") || a.path().endsWith(".mm")) return "OBJC";
        if (name.startsWith("~")) return "DESTRUCTOR";
        if (name.startsWith("operator")) return "OPERATOR";
        if (!mentions(text, name)) return "MACRO_GENERATED";
        if (lines.conditional(a.line())) return "IN_CONDITIONAL";
        if (spansConditional(lines, a)) return "SPANS_CONDITIONAL";
        if ("TYPE".equals(a.kind())) return lines.line(a.line()).contains("enum") ? "ENUM" : "TYPE";
        return "OTHER";
    }

    private static String lexicalOnly(Atom b, List<Atom> clang, SourceLines lines) {
        boolean nested = clang.stream().anyMatch(c -> "METHOD".equals(c.kind()) && c.line() <= b.line()
                && b.endLine() <= c.endLine());
        if (nested) return "NESTED_IN_FUNCTION";
        if (lines.conditional(b.endLine())) return "IN_CONDITIONAL";
        if (Symbol.of(b).name().matches("[A-Z_][A-Z0-9_]*")) return "MACRO_CALL";
        if (directive(lines, b)) return "DIRECTIVE_IN_HEADER";
        return spansConditional(lines, b) ? "SPANS_CONDITIONAL" : "OTHER";
    }
}
