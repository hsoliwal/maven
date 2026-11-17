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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The brace blocks of one file. A header runs from the last {@code ;}, brace, Objective-C directive or preprocessor
 * line to the brace that opens the block, and {@link CFamilyHeader} tells what the block is. A C, C++ or Objective-C
 * preprocessor line inside an open parenthesis or square bracket ({@code #ifdef} among parameters) is skipped and the
 * header goes on; elsewhere it ends the header. When the header after preprocessor lines names nothing
 * ({@code #if ... __attribute__((hot)) #endif} before the brace), it is joined to the header before them. Preprocessor
 * tokens are never part of a header.
 */
final class BraceBlocks {
    private final String path;
    private final String text;
    private final List<Token> tokens;
    private final List<Atom> atoms;
    private final String extension;
    private final boolean objc;
    private final BitSet preprocessor;
    private final Deque<Frame> open = new ArrayDeque<>();
    /** Where the current header starts. */
    private int boundary;
    /** Where the header started before the preprocessor lines since the last {@code ;} or brace; -1 without one. */
    private int joined = -1;
    /** Parentheses and square brackets open in the current header. */
    private int depth;
    /** Tokens before this index belong to an Objective-C directive already read. */
    private int skip;

    /** A header without preprocessor tokens, and the index in the file of each of its tokens. */
    private record Header(List<Token> tokens, int[] at) { }

    private BraceBlocks(String path, String text, List<Token> tokens, List<Atom> atoms, String extension) {
        this.path = path;
        this.text = text;
        this.tokens = tokens;
        this.atoms = atoms;
        this.extension = extension;
        this.objc = ObjectiveC.EXTENSIONS.contains(extension);
        this.preprocessor = Preprocessor.directives(tokens, extension);
    }

    static void scan(String path, String text, List<Token> tokens, List<Atom> atoms, String extension) {
        BraceBlocks blocks = new BraceBlocks(path, text, tokens, atoms, extension);
        IntStream.range(0, tokens.size()).forEach(blocks::step);
    }

    private void step(int i) {
        if (i < skip) return;
        if (preprocessor.get(i)) {
            directive(i);
            return;
        }
        int next = objc ? CFamilyHeader.objectiveC(path, text, tokens, i, open, atoms) : -1;
        if (next >= 0) {
            skip = next;
            restart(next);
        } else if (tokens.get(i).kind() == Kind.OP) {
            token(i, tokens.get(i).text());
        }
    }

    /** A preprocessor token: skipped inside an open bracket, else the header starts again after it. */
    private void directive(int i) {
        if (depth > 0) return;
        if (joined < 0) joined = boundary;
        boundary = i + 1;
    }

    private void token(int i, String t) {
        switch (t) {
            case "{" -> {
                open.push(frame(i));
                restart(i + 1);
            }
            case "}" -> close(i);
            case ";" -> restart(i + 1);
            case "(", "[" -> depth++;
            case ")", "]" -> depth = Math.max(0, depth - 1);
            default -> { }
        }
    }

    private void restart(int at) {
        boundary = at;
        joined = -1;
        depth = 0;
    }

    /** The block the brace at {@code i} opens; a header after preprocessor lines that names nothing joins the one before. */
    private Frame frame(int i) {
        Header recent = header(boundary, i);
        Frame frame = frame(recent, i);
        if (frame.kind() != null || joined < 0 || CFamilyHeader.named(recent.tokens())) return frame;
        Frame earlier = frame(header(joined, i), i);
        return earlier.kind() != null ? earlier : frame;
    }

    private Frame frame(Header header, int brace) {
        Frame frame = CFamilyHeader.frame(header.tokens(), 0, extension);
        return frame.at(frame.headerStart() < header.at().length ? header.at()[frame.headerStart()] : brace);
    }

    private Header header(int from, int to) {
        int[] at = IntStream.range(from, to).filter(k -> !preprocessor.get(k)).toArray();
        return new Header(Arrays.stream(at).mapToObj(tokens::get).toList(), at);
    }

    /** Closes the innermost block, not an {@code @implementation}; a block with a kind becomes an atom. */
    private void close(int i) {
        if (open.isEmpty() || open.peek().container()) return;
        Frame frame = open.pop();
        if (frame.kind() != null) {
            atoms.add(LexicalPlanes.atom(path, text, tokens.subList(frame.headerStart(), i + 1), frame,
                    LexicalAtoms.owners(open), 0));
        }
        restart(i + 1);
    }
}
