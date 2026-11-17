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
package org.apache.maven.impl;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.apache.maven.api.services.ResourceFilterRequest;

/**
 * Reader that replaces the expressions of the wrapped reader, one character at a time, so that the text between
 * expressions (line endings included) is passed through unchanged.
 * <p>
 * The scanning rules are those of maven-filtering 3.x {@code MultiDelimiterInterpolatorFilterReaderLineEnding},
 * {@code AbstractFilterReaderLineEnding} and {@code BoundedReader} (Apache-2.0), which derive from plexus-utils
 * {@code InterpolationFilterReader} (Apache-2.0): an escape string before a delimiter cancels the expression;
 * without multi-line filtering an expression ends at {@code '\n'}; the end token is searched with the same naive
 * matcher; an expression is searched for in a window of 255 characters plus the lengths of the escape string and
 * of all delimiters; when several begin tokens match, the last declared one wins. Unlike maven-filtering, an escape
 * string at the end of the input is copied as is (maven-filtering appends {@code U+FFFF}). Values come from the
 * given resolver, which returns empty for an unresolved expression; such an expression is copied with only the
 * expressions of earlier declared delimiters inside it replaced, as maven-filtering does through
 * plexus-interpolation.
 */
final class ResourceFilterReader extends Reader {

    /** Base of the search window, as {@code markLength} of maven-filtering. */
    private static final int WINDOW_BASE = 255;

    /** Returned by {@link #next()} when replacement text was queued instead of a character. */
    private static final int AGAIN = -2;

    private final Reader in;
    private final List<Delimiter> delimiters;
    private final String escape;
    private final boolean multiLine;
    private final Function<String, Optional<String>> resolver;
    private final int window;
    private final char[] buffer;
    private int start;
    private int end;
    private boolean eof;
    private String pending = "";
    private int pendingIndex;

    /**
     * A begin and an end token.
     *
     * @param begin the begin token
     * @param end the end token
     */
    record Delimiter(String begin, String end) {
        /**
         * Parses {@code BEGIN*END}, {@code TOKEN} or {@code TOKEN*} (symmetric), as plexus-interpolation
         * {@code DelimiterSpecification.parse} does.
         */
        static Delimiter parse(String spec) {
            int star = spec.indexOf('*');
            if (star < 0) {
                return new Delimiter(spec, spec);
            }
            String begin = spec.substring(0, star);
            return star == spec.length() - 1
                    ? new Delimiter(begin, begin)
                    : new Delimiter(begin, spec.substring(star + 1));
        }
    }

    ResourceFilterReader(Reader in, ResourceFilterRequest request, Function<String, Optional<String>> resolver) {
        this.in = in;
        this.delimiters = request.getDelimiters().stream()
                .map(Delimiter::parse)
                .distinct()
                .toList();
        this.escape = request.getEscapeString().orElse(null);
        this.multiLine = request.isSupportMultiLineFiltering();
        this.resolver = resolver;
        this.window = windowLength(delimiters, escape);
        this.buffer = new char[window * 2];
    }

    private static int windowLength(List<Delimiter> delimiters, String escape) {
        int length = WINDOW_BASE + (escape == null ? 0 : escape.length());
        return length
                + delimiters.stream()
                        .mapToInt(d -> d.begin().length() + d.end().length())
                        .sum();
    }

    @Override
    public int read() throws IOException {
        int c = next();
        while (c == AGAIN) {
            c = next();
        }
        return c;
    }

    /** The next character, or {@link #AGAIN} when replacement text was queued. */
    private int next() throws IOException {
        if (pendingIndex < pending.length()) {
            return pending.charAt(pendingIndex++);
        }
        int c = peek(0);
        if (c == -1 || (c == '\n' && !multiLine)) {
            return take(1, c);
        }
        int escaped = escapeLength();
        Delimiter delimiter = beginAt(escaped);
        if (escaped > 0) {
            return escaped(escaped, delimiter);
        }
        return delimiter == null ? take(1, c) : expression(delimiter);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c == -1) {
                return i == 0 ? -1 : i;
            }
            cbuf[off + i] = (char) c;
        }
        return len;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** The escape string and the character after it; that character starts no expression. */
    private int escaped(int length, Delimiter delimiter) throws IOException {
        int next = peek(length);
        String prefix = delimiter == null ? escape : "";
        consume(next == -1 ? length : length + 1);
        return emit(next == -1 ? prefix : prefix + (char) next);
    }

    /** A begin token: the expression up to its end token, or the first character when no end token follows. */
    private int expression(Delimiter delimiter) throws IOException {
        int length = expressionLength(delimiter);
        if (length < 0) {
            return take(1, peek(0));
        }
        String text = new String(buffer, start, length);
        consume(length);
        return emit(value(text, delimiter).orElseGet(() -> reread(text)));
    }

    /** The value of an expression written with both tokens of its delimiter, empty when unresolved. */
    private Optional<String> value(String expression, Delimiter delimiter) {
        return resolver.apply(expression.substring(
                delimiter.begin().length(),
                expression.length() - delimiter.end().length()));
    }

    /**
     * An unresolved expression read again as maven-filtering does: it hands the whole text to plexus-interpolation
     * {@code MultiDelimiterStringSearchInterpolator}, which takes each time the first declared delimiter whose begin
     * token occurs, so that an expression of an earlier declared delimiter inside the text is replaced, as
     * {@code ${b}} in {@code @x ${b} y@}, while the text around it is kept.
     */
    private String reread(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (from < text.length()) {
            from = rereadNext(text, from, out);
        }
        return out.toString();
    }

    /** Appends the text from {@code from} to the end of its next expression; returns the index after that. */
    private int rereadNext(String text, int from, StringBuilder out) {
        Optional<Delimiter> first = delimiters.stream()
                .filter(d -> text.indexOf(d.begin(), from) >= 0)
                .findFirst();
        int begin = first.map(d -> text.indexOf(d.begin(), from)).orElse(-1);
        int end = first.map(d -> text.indexOf(d.end(), begin + d.begin().length()))
                .orElse(-1);
        if (end < 0) {
            out.append(text, from, text.length());
            return text.length();
        }
        int after = end + first.get().end().length();
        appendExpression(text, from, begin, text.substring(begin, after), first.get(), out);
        return after;
    }

    /**
     * The text from {@code from} and the value of the expression at {@code begin}; after an escape string, the
     * text without it and the expression itself. The whole text, already unresolved, is not looked up again.
     */
    private void appendExpression(
            String text, int from, int begin, String expression, Delimiter delimiter, StringBuilder out) {
        boolean escaped =
                escape != null && begin - escape.length() >= from && text.startsWith(escape, begin - escape.length());
        out.append(text, from, escaped ? begin - escape.length() : begin);
        out.append(
                escaped || expression.equals(text)
                        ? expression
                        : value(expression, delimiter).orElse(expression));
    }

    /** Length of the expression with both tokens, or -1 when its end is not found before EOL, EOF or the window. */
    private int expressionLength(Delimiter delimiter) throws IOException {
        String endToken = delimiter.end();
        int matched = 0;
        for (int offset = delimiter.begin().length(); ; offset++) {
            int c = peek(offset);
            if (c == -1 || (c == '\n' && !multiLine)) {
                return -1;
            }
            matched = c == endToken.charAt(matched) ? matched + 1 : 0;
            if (matched == endToken.length()) {
                return offset + 1;
            }
        }
    }

    private int escapeLength() throws IOException {
        return escape != null && matchesAt(0, escape) ? escape.length() : 0;
    }

    private Delimiter beginAt(int offset) throws IOException {
        Delimiter found = null;
        for (Delimiter delimiter : delimiters) {
            if (matchesAt(offset, delimiter.begin())) {
                found = delimiter;
            }
        }
        return found;
    }

    private boolean matchesAt(int offset, String token) throws IOException {
        for (int i = 0; i < token.length(); i++) {
            int c = peek(offset + i);
            if (c != token.charAt(i) || (c == '\n' && !multiLine)) {
                return false;
            }
        }
        return true;
    }

    private int emit(String text) {
        pending = text;
        pendingIndex = 0;
        return AGAIN;
    }

    private int take(int count, int c) {
        if (c != -1) {
            consume(count);
        }
        return c;
    }

    private void consume(int count) {
        start += count;
    }

    /** The character at {@code offset} from the current position, or -1 at EOF or beyond the search window. */
    private int peek(int offset) throws IOException {
        if (offset >= window) {
            return -1;
        }
        while (end - start <= offset && !eof) {
            fill();
        }
        return end - start > offset ? buffer[start + offset] : -1;
    }

    private void fill() throws IOException {
        if (end == buffer.length) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
        }
        int count = in.read(buffer, end, buffer.length - end);
        if (count < 0) {
            eof = true;
        } else {
            end += count;
        }
    }
}
