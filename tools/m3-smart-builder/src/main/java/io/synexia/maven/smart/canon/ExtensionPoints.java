// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in check {@code extension_points} (the PUBLIC_API lock for types others implement): a method added to an
 * existing interface is a default method, and a method added to an existing abstract class has a body, so existing
 * implementors and subclasses keep compiling. Atom: one modified Java file. Pattern: comparator.
 *
 * <p>For every Java file modified since the base commit it compares the interfaces and abstract classes that exist
 * on both sides, nested types included, and writes one row per difference to {@value #TABLE}:
 * {@code ABSTRACT_ADDED} (the violation), {@code DEFAULT_ADDED} and {@code CONCRETE_ADDED} (allowed) and
 * {@code ABSTRACT_REMOVED} (information). Types that are new, annotation types, concrete classes, and static or
 * private methods are not compared. Comments and literals are blanked first. A signature is the method name and its
 * parameter types; annotations, {@code final} and parameter names do not belong to it.
 */
final class ExtensionPoints {
    static final String TABLE = "EXTENSION_POINTS.tsv";
    static final String PREFIX = "EXTENSION_";
    private static final Pattern DECLARATION = Pattern.compile("(?<![@\\w])(interface|class)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern TYPE_WORD = Pattern.compile("\\b(class|interface|enum|record)\\b");
    private static final Pattern HIDDEN = Pattern.compile("\\b(static|private)\\b");
    private static final Pattern METHOD_HEAD = Pattern.compile("[\\w>\\]]\\s+[A-Za-z_$][\\w$]*\\s*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\([^)]*\\))?");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[\\s>\\].]\\s*[A-Za-z_$][\\w$]*\\s*$");
    private static final Pattern TRAILING_NAME = Pattern.compile("\\s*[A-Za-z_$][\\w$]*\\s*$");

    private ExtensionPoints() {
    }

    /** An interface or abstract class: its kind and the signatures of its abstract and bodied methods. */
    private record Type(String kind, Set<String> abstracts, Set<String> bodied) { }

    /** A declared type: where it starts, where its body opens and closes, its name and kind. */
    private record Span(int start, int open, int end, String name, String kind) { }

    static String check(Git git, Path root, Path out, String base, List<String> violations)
            throws IOException, InterruptedException {
        List<String> rows = new ArrayList<>();
        for (String path : git.modified(base, "*.java")) {
            Map<String, Type> before = types(git.show(base, path));
            Map<String, Type> after = types(new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8));
            for (Map.Entry<String, Type> now : after.entrySet()) {
                if (before.containsKey(now.getKey())) compare(path, now.getKey(), before.get(now.getKey()), now.getValue(), rows);
            }
        }
        rows.stream().filter(row -> row.endsWith("\tABSTRACT_ADDED\n")).map(row -> row.strip().split("\t"))
                .forEach(cell -> violations.add(PREFIX + "ABSTRACT_ADDED " + cell[0] + " " + cell[2] + " " + cell[3]));
        Ledger.write(out, TABLE, "path\ttype_kind\ttype\tsignature\tkind\n" + String.join("", rows));
        return "extension points: " + count(rows, "ABSTRACT_ADDED") + " abstract added, "
                + (count(rows, "DEFAULT_ADDED") + count(rows, "CONCRETE_ADDED")) + " with a body added, "
                + count(rows, "ABSTRACT_REMOVED") + " abstract removed";
    }

    private static long count(List<String> rows, String kind) {
        return rows.stream().filter(row -> row.endsWith("\t" + kind + "\n")).count();
    }

    private static void compare(String path, String name, Type before, Type after, List<String> rows) {
        String added = after.kind().equals("interface") ? "DEFAULT_ADDED" : "CONCRETE_ADDED";
        String lead = Ledger.clean(path) + "\t" + after.kind() + "\t" + name + "\t";
        after.abstracts().stream().filter(s -> !before.abstracts().contains(s) && !before.bodied().contains(s))
                .forEach(s -> rows.add(lead + s + "\tABSTRACT_ADDED\n"));
        after.bodied().stream().filter(s -> !before.bodied().contains(s) && !before.abstracts().contains(s))
                .forEach(s -> rows.add(lead + s + "\t" + added + "\n"));
        before.abstracts().stream().filter(s -> !after.abstracts().contains(s))
                .forEach(s -> rows.add(lead + s + "\tABSTRACT_REMOVED\n"));
    }

    /** Interfaces and abstract classes of one source text, keyed by their nested type path. */
    static Map<String, Type> types(String text) {
        String code = JavaText.mask(text);
        List<Span> spans = spans(code);
        Map<String, Type> out = new TreeMap<>();
        for (Span span : spans) {
            if (span.kind().equals("class")) continue;
            StringBuilder path = new StringBuilder();
            spans.stream().filter(s -> s.start() <= span.start() && span.end() <= s.end())
                    .forEach(s -> path.append(path.length() == 0 ? "" : ".").append(s.name()));
            out.put(path.toString(), type(span.kind(), code.substring(span.open() + 1, span.end())));
        }
        return out;
    }

    private static List<Span> spans(String code) {
        List<Span> spans = new ArrayList<>();
        Matcher match = DECLARATION.matcher(code);
        while (match.find()) {
            int open = code.indexOf('{', match.end());
            if (open < 0) continue;
            int from = Math.max(code.lastIndexOf(';', match.start() - 1),
                    Math.max(code.lastIndexOf('{', match.start() - 1), code.lastIndexOf('}', match.start() - 1))) + 1;
            boolean isAbstract = code.substring(from, match.start()).matches("(?s).*\\babstract\\b.*");
            String kind = match.group(1).equals("interface") ? "interface" : isAbstract ? "abstract class" : "class";
            spans.add(new Span(match.start(), open, JavaText.close(code, open), match.group(2), kind));
        }
        return spans;
    }

    private static Type type(String kind, String body) {
        Set<String> abstracts = new TreeSet<>();
        Set<String> bodied = new TreeSet<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            boolean opensBody = ch == '{' && depth == 0;
            if (opensBody || (ch == ';' && depth == 0)) {
                member(kind, body.substring(start, i), opensBody, abstracts, bodied);
                if (opensBody) i = JavaText.close(body, i);
                start = i + 1;
            } else if (ch == '(' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == '}') {
                depth--;
            }
        }
        return new Type(kind, abstracts, bodied);
    }

    /** Files one member of a type body under abstract or bodied, or drops it when it is not an inheritable method. */
    private static void member(String kind, String chunk, boolean hasBody, Set<String> abstracts, Set<String> bodied) {
        int paren = chunk.indexOf('(');
        if (paren < 0) return;
        String head = chunk.substring(0, paren);
        if (TYPE_WORD.matcher(head).find() || head.contains("=") || HIDDEN.matcher(head).find()
                || !METHOD_HEAD.matcher(head).find()) return;
        boolean isInterface = kind.equals("interface");
        if (hasBody && (!isInterface || head.matches("(?s).*\\bdefault\\b.*"))) bodied.add(signature(chunk, paren));
        else if (!hasBody && (isInterface || head.matches("(?s).*\\babstract\\b.*"))) abstracts.add(signature(chunk, paren));
    }

    /** Method name and parameter types, for example {@code put(K,java.util.List<V>)}. */
    private static String signature(String chunk, int paren) {
        Matcher identifier = IDENTIFIER.matcher(chunk.substring(0, paren));
        String name = "";
        while (identifier.find()) name = identifier.group();
        return name + "(" + String.join(",", parameters(chunk.substring(paren + 1, closing(chunk, paren)))) + ")";
    }

    /** Index of the parenthesis that closes the one at {@code paren}; the length when none closes it. */
    private static int closing(String chunk, int paren) {
        int depth = 0;
        for (int i = paren; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '(') depth++;
            else if (chunk.charAt(i) == ')' && --depth == 0) return i;
        }
        return chunk.length();
    }

    /** The parameter types of a parameter list, split at the commas outside angle brackets. */
    private static List<String> parameters(String list) {
        List<String> out = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int angle = 0;
        for (char ch : (list + ",").toCharArray()) {
            angle += ch == '<' ? 1 : ch == '>' ? -1 : 0;
            if (ch != ',' || angle != 0) {
                part.append(ch);
                continue;
            }
            String type = parameterType(part.toString());
            if (!type.isEmpty()) out.add(type);
            part.setLength(0);
        }
        return out;
    }

    private static String parameterType(String parameter) {
        String text = ANNOTATION.matcher(parameter).replaceAll(" ").replaceAll("\\bfinal\\b", " ").strip();
        if (PARAMETER_NAME.matcher(text).find()) text = TRAILING_NAME.matcher(text).replaceFirst("");
        return text.replaceAll("\\s+", "");
    }
}
