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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The planes of a lexical atom, through {@link AtomBuilder}: every token is a node in order, names and literals as the
 * javac front end has them, and a member name after {@code .} or {@code ->} is kept like a literal. The names the atom
 * declares, numbered on the LOGIC plane, are its own name, its parameters, and names bound by a declaration keyword,
 * {@code :=}, a plain assignment, or {@code in}/{@code of}/{@code :} of a loop. The symbol is
 * {@code Owner.name(parameterCount)} for a METHOD, {@code Owner.selector:} for an Objective-C one, {@code Owner.Name}
 * for a TYPE.
 */
final class LexicalPlanes {
    private static final Set<String> PARAMETER_END = Set.of(",", ")", "=", ":");
    private static final Set<String> BEFORE_ASSIGNED = Set.of("*", ">", "]", ";", "{", "}", "INDENT", "DEDENT", ",");

    private LexicalPlanes() { }

    /**
     * The atom of a token span. Its lines run from the start of the first token to the end of the last, plus
     * {@code lineBase} when the tokens come from a slice of the file that starts on line {@code lineBase + 1}.
     */
    static Atom atom(String path, String text, List<Token> span, Frame frame, String owner, int lineBase) {
        int from = span.get(0).start();
        int to = span.get(span.size() - 1).end();
        AtomBuilder builder = AtomBuilder.atom(path, frame.kind(), symbol(frame, owner), text.substring(from, to))
                .lines(lineBase + span.get(0).line(), lineBase + span.get(span.size() - 1).endLine());
        declared(span, frame).forEach(builder::declare);
        IntStream.range(0, span.size()).forEach(i -> node(builder, span, i));
        return builder.build();
    }

    /** One token as a node: a member name after {@code .} or {@code ->} is kept like a literal. */
    private static void node(AtomBuilder builder, List<Token> span, int i) {
        Token t = span.get(i);
        boolean member = i > 0 && (span.get(i - 1).text().equals(".") || span.get(i - 1).text().equals("->"));
        switch (t.kind()) {
            case ID -> {
                if (member) builder.literal("ID", t.text());
                else builder.name("ID", t.text());
            }
            case NUMBER -> builder.literal("NUM", t.text());
            case STRING -> builder.literal("STR", t.text());
            default -> builder.node(t.text());
        }
    }

    /** {@code Owner.name(parameterCount)} for a METHOD, {@code Owner.selector:} for an Objective-C one, {@code Owner.Name} for a TYPE. */
    static String symbol(Frame frame, String owner) {
        String name = (owner.isEmpty() ? "" : owner + ".") + frame.name();
        return "METHOD".equals(frame.kind()) && !frame.objc() ? name + "(" + arity(frame.params()) + ")" : name;
    }

    private static int arity(List<Token> params) {
        if (params.isEmpty()) return 0;
        int depth = 0;
        int commas = 0;
        for (Token t : params) {
            String s = t.text();
            if (s.equals("(") || s.equals("[") || s.equals("{") || s.equals("<")) depth++;
            if ((s.equals(")") || s.equals("]") || s.equals("}") || s.equals(">")) && depth > 0) depth--;
            if (s.equals(",") && depth == 0) commas++;
        }
        return commas + 1;
    }

    /** Names the atom binds, in order: its own name, its parameters, then local bindings. */
    private static Set<String> declared(List<Token> span, Frame frame) {
        Set<String> declared = new LinkedHashSet<>();
        declared.add(frame.name());
        List<Token> params = frame.params();
        IntStream.range(0, params.size()).filter(i -> parameter(params, i)).forEach(i -> declared.add(params.get(i).text()));
        IntStream.range(0, span.size() - 1).filter(i -> binds(span, i)).forEach(i -> declared.add(span.get(i).text()));
        return declared;
    }

    private static boolean parameter(List<Token> params, int i) {
        String next = i + 1 < params.size() ? params.get(i + 1).text() : ")";
        return params.get(i).kind() == Kind.ID && PARAMETER_END.contains(next);
    }

    /** Whether name {@code i} is bound: after a declaration keyword, by an assignment, or as a loop variable. */
    private static boolean binds(List<Token> span, int i) {
        Token t = span.get(i);
        if (t.kind() != Kind.ID) return false;
        String prev = i > 0 ? span.get(i - 1).text() : ";";
        Token prevToken = i > 0 ? span.get(i - 1) : null;
        String next = span.get(i + 1).text();
        boolean afterKeyword = prevToken != null && prevToken.kind() == Kind.KEYWORD && LexicalAtoms.DECLARE.contains(prev);
        boolean assigned = next.equals("=") && (prevToken == null || prevToken.kind() == Kind.ID
                || BEFORE_ASSIGNED.contains(prev));
        boolean loop = next.equals("in") || next.equals("of") || next.equals(":=")
                || next.equals(":") && prevToken != null && prevToken.kind() == Kind.ID;
        return afterKeyword || assigned || loop;
    }
}
