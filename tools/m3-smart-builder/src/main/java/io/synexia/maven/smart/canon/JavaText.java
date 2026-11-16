// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

/**
 * Lexical view of Java source for the built-in checks (coding-agent laws 3 and 4: strings and comments are data,
 * never structure). Atom: one source text. Pattern: extractor; a linear scanner, no parser and no regular expression
 * over literals, so a long text block costs no stack.
 */
final class JavaText {
    private JavaText() {
    }

    /**
     * Blanks comments, string literals, character literals and text blocks. Every masked character becomes a space
     * and every line break stays, so offsets and line numbers of the result equal those of the source.
     */
    static String mask(String text) {
        char[] out = text.toCharArray();
        int at = 0;
        while (at < out.length) {
            int end = literalEnd(text, at);
            for (int i = at; i < end; i++) if (out[i] != '\n') out[i] = ' ';
            at = Math.max(end, at + 1);
        }
        return new String(out);
    }

    /** Index of the brace that closes the one at or after {@code start}; the last index when none closes it. */
    static int close(String code, int start) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return code.length() - 1;
    }

    /** End (exclusive) of the comment or literal that starts at {@code at}; {@code at} itself when none starts there. */
    private static int literalEnd(String text, int at) {
        if (text.startsWith("//", at)) return upTo(text.indexOf('\n', at), text.length());
        if (text.startsWith("/*", at)) return upTo(after(text.indexOf("*/", at + 2), 2), text.length());
        if (text.startsWith("\"\"\"", at)) return textBlockEnd(text, at + 3);
        char ch = text.charAt(at);
        return ch == '"' || ch == '\'' ? quotedEnd(text, at, ch) : at;
    }

    private static int textBlockEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.startsWith("\"\"\"", i)) return i + 3;
        }
        return text.length();
    }

    /** A quoted literal ends on its own line; an unpaired quote is left as code. */
    private static int quotedEnd(String text, int at, char quote) {
        for (int i = at + 1; i < text.length() && text.charAt(i) != '\n'; i++) {
            if (text.charAt(i) == '\\') i++;
            else if (text.charAt(i) == quote) return i + 1;
        }
        return at;
    }

    private static int after(int found, int length) {
        return found < 0 ? -1 : found + length;
    }

    private static int upTo(int found, int otherwise) {
        return found < 0 ? otherwise : found;
    }
}
