// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Regex call sites of one Java source (owner decision 46: every regex carries a spec row).
 * Atom: one source text. Pattern: extractor; a linear scanner over {@link JavaText#mask}, no
 * parser and no regular expression.
 *
 * <p>A site is a call of {@code Pattern.compile} or {@code Pattern.matches} with any first
 * argument, or of {@code matches}, {@code replaceAll}, {@code replaceFirst} or {@code split}
 * whose first argument starts with a string literal. A one-argument {@code replaceAll} or
 * {@code replaceFirst} is the replacement method of {@code Matcher} and is not a site. Calls in
 * comments and in literals are not sites. A first argument made only of string and character
 * literals joined by {@code +} is decoded (JLS 3.10.7) and keyed by its content key, the key of
 * {@code jdk.m3.compiletime.M3ContentKey}: {@code h1:h2:length:flags}. Any other argument, a
 * text block, or a flags argument that is not made of {@code Pattern} flag names and integer
 * literals is dynamic and keyed {@code dyn-} plus 16 hex digits of the SHA-256 of the call
 * text, so a moved call keeps its key.
 */
final class RegexSites {
    static final String DYNAMIC = "dynamic";
    private static final String META = "\\^$.|?*+()[]{}";
    private static final String SPLIT_META = ".$|()[{^?*+\\";
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;
    private static final int DYNAMIC_DIGITS = 16;
    private static final int UNICODE_DIGITS = 4;
    private static final Map<String, Integer> FLAGS = Map.of("UNIX_LINES", 0x01,
            "CASE_INSENSITIVE", 0x02, "COMMENTS", 0x04, "MULTILINE", 0x08, "LITERAL", 0x10,
            "DOTALL", 0x20, "UNICODE_CASE", 0x40, "CANON_EQ", 0x80,
            "UNICODE_CHARACTER_CLASS", 0x100);
    private static final List<String> FLAG_ORDER = List.of("UNIX_LINES", "CASE_INSENSITIVE",
            "COMMENTS", "MULTILINE", "LITERAL", "DOTALL", "UNICODE_CASE", "CANON_EQ",
            "UNICODE_CHARACTER_CLASS");

    private RegexSites() {
    }

    /**
     * One site: the lines of its call and its last argument, the call name, the kind
     * ({@code regex}, {@code literal-only}, {@code single-char-split} or {@code dynamic}), the
     * row id, the pattern as a Java string literal (empty when dynamic), the flags (0 when
     * dynamic) and the call text with white space collapsed.
     */
    record Site(int line, int lastLine, String call, String kind, String id, String pattern,
            int flags, String expression) {
        boolean dynamic() {
            return kind.equals(DYNAMIC);
        }
    }

    /** Every site of {@code text}, in source order. */
    static List<Site> scan(String text) {
        String code = JavaText.mask(text);
        List<Site> out = new ArrayList<>();
        int line = 1;
        int counted = 0;
        for (int open = code.indexOf('('); open >= 0; open = code.indexOf('(', open + 1)) {
            String call = call(text, code, open);
            if (call.isEmpty()) continue;
            int close = close(code, open);
            List<int[]> args = arguments(text, code, open, close);
            if (!isSite(call, args)) continue;
            line += newlines(code, counted, open);
            counted = open;
            out.add(site(text, call, args, line, line + newlines(code, open, close)));
        }
        return out;
    }

    /** The call name ending just before {@code open}, or empty when it is not a regex call. */
    private static String call(String text, String code, int open) {
        int end = back(code, open - 1);
        int start = identifierStart(code, end);
        String name = code.substring(start, end + 1);
        int dot = back(code, start - 1);
        if (dot < 0 || code.charAt(dot) != '.') return "";
        int qualifierEnd = back(code, dot - 1);
        boolean pattern = code.substring(identifierStart(code, qualifierEnd), qualifierEnd + 1)
                .equals("Pattern");
        return switch (name) {
            case "compile" -> pattern ? "Pattern.compile" : "";
            case "matches" -> pattern ? "Pattern.matches" : literalNext(text, open, name);
            case "replaceAll", "replaceFirst", "split" -> literalNext(text, open, name);
            default -> "";
        };
    }

    private static String literalNext(String text, int open, String name) {
        int at = open + 1;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        return at < text.length() && text.charAt(at) == '"' ? name : "";
    }

    private static boolean isSite(String call, List<int[]> args) {
        boolean replacement = call.equals("replaceAll") || call.equals("replaceFirst");
        return !args.isEmpty() && !(replacement && args.size() == 1);
    }

    private static Site site(String text, String call, List<int[]> args, int line, int last) {
        String expression = call + "(" + collapse(text.substring(args.get(0)[0],
                args.get(args.size() - 1)[1])) + ")";
        String pattern = constant(text, args.get(0));
        int flags = call.equals("Pattern.compile") && args.size() > 1
                ? flags(text.substring(args.get(1)[0], args.get(1)[1])) : 0;
        if (pattern == null || flags < 0) {
            return new Site(line, last, call, DYNAMIC, dynamicId(expression), "", 0, expression);
        }
        return new Site(line, last, call, kind(call, pattern), contentKey(pattern, flags),
                quote(pattern), flags, expression);
    }

    /** Spans of the top-level arguments between {@code open} and {@code close}. */
    private static List<int[]> arguments(String text, String code, int open, int close) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
            char ch = code.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) {
                out.add(new int[] {start, i});
                start = i + 1;
            }
        }
        if (!text.substring(start, close).isBlank() || !out.isEmpty()) {
            out.add(new int[] {start, close});
        }
        return out;
    }

    /** Index of the parenthesis that closes {@code open}; the last index when none does. */
    private static int close(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return code.length() - 1;
    }

    /**
     * The value of an argument made only of string and character literals joined by
     * {@code +}, comments and white space; {@code null} for anything else.
     */
    static String constant(String text, int[] span) {
        StringBuilder value = new StringBuilder();
        int at = span[0];
        boolean literal = false;
        while (at < span[1]) {
            char ch = text.charAt(at);
            if (Character.isWhitespace(ch) || ch == '+') at++;
            else if (text.startsWith("//", at)) at = lineEnd(text, at);
            else if (text.startsWith("/*", at)) at = commentEnd(text, at);
            else if (text.startsWith("\"\"\"", at) || (ch != '"' && ch != '\'')) return null;
            else {
                int end = quotedEnd(text, at, ch);
                if (end < 0) return null;
                value.append(unquote(text.substring(at + 1, end)));
                literal = true;
                at = end + 1;
            }
        }
        return literal ? value.toString() : null;
    }

    /** Flags of a {@code Pattern} flag expression, or -1 when a term is not known. */
    static int flags(String expression) {
        int value = 0;
        for (String term : terms(expression.replace('+', '|'))) {
            String name = term.strip();
            int dot = name.lastIndexOf('.');
            Integer bit = FLAGS.get(name.substring(dot + 1));
            String qualifier = dot < 0 ? "Pattern" : name.substring(0, dot);
            if (bit != null && (qualifier.equals("Pattern") || qualifier.endsWith(".Pattern"))) {
                value |= bit;
            } else if (isNumber(name)) {
                value |= Integer.decode(name);
            } else {
                return -1;
            }
        }
        return value;
    }

    /** The flag names of {@code flags} joined by a bar, or {@code -} for none. */
    static String flagNames(int flags) {
        List<String> names = FLAG_ORDER.stream().filter(n -> (flags & FLAGS.get(n)) != 0).toList();
        return names.isEmpty() ? "-" : String.join("|", names);
    }

    /** Flags of {@link #flagNames} output, or -1 when a name is not a flag. */
    static int flagValue(String names) {
        if (names.equals("-")) return 0;
        int value = 0;
        for (String name : terms(names)) {
            Integer bit = FLAGS.get(name);
            if (bit == null) return -1;
            value |= bit;
        }
        return value;
    }

    /** The parts of {@code text} between bars. */
    private static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int bar = text.indexOf('|'); bar >= 0; bar = text.indexOf('|', start)) {
            out.add(text.substring(start, bar));
            start = bar + 1;
        }
        out.add(text.substring(start));
        return out;
    }

    private static boolean isNumber(String name) {
        if (name.isEmpty()) return false;
        try {
            return Integer.decode(name) >= 0;
        } catch (NumberFormatException notNumber) {
            return false;
        }
    }

    /** Kind of a constant pattern used by {@code call}. */
    static String kind(String call, String regex) {
        if (call.equals("split") && fastpath(regex)) return "single-char-split";
        return literalOnly(regex) ? "literal-only" : "regex";
    }

    /** The condition of the {@code String.split} fastpath: no {@code Pattern} is created. */
    static boolean fastpath(String regex) {
        char ch;
        if (regex.length() == 1) {
            ch = regex.charAt(0);
            if (SPLIT_META.indexOf(ch) >= 0) return false;
        } else if (regex.length() == 2 && regex.charAt(0) == '\\') {
            ch = regex.charAt(1);
            if (ch < 0x80 && Character.isLetterOrDigit(ch)) return false;
        } else {
            return false;
        }
        return !Character.isSurrogate(ch);
    }

    /** True when no character of {@code regex} has a regex meaning beyond itself. */
    static boolean literalOnly(String regex) {
        int at = 0;
        while (at < regex.length()) {
            char ch = regex.charAt(at);
            if (regex.startsWith("\\Q", at)) {
                int end = regex.indexOf("\\E", at + 2);
                at = end < 0 ? regex.length() : end + 2;
            } else if (ch == '\\') {
                if (at + 1 >= regex.length() || !escapedLiteral(regex.charAt(at + 1))) return false;
                at += 2;
            } else if (META.indexOf(ch) >= 0) {
                return false;
            } else {
                at++;
            }
        }
        return true;
    }

    private static boolean escapedLiteral(char ch) {
        return !Character.isLetterOrDigit(ch) || "tnrfae".indexOf(ch) >= 0;
    }

    /** Content key of {@code jdk.m3.compiletime.M3ContentKey.of(pattern, flags)} as text. */
    static String contentKey(String pattern, int flags) {
        long a = FNV_OFFSET ^ flags;
        long b = GOLDEN * (flags + 1L);
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            a = (a ^ c) * FNV_PRIME;
            b = (b + c) * GOLDEN;
            b ^= b >>> 29;
        }
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits(a ^ n) + ":" + hex.toHexDigits(fmix64(b ^ ((long) n << 32 | flags)))
                + ":" + n + ":" + flags;
    }

    private static long fmix64(long value) {
        long k = value;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    static String dynamicId(String expression) {
        String digest = Ledger.sha256(expression.getBytes(StandardCharsets.UTF_8));
        return "dyn-" + digest.substring(0, DYNAMIC_DIGITS);
    }

    /** {@code value} as a Java string literal with quotes; ASCII only, so it fits a TSV cell. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                case '\b' -> out.append("\\b");
                default -> out.append(ch < 0x20 || ch > 0x7e ? unicode(ch) : String.valueOf(ch));
            }
        }
        return out.append('"').toString();
    }

    private static String unicode(char ch) {
        return "\\u" + HexFormat.of().toHexDigits(ch);
    }

    /** The value of the body of a Java string or character literal (JLS 3.10.7). */
    static String unquote(String body) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < body.length()) {
            char ch = body.charAt(at++);
            if (ch != '\\' || at >= body.length()) {
                out.append(ch);
                continue;
            }
            char next = body.charAt(at++);
            switch (next) {
                case 'b' -> out.append('\b');
                case 's' -> out.append(' ');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'f' -> out.append('\f');
                case 'r' -> out.append('\r');
                case 'u' -> at = unicodeEscape(body, at, out);
                default -> at = octalOrSelf(body, at, next, out);
            }
        }
        return out.toString();
    }

    private static int unicodeEscape(String body, int from, StringBuilder out) {
        int at = from;
        while (at < body.length() && body.charAt(at) == 'u') at++;
        int end = Math.min(body.length(), at + UNICODE_DIGITS);
        out.append((char) Integer.parseInt(body.substring(at, end), 16));
        return end;
    }

    private static boolean isOctal(char ch) {
        return ch >= '0' && ch <= '7';
    }

    private static int octalOrSelf(String body, int from, char first, StringBuilder out) {
        if (!isOctal(first)) {
            out.append(first);
            return from;
        }
        int at = from;
        int limit = first <= '3' ? 2 : 1;
        int value = first - '0';
        while (limit-- > 0 && at < body.length() && isOctal(body.charAt(at))) {
            value = value * 8 + body.charAt(at++) - '0';
        }
        out.append((char) value);
        return at;
    }

    /** Decoded values of a cell of quoted Java literals separated by commas. */
    static List<String> literals(String cell) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (at < cell.length()) {
            char ch = cell.charAt(at);
            if (ch == ' ' || ch == ',') {
                at++;
                continue;
            }
            int end = ch == '"' ? quotedEnd(cell, at, '"') : -1;
            if (end < 0) return List.of();
            out.add(unquote(cell.substring(at + 1, end)));
            at = end + 1;
        }
        return out;
    }

    /** Index of the quote that ends the literal opened at {@code at}, or -1 on its line. */
    private static int quotedEnd(String text, int at, char quote) {
        for (int i = at + 1; i < text.length() && text.charAt(i) != '\n'; i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == quote) return i;
        }
        return -1;
    }

    private static int lineEnd(String text, int at) {
        int end = text.indexOf('\n', at);
        return end < 0 ? text.length() : end;
    }

    private static int commentEnd(String text, int at) {
        int end = text.indexOf("*/", at + 2);
        return end < 0 ? text.length() : end + 2;
    }

    private static int back(String code, int from) {
        int at = from;
        while (at >= 0 && Character.isWhitespace(code.charAt(at))) at--;
        return at;
    }

    private static int identifierStart(String code, int end) {
        int at = end;
        while (at >= 0 && Character.isJavaIdentifierPart(code.charAt(at))) at--;
        return at + 1;
    }

    private static int newlines(String code, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) if (code.charAt(i) == '\n') count++;
        return count;
    }

    private static String collapse(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                space = false;
                out.append(ch);
            }
        }
        return out.toString();
    }
}
