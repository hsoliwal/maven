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

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The block a brace opens, with the C, C++ and Objective-C rules on top of the shared ones: in Objective-C a method
 * ({@link ObjectiveC}), nothing after another {@code @} directive, and {@code @implementation} ... {@code @end}; in all
 * three a function named by {@link CFunctionName}, and no atom for a {@code case} label ({@code case FOO(1): {}}).
 * {@link BraceBlocks} decides where a header starts: a preprocessor line ({@link Preprocessor}) inside an open
 * parenthesis or a member-initializer list is skipped, and a header after a preprocessor line that is not
 * {@link #named} is joined to the header before that line under the conditions {@link BraceBlocks} lists.
 */
final class CFamilyHeader {
    private CFamilyHeader() { }

    /** The block that the header tokens open; {@code offset} is the index of the first header token in the file. */
    static Frame frame(List<Token> header, int offset, String extension) {
        boolean objc = ObjectiveC.EXTENSIONS.contains(extension);
        Optional<ObjectiveC.Method> method = objc ? ObjectiveC.method(header) : Optional.empty();
        if (method.isPresent()) {
            return new Frame(offset + method.get().start(), "METHOD", method.get().selector(), method.get().args(), true);
        }
        if (objc && ObjectiveC.hasDirective(header)) return new Frame(offset, null, null, List.of());
        Frame frame = LexicalAtoms.classify(header, offset);
        boolean c = Preprocessor.EXTENSIONS.contains(extension);
        if (c && caseLabel(header, frame.headerStart() - offset)) {
            return new Frame(frame.headerStart(), null, null, List.of());
        }
        return c && CFunctionName.applies(header, offset, frame) ? CFunctionName.of(header, offset, frame) : frame;
    }

    /** Whether the header part that names the block starts with {@code case}: a C-family control word. */
    private static boolean caseLabel(List<Token> header, int start) {
        return start >= 0 && start < header.size() && header.get(start).text().equals("case");
    }

    /**
     * Whether a header has a name or keyword outside brackets, other than a specifier or qualifier that may follow a
     * parameter list ({@code __attribute__((hot))}, {@code const}, {@code noexcept}).
     */
    static boolean named(List<Token> header) {
        int[] depth = new int[header.size() + 1];
        IntStream.range(0, header.size()).forEach(i -> depth[i + 1] = Math.max(0, depth[i] + switch (header.get(i).text()) {
            case "(", "[" -> 1;
            case ")", "]" -> -1;
            default -> 0;
        }));
        return IntStream.range(0, header.size()).anyMatch(i -> depth[i] == 0 && name(header.get(i)));
    }

    private static boolean name(Token t) {
        boolean word = t.kind() == Kind.ID || t.kind() == Kind.KEYWORD;
        return word && !CFunctionName.TRAILING.contains(t.text()) && !CFunctionName.QUALIFIERS.contains(t.text());
    }

    /**
     * {@code @implementation Name} opens a TYPE block and {@code @end} closes it (with any block left open inside);
     * returns where the next header starts, or -1 when token {@code i} is neither.
     */
    static int objectiveC(String path, String text, List<Token> tokens, int i, Deque<Frame> open, List<Atom> atoms) {
        Optional<ObjectiveC.Implementation> implementation = ObjectiveC.implementation(tokens, i);
        implementation.ifPresent(m -> open.push(new Frame(i, "TYPE", m.name(), List.of(), true)));
        if (implementation.isPresent()) return implementation.get().next();
        if (!ObjectiveC.end(tokens, i)) return -1;
        if (open.stream().anyMatch(Frame::container)) {
            while (!open.peek().container()) open.pop();
            Frame type = open.pop();
            atoms.add(LexicalPlanes.atom(path, text, tokens.subList(type.headerStart(), i + 2), type,
                    LexicalAtoms.owners(open), 0));
        }
        return i + 2;
    }
}
