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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Language-neutral tokenizer for the lexical front end. It skips comments, keeps string and number literals whole,
 * and marks keywords from one shared set. Two comment families are covered: {@code //} and block comments for the
 * brace languages, and {@code #} for the indentation languages. Indentation languages also get synthetic
 * {@code INDENT} and {@code DEDENT} tokens, so that block nesting shows in the token stream as braces do. A lexical
 * tree is coarser than a parser's tree. A dedicated front end (javac for Java) is used where one exists.
 */
final class Lexer {
    enum Kind { ID, KEYWORD, NUMBER, STRING, OP }

    /** A token; {@code line} is the 1-based line it starts on (a string literal may run over more lines). */
    record Token(Kind kind, String text, int start, int end, int line) {
        /** The 1-based line the token ends on. */
        int endLine() {
            return line + (int) text.chars().filter(c -> c == '\n').count();
        }
    }

    static final Set<String> KEYWORDS = Set.of("abstract", "and", "as", "async", "await", "break", "case", "catch",
            "class", "const", "continue", "def", "default", "defer", "del", "do", "elif", "else", "enum", "except",
            "export", "extends", "false", "final", "finally", "fn", "for", "from", "fun", "func", "function", "go",
            "goto", "guard", "if", "impl", "implements", "import", "in", "interface", "is", "lambda", "let", "loop",
            "match", "mod", "module", "mut", "namespace", "new", "nil", "None", "not", "null", "object", "of", "or",
            "override", "package", "pass", "private", "protected", "pub", "public", "raise", "range", "record",
            "return", "self", "static", "struct", "super", "switch", "this", "throw", "throws", "trait", "True",
            "False", "true", "try", "type", "typedef", "union", "unless", "use", "using", "val", "var", "void",
            "when", "where", "while", "with", "yield");

    private final String text;
    private final boolean hashComments;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;

    private Lexer(String text, boolean hashComments) {
        this.text = text;
        this.hashComments = hashComments;
    }

    static List<Token> tokens(String text, boolean indentation) {
        Lexer lexer = new Lexer(text, indentation);
        lexer.run(indentation);
        return lexer.tokens;
    }

    private void run(boolean indentation) {
        List<Integer> indents = new ArrayList<>(List.of(0));
        boolean lineStart = true;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (lineStart && indentation) {
                int width = indentWidth();
                if (pos < text.length() && text.charAt(pos) != '\n' && text.charAt(pos) != '#') indent(indents, width);
                lineStart = false;
                continue;
            }
            if (c == '\n') {
                line++;
                pos++;
                lineStart = true;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (comment()) {
                continue;
            } else if (c == '"' || c == '\'' || c == '`') {
                string(c);
            } else if (Character.isDigit(c)) {
                span(Kind.NUMBER, pos, c2 -> Character.isLetterOrDigit(c2) || c2 == '.' || c2 == '_');
            } else if (Character.isJavaIdentifierStart(c)) {
                int from = pos;
                span(Kind.ID, from, Character::isJavaIdentifierPart);
                Token last = tokens.remove(tokens.size() - 1);
                tokens.add(KEYWORDS.contains(last.text()) ? new Token(Kind.KEYWORD, last.text(), last.start(), last.end(), last.line()) : last);
            } else {
                operator();
            }
        }
        indents.subList(1, indents.size()).forEach(i -> add(Kind.OP, "DEDENT", pos, pos));
    }

    private int indentWidth() {
        int width = 0;
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
            width += text.charAt(pos) == '\t' ? 8 : 1;
            pos++;
        }
        return width;
    }

    private void indent(List<Integer> indents, int width) {
        if (width > indents.get(indents.size() - 1)) {
            indents.add(width);
            add(Kind.OP, "INDENT", pos, pos);
        }
        while (width < indents.get(indents.size() - 1)) {
            indents.remove(indents.size() - 1);
            add(Kind.OP, "DEDENT", pos, pos);
        }
    }

    private boolean comment() {
        if (hashComments ? text.charAt(pos) == '#' : text.startsWith("//", pos)) {
            while (pos < text.length() && text.charAt(pos) != '\n') pos++;
            return true;
        }
        if (!hashComments && text.startsWith("/*", pos)) {
            int close = text.indexOf("*/", pos + 2);
            int stop = close < 0 ? text.length() : close + 2;
            line += (int) text.substring(pos, stop).chars().filter(ch -> ch == '\n').count();
            pos = stop;
            return true;
        }
        return false;
    }

    /** A string literal; its token carries the line it starts on, and every newline inside it is counted. */
    private void string(char quote) {
        int from = pos;
        int startLine = line;
        String fence = hashComments && text.startsWith(String.valueOf(quote).repeat(3), pos) ? String.valueOf(quote).repeat(3) : null;
        pos += fence == null ? 1 : 3;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\\') {
                if (pos + 1 < text.length() && text.charAt(pos + 1) == '\n') line++;
                pos += 2;
                continue;
            }
            if (fence != null ? text.startsWith(fence, pos) : c == quote) {
                pos += fence == null ? 1 : 3;
                break;
            }
            if (c == '\n' && fence == null && quote != '`') {
                if (quote == '\'') {
                    pos = from + 1;
                    line = startLine;
                    add(Kind.OP, "'", from, from + 1);
                    return;
                }
                break;
            }
            if (c == '\n') line++;
            pos++;
        }
        int end = Math.min(pos, text.length());
        tokens.add(new Token(Kind.STRING, text.substring(from, end), from, end, startLine));
    }

    private void span(Kind kind, int from, java.util.function.IntPredicate part) {
        while (pos < text.length() && part.test(text.charAt(pos))) pos++;
        add(kind, text.substring(from, pos), from, pos);
    }

    private static final String[] OPERATORS = {">>>=", "<<=", ">>=", "...", "===", "!==", "**=", "->", "=>", "::",
        ":=", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", "**"};

    private void operator() {
        for (String op : OPERATORS) {
            if (text.startsWith(op, pos)) {
                add(Kind.OP, op, pos, pos + op.length());
                pos += op.length();
                return;
            }
        }
        add(Kind.OP, String.valueOf(text.charAt(pos)), pos, pos + 1);
        pos++;
    }

    private void add(Kind kind, String value, int from, int to) {
        tokens.add(new Token(kind, value, from, to, line));
    }
}
