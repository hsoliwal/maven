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
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * JNI functions whose names a macro gives, which the lexical front end cannot see by name. Four forms: an object-like
 * alias, {@code #define FN Java_p_Lib_open}, used where a function is defined, {@code JNIEXPORT jint JNICALL FN(...)
 * {...}}; a name macro, {@code #define JNI_NAME(n) Java_p_Lib_##n}, used as {@code JNI_NAME(open)(JNIEnv *env, ...)
 * {...}}; a macro that defines whole functions, {@code #define DEFINE(NAME, T) ... Java_p_Lib_sort##NAME##0(JNIEnv *env,
 * ...) {...}}, instantiated as {@code DEFINE(Int, jint)}; and a defining macro whose function name is a parameter,
 * {@code #define HANDLE(function, m) ... function(JNIEnv *env, ...) {...}}, instantiated with the full name,
 * {@code HANDLE(Java_p_Lib_focusGained, fire)}. A name template is a {@code Java_} name or a parameter, followed by
 * {@code ##} pastes; the arguments of a use replace the macro's parameters, the parts are joined, and a result that is
 * a {@code Java_} name counts. A macro defined more than once (in {@code #if} branches) gives every definition's name.
 * Nothing else of the preprocessor is run.
 */
final class JniMacros {
    /** A function a macro use defines: its file, its expanded name, and the use as written ({@code DEFINE(Int,jint)}). */
    record Named(String path, String function, String written) { }

    /** A name in a macro body: its parts, and whether the body defines the function or only names it. */
    private record Template(List<String> parts, boolean defines) { }

    /** One definition: its parameters (none for an object-like macro, {@code functionLike} false) and name templates. */
    record Macro(List<String> params, boolean functionLike, List<Template> templates) { }

    private static final Pattern IDENTIFIER = Pattern.compile("Java_[A-Za-z0-9_]+");
    private static final String JAVA = "Java_";

    private JniMacros() { }

    /** The macros a file defines whose body has a name template, by macro name. */
    static Map<String, List<Macro>> macros(List<Token> tokens) {
        BitSet directives = Preprocessor.directives(tokens);
        Map<String, List<Macro>> macros = new HashMap<>();
        directives.stream().filter(i -> Preprocessor.starts(tokens, i)).forEach(i -> macro(tokens, i, macros));
        return macros;
    }

    /** The functions that uses of the macros in a file define. */
    static List<Named> functions(String path, List<Token> tokens, Map<String, List<Macro>> macros) {
        if (macros.isEmpty()) return List.of();
        BitSet directives = Preprocessor.directives(tokens);
        return IntStream.range(0, tokens.size()).filter(i -> !directives.get(i) && macros.containsKey(tokens.get(i).text()))
                .boxed().flatMap(i -> macros.get(tokens.get(i).text()).stream().flatMap(m -> uses(path, tokens, i, m).stream()))
                .distinct().toList();
    }

    /** {@code # define NAME(params) body} or {@code # define NAME body}: kept when the body has a name template. */
    private static void macro(List<Token> t, int at, Map<String, List<Macro>> macros) {
        int end = Preprocessor.end(t, at);
        if (at + 3 > end || !t.get(at + 1).text().equals("define") || t.get(at + 2).kind() != Kind.ID) return;
        boolean functionLike = t.get(at + 3).text().equals("(") && t.get(at + 2).end() == t.get(at + 3).start();
        int close = functionLike ? JniRegistrations.close(t, at + 3) : at + 2;
        if (close >= end) return;
        List<String> params = t.subList(functionLike ? at + 4 : close, close).stream()
                .filter(p -> p.kind() == Kind.ID || p.kind() == Kind.KEYWORD).map(Token::text).toList();
        List<Template> templates = templates(t.subList(close + 1, end + 1), params);
        if (!templates.isEmpty()) {
            macros.computeIfAbsent(t.get(at + 2).text(), k -> new ArrayList<>()).add(new Macro(params, functionLike, templates));
        }
    }

    /**
     * The name templates of a macro body: each paste chain that starts with a {@code Java_} name, or with a parameter
     * and defines a function. Line continuations are dropped.
     */
    private static List<Template> templates(List<Token> tokens, List<String> params) {
        List<Token> body = tokens.stream().filter(b -> !b.text().equals("\\")).toList();
        return IntStream.range(0, body.size()).filter(i -> i < 2 || !pasted(body, i - 3)).mapToObj(i -> template(body, i))
                .filter(m -> m.parts().get(0).startsWith(JAVA) || m.defines() && params.contains(m.parts().get(0))).toList();
    }

    /** The parts joined by {@code ##} from {@code i}; it defines the function when {@code (parameters) {...}} follows. */
    private static Template template(List<Token> body, int i) {
        List<String> parts = new ArrayList<>(List.of(body.get(i).text()));
        int j = i;
        while (pasted(body, j)) {
            parts.add(body.get(j + 3).text());
            j += 3;
        }
        return new Template(parts, followedByBody(body, j + 1));
    }

    private static boolean pasted(List<Token> body, int j) {
        return j + 3 < body.size() && body.get(j + 1).text().equals("#") && body.get(j + 2).text().equals("#")
                && (body.get(j + 3).kind() == Kind.ID || body.get(j + 3).kind() == Kind.NUMBER);
    }

    /** {@code (parameters) {...}} at {@code i}: a function definition. */
    private static boolean followedByBody(List<Token> t, int i) {
        if (i >= t.size() || !t.get(i).text().equals("(")) return false;
        int close = JniRegistrations.close(t, i);
        return close + 1 < t.size() && t.get(close + 1).text().equals("{");
    }

    /**
     * The functions one use defines: every defining template, and a name template where a definition follows the use
     * ({@code NAME(args)(parameters) {}} for a function-like macro, {@code NAME(parameters) {}} for an object-like one).
     */
    private static List<Named> uses(String path, List<Token> t, int i, Macro macro) {
        if (!macro.functionLike()) return expand(path, macro, List.of(), t.get(i).text(), followedByBody(t, i + 1));
        if (i + 1 >= t.size() || !t.get(i + 1).text().equals("(")) return List.of();
        int close = JniRegistrations.close(t, i + 1);
        List<String> args = arguments(t.subList(i + 2, Math.min(close, t.size())));
        if (args.size() != macro.params().size()) return List.of();
        String written = t.get(i).text() + "(" + String.join(",", args) + ")";
        return expand(path, macro, args, written, followedByBody(t, close + 1));
    }

    private static List<Named> expand(String path, Macro macro, List<String> args, String written, boolean definition) {
        return macro.templates().stream().filter(m -> m.defines() || definition)
                .map(m -> m.parts().stream().map(p -> macro.params().contains(p) ? args.get(macro.params().indexOf(p)) : p)
                        .collect(Collectors.joining()))
                .filter(name -> IDENTIFIER.matcher(name).matches()).map(name -> new Named(path, name, written)).toList();
    }

    /** The arguments of a use, each as its tokens joined without spaces. */
    private static List<String> arguments(List<Token> inside) {
        List<String> args = new ArrayList<>(List.of(""));
        int depth = 0;
        for (Token token : inside) {
            String s = token.text();
            if (s.equals("(") || s.equals("[") || s.equals("{")) depth++;
            if (s.equals(")") || s.equals("]") || s.equals("}")) depth--;
            if (depth == 0 && s.equals(",")) args.add("");
            else args.set(args.size() - 1, args.get(args.size() - 1) + s);
        }
        return args.size() == 1 && args.get(0).isEmpty() ? List.of() : args;
    }
}
