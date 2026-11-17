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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The JNI bindings that C, C++ and Objective-C sources make other than by a function atom named {@code Java_...}.
 * RegisterNatives tables: arrays {@code JNINativeMethod name[] = {...}} (or {@code name[] {...}}) whose entries name a
 * Java method with a string literal, for example {@code {"open0", "(Ljava/lang/String;)I", (void *) &open0}}. An entry
 * gives the method name, the signature when it is written with string literals only (a macro in it leaves the
 * signature unknown) and the function its pointer names. A file with a table also gives the class names it writes as
 * string literals ({@code "java/io/File"}, as passed to {@code FindClass}). And functions named through a macro
 * ({@link JniMacros}): the macros of every header ({@code .h}, {@code .hh}, {@code .hpp}) under the root apply in every
 * file, and a file's own macros apply in it and hide a header's macro of the same name.
 */
final class JniRegistrations {
    /** One table entry: its file and table, the Java method's name, its signature if literal, the function named. */
    record Entry(String path, String table, String name, Optional<String> signature, Optional<String> function) { }

    private static final String TYPE = "JNINativeMethod";
    private static final Set<String> OPEN = Set.of("(", "[", "{");
    private static final Set<String> CLOSE = Set.of(")", "]", "}");
    private static final Set<String> CAST = Set.of("(", ")", "*", "char", "const");
    private static final Pattern CLASS_NAME = Pattern.compile("\"[A-Za-z_$][\\w$]*(?:/[A-Za-z_$][\\w$]*)+\"");
    private static final Set<String> HEADERS = Set.of("h", "hh", "hpp");
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Set<String>> classes = new HashMap<>();
    private final List<JniMacros.Named> named = new ArrayList<>();

    private JniRegistrations() { }

    /**
     * Reads the tables and macro-named functions of the C, C++ and Objective-C files under the roots, after the macros
     * of the headers under each root; paths are relative to their root.
     */
    static JniRegistrations scan(List<Path> roots) throws IOException {
        JniRegistrations found = new JniRegistrations();
        for (Path given : roots) {
            Path root = given.toAbsolutePath().normalize();
            List<Path> files = AtomPlanes.sourceFiles(root, JniLinker::nativeFile);
            Map<String, List<JniMacros.Macro>> headers = new HashMap<>();
            for (Path file : files) if (HEADERS.contains(AtomFrontEnd.extension(file))) headerMacros(file, headers);
            for (Path file : files) found.read(root, file, headers);
        }
        return found;
    }

    /** Adds the macros of a header that may name a JNI function. */
    private static void headerMacros(Path file, Map<String, List<JniMacros.Macro>> headers) throws IOException {
        String text = text(file);
        if (!text.contains("define")) return;
        JniMacros.macros(Lexer.tokens(text, false)).forEach((name, macros) ->
                headers.computeIfAbsent(name, k -> new ArrayList<>()).addAll(macros));
    }

    List<Entry> entries() {
        return entries;
    }

    /** The class names a file with a table writes as string literals, in internal form. */
    Set<String> classes(String path) {
        return classes.getOrDefault(path, Set.of());
    }

    /** The {@code Java_} functions defined through a token-pasting macro. */
    List<JniMacros.Named> named() {
        return named;
    }

    /** Reads one file: when it may use a macro or has a table, its macro-named functions and its table entries. */
    private void read(Path root, Path file, Map<String, List<JniMacros.Macro>> headers) throws IOException {
        String text = text(file);
        boolean table = text.contains(TYPE);
        boolean macros = text.contains("define") || headers.keySet().stream().anyMatch(text::contains);
        if (!table && !macros) return;
        String path = root.relativize(file).toString().replace('\\', '/');
        List<Token> tokens = Lexer.tokens(text, false);
        Map<String, List<JniMacros.Macro>> visible = new HashMap<>(headers);
        visible.putAll(JniMacros.macros(tokens));
        named.addAll(JniMacros.functions(path, tokens, visible));
        if (table) tables(path, tokens);
    }

    private void tables(String path, List<Token> tokens) {
        IntStream.range(0, tokens.size()).filter(i -> tokens.get(i).text().equals(TYPE))
                .forEach(i -> entries.addAll(table(path, tokens, i)));
        classes.put(path, tokens.stream().filter(t -> t.kind() == Kind.STRING && CLASS_NAME.matcher(t.text()).matches())
                .map(t -> unquote(t.text())).collect(Collectors.toSet()));
    }

    private static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * The entries of {@code JNINativeMethod name[...] = {...}} or {@code name[...] {...}} at {@code at}; none for a
     * pointer or a parameter.
     */
    private static List<Entry> table(String path, List<Token> tokens, int at) {
        int name = at + 1 < tokens.size() && tokens.get(at + 1).text().equals("const") ? at + 2 : at + 1;
        if (name + 1 >= tokens.size() || tokens.get(name).kind() != Kind.ID || !tokens.get(name + 1).text().equals("[")) {
            return List.of();
        }
        int after = close(tokens, name + 1) + 1;
        int brace = after < tokens.size() && tokens.get(after).text().equals("=") ? after + 1 : after;
        if (brace >= tokens.size() || !tokens.get(brace).text().equals("{")) return List.of();
        List<Token> body = tokens.subList(brace + 1, Math.max(brace + 1, close(tokens, brace)));
        return elements(body).stream().map(e -> entry(path, tokens.get(name).text(), e)).flatMap(Optional::stream).toList();
    }

    /** One {@code {"name", "signature", function}} entry; preprocessor lines before its brace are skipped. */
    private static Optional<Entry> entry(String path, String table, List<Token> element) {
        int open = IntStream.range(0, element.size()).filter(i -> element.get(i).text().equals("{")).findFirst().orElse(-1);
        if (open < 0) return Optional.empty();
        List<List<Token>> parts = elements(element.subList(open + 1, Math.max(open + 1, close(element, open))));
        Optional<String> name = parts.get(0).stream().filter(t -> t.kind() == Kind.STRING).findFirst().map(t -> unquote(t.text()));
        Optional<String> signature = parts.size() > 1 ? literal(parts.get(1)) : Optional.empty();
        Optional<String> function = parts.size() > 2 ? last(parts.get(2)) : Optional.empty();
        return name.map(n -> new Entry(path, table, n, signature, function));
    }

    /** The text of string literals, possibly behind a {@code (char *)} cast; empty when anything else takes part. */
    private static Optional<String> literal(List<Token> part) {
        boolean plain = part.stream().allMatch(t -> t.kind() == Kind.STRING || CAST.contains(t.text()));
        String text = part.stream().filter(t -> t.kind() == Kind.STRING).map(t -> unquote(t.text())).collect(Collectors.joining());
        return plain && !text.isEmpty() ? Optional.of(text) : Optional.empty();
    }

    /** The last name in a function pointer expression: {@code (void *) &f}, {@code FN_PTR(f)}, {@code reinterpret_cast<void *>(f)}. */
    private static Optional<String> last(List<Token> part) {
        return part.stream().filter(t -> t.kind() == Kind.ID).reduce((a, b) -> b).map(Token::text);
    }

    /** The parts of a bracket group's content, split at the commas outside nested brackets. */
    private static List<List<Token>> elements(List<Token> content) {
        List<List<Token>> parts = new ArrayList<>(List.of(new ArrayList<>()));
        int depth = 0;
        for (Token t : content) {
            if (OPEN.contains(t.text())) depth++;
            if (CLOSE.contains(t.text())) depth--;
            if (depth == 0 && t.text().equals(",")) parts.add(new ArrayList<>());
            else parts.get(parts.size() - 1).add(t);
        }
        return parts;
    }

    /** The index of the bracket that closes the one at {@code open}; the size when it is not closed. */
    static int close(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (OPEN.contains(tokens.get(i).text())) depth++;
            if (CLOSE.contains(tokens.get(i).text()) && --depth == 0) return i;
        }
        return tokens.size();
    }

    private static String unquote(String literal) {
        return literal.length() < 2 ? "" : literal.substring(1, literal.length() - 1);
    }
}
