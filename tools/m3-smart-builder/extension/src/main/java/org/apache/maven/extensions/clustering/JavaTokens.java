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
package org.apache.maven.extensions.clustering;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * A quick lexical pass over Java sources that counts tokens: white space and comments are left out; a string, text
 * block or character literal is one token, so is an identifier, a keyword or a number; every other character is one
 * token (an operator of two characters counts two). javac's heap, and the javac plugin's, grow with the tokens of a
 * compilation, not with its bytes: comment-heavy sources hold few tokens per byte, generated code many. Bytes are read
 * as they are (UTF-8 bytes above 127 count as identifier characters), one pass, no allocation per token.
 */
final class JavaTokens {
    private final byte[] text;
    private int at;
    private long tokens;

    private JavaTokens(byte[] text) {
        this.text = text;
    }

    /** The tokens of one source. */
    static long count(byte[] source) {
        JavaTokens lexer = new JavaTokens(source);
        while (lexer.at < source.length) lexer.next();
        return lexer.tokens;
    }

    /** The tokens of the {@code .java} files under the roots; a root or file that cannot be read counts 0. */
    static long count(List<Path> roots) {
        return roots.stream().filter(Files::isDirectory).mapToLong(JavaTokens::underRoot).sum();
    }

    /** The bytes of the {@code .java} files under the roots: never fewer than their tokens. */
    static long bytes(List<Path> roots) {
        return roots.stream().filter(Files::isDirectory).flatMap(JavaTokens::javaFiles).mapToLong(p -> p.toFile().length()).sum();
    }

    private static long underRoot(Path root) {
        return javaFiles(root).mapToLong(JavaTokens::ofFile).sum();
    }

    private static Stream<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).toList().stream();
        } catch (IOException | UncheckedIOException e) {
            return Stream.empty();
        }
    }

    private static long ofFile(Path file) {
        try {
            return count(Files.readAllBytes(file));
        } catch (IOException e) {
            return 0;
        }
    }

    private void next() {
        int c = text[at] & 0xff;
        if (c <= ' ') at++;
        else if (c == '/' && byteAt(at + 1) == '/') skipTo('\n');
        else if (c == '/' && byteAt(at + 1) == '*') skipComment();
        else token(c);
    }

    private void token(int c) {
        tokens++;
        if (c == '"' || c == '\'') quoted(c);
        else if (word(c) && !digit(c)) skipWhile(false);
        else if (digit(c)) skipWhile(true);
        else at++;
    }

    /** A string, a character or a text block: to the closing quote, escapes skipped; a plain one ends at a line end. */
    private void quoted(int quote) {
        boolean block = quote == '"' && byteAt(at + 1) == '"' && byteAt(at + 2) == '"';
        at += block ? 3 : 1;
        while (at < text.length && !(block ? closesBlock() : text[at] == quote || text[at] == '\n')) {
            at += text[at] == '\\' ? 2 : 1;
        }
        at += block ? 3 : 1;
    }

    private boolean closesBlock() {
        return text[at] == '"' && byteAt(at + 1) == '"' && byteAt(at + 2) == '"';
    }

    private void skipComment() {
        at += 2;
        while (at < text.length && !(text[at] == '*' && byteAt(at + 1) == '/')) at++;
        at += 2;
    }

    private void skipTo(char end) {
        while (at < text.length && text[at] != end) at++;
    }

    /** An identifier or keyword; with {@code number}, a number literal ({@code 0x1F}, {@code 1_000L}, {@code 1.5e3}). */
    private void skipWhile(boolean number) {
        at++;
        while (at < text.length && (word(text[at] & 0xff) || number && text[at] == '.')) at++;
    }

    private int byteAt(int index) {
        return index < text.length ? text[index] : -1;
    }

    private static boolean digit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean word(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || digit(c) || c == '_' || c == '$' || c >= 0x80;
    }
}
