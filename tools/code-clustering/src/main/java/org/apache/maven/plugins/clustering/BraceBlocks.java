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
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.LexicalAtoms.Frame;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * The brace blocks of one file. A header runs from the last {@code ;}, brace, Objective-C directive or preprocessor
 * line to the brace that opens the block, and {@link CFamilyHeader} tells what the block is. A C, C++ or Objective-C
 * preprocessor line inside an open parenthesis or square bracket ({@code #ifdef} among parameters) is skipped and the
 * header goes on; elsewhere it ends the header. Preprocessor tokens are never part of a header.
 *
 * <p>When the header after preprocessor lines names nothing ({@code #if ... __attribute__((hot)) #endif} before the
 * brace), it is joined to the header before them, but only outside function bodies, and only when the joined header
 * is a type or an Objective-C method, or ends in the parameter list of the function it names plus trailing specifiers
 * ({@link CFunctionName#endsInParameters}: no {@code =}, no control word). So {@code UNUSED(x) #endif #if .. if (m)
 * #endif {} } in a function body and {@code XVEC(1, 2), ... #else {1, 2} } in an array initializer are not functions.
 *
 * <p>In C++ files ({@link #CPP}), a {@code :} outside brackets and function bodies right after such a header starts a
 * member-initializer list. Until the constructor's body, preprocessor lines are skipped, and an opening brace that
 * directly follows an element's qualified name ({@code fOrigin{0, 0}}, {@code Base<T>{a}}), or comes inside open
 * parentheses or another initializer brace ({@code ({150, 150})}, a lambda body), belongs to the list, as do the
 * semicolons inside it. The body is the first other opening brace: after {@code )}, a closing brace, or a macro that
 * stands for an initializer ({@code st STATE_DEFAULT {}}). Outside function bodies, a default argument inside
 * parentheses in C++ ({@code SkVector advance = {0, 0}}) opens no block either; any other brace inside parentheses
 * there (a lambda body) still opens one.
 */
final class BraceBlocks {
    /** Extensions of files that may hold C++ constructors. */
    static final Set<String> CPP = Set.of("cc", "cpp", "cxx", "hpp", "hh", "h", "mm");
    private final String path;
    private final String text;
    private final List<Token> tokens;
    private final List<Atom> atoms;
    private final String extension;
    private final boolean objc;
    private final boolean cpp;
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
    /** Whether the current header has reached a member-initializer {@code :}. */
    private boolean initializer;
    /** Braces open inside the member-initializer list, or in a default argument outside function bodies in C++. */
    private int braces;
    /** Where the current element of the member-initializer list starts (after its {@code :} or {@code ,}). */
    private int element;
    /** The brace that opened the counted braces of the current initializer-list element; -1 without one. */
    private int counted = -1;

    /** A header without preprocessor tokens, and the index in the file of each of its tokens. */
    private record Header(List<Token> tokens, int[] at) { }

    private BraceBlocks(String path, String text, List<Token> tokens, List<Atom> atoms, String extension) {
        this.path = path;
        this.text = text;
        this.tokens = tokens;
        this.atoms = atoms;
        this.extension = extension;
        this.objc = ObjectiveC.EXTENSIONS.contains(extension);
        this.cpp = CPP.contains(extension);
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

    /** A preprocessor token: skipped inside an open bracket or initializer list, else the header starts again after it. */
    private void directive(int i) {
        if (depth > 0 || initializer) return;
        if (joined < 0) joined = boundary;
        boundary = i + 1;
    }

    private void token(int i, String t) {
        switch (t) {
            case "{" -> brace(i);
            case "}" -> closeBrace(i);
            case ";" -> semicolon(i);
            case ":" -> colon(i);
            case "," -> element = initializer && depth == 0 && braces == 0
                    && InitializerList.openAngles(header(element, i).tokens()) == 0 ? i + 1 : element;
            case "(", "[" -> depth++;
            case ")", "]" -> depth = Math.max(0, depth - 1);
            default -> { }
        }
    }

    /**
     * A brace inside a member-initializer list, or a default argument inside parentheses outside function bodies in
     * C++ ({@code = {0, 0}}), counted in {@link #braces}; else a brace that opens a block.
     */
    private void brace(int i) {
        boolean nested = braces > 0 || depth > 0 && (initializer || cpp && !inFunction() && braceInitializer(i));
        if (nested || initializer && InitializerList.elementName(header(element, i).tokens())) {
            if (braces++ == 0 && depth == 0) counted = i;
            return;
        }
        open.push(frame(i));
        restart(i + 1);
    }

    /**
     * Whether the brace at {@code i} follows {@code =}: a default argument. Only that form counts, so that parentheses
     * left open by code the lexer cannot follow (a macro body continued after a comment) never swallow a namespace,
     * class or function body.
     */
    private boolean braceInitializer(int i) {
        int previous = preprocessor.previousClearBit(i - 1);
        return previous >= 0 && tokens.get(previous).text().equals("=");
    }

    /**
     * A closing brace: it closes a counted brace or the innermost block. When it closes an initializer-list element's
     * brace and the next token neither goes on with the list ({@code ,}, {@code :} of another {@code #if} branch,
     * {@code ...}) nor opens the body, that brace was the constructor body ({@code : Base(), INIT_MEMBERS { .. }}).
     */
    private void closeBrace(int i) {
        if (braces == 0) close(i);
        else if (--braces == 0 && initializer && depth == 0 && counted >= 0 && !continues(i)) {
            open.push(frame(counted));
            close(i);
        }
    }

    /** Whether the token after {@code i}, preprocessor lines skipped, goes on with an initializer list or opens the body. */
    private boolean continues(int i) {
        int next = preprocessor.nextClearBit(i + 1);
        return next < tokens.size() && InitializerList.GOES_ON.contains(tokens.get(next).text());
    }

    /**
     * A {@code ;} ends the header, except inside counted braces (a lambda body) or brackets of an initializer list.
     * Directly inside an initializer-list element's brace it proves that brace is the constructor body.
     */
    private void semicolon(int i) {
        if (initializer && depth == 0 && braces == 1 && counted >= 0) {
            open.push(frame(counted));
            restart(i + 1);
        } else if (braces == 0 && !(initializer && depth > 0)) {
            restart(i + 1);
        }
    }

    /**
     * A {@code :} right after a constructor header ({@link #constructorHead}) starts a member-initializer list (outside
     * brackets and function bodies, in C++ files); the header then runs on to the body. The header after preprocessor
     * lines is used when it is complete by itself, else the one joined to the header before them. Inside the list, a
     * {@code :} at its top level starts the list again (one in each {@code #if} branch).
     */
    private void colon(int i) {
        if (!cpp || depth > 0 || braces > 0 || inFunction()) return;
        if (initializer) {
            element = i + 1;
            return;
        }
        int start = joined >= 0 && !CFunctionName.endsInParameters(header(boundary, i).tokens()) ? joined : boundary;
        if (!InitializerList.constructorHead(header(start, i).tokens())) return;
        initializer = true;
        element = i + 1;
        boundary = start;
        joined = -1;
    }

    private void restart(int at) {
        boundary = at;
        joined = -1;
        depth = 0;
        initializer = false;
        braces = 0;
        counted = -1;
    }

    /** Whether a function body is open: no header inside one is joined across preprocessor lines. */
    private boolean inFunction() {
        return open.stream().anyMatch(f -> "METHOD".equals(f.kind()));
    }

    /**
     * The block the brace at {@code i} opens; a header after preprocessor lines that names nothing joins the one before
     * when that is a type, an Objective-C method or a header that ends in its function's parameter list.
     */
    private Frame frame(int i) {
        Header recent = header(boundary, i);
        Frame frame = frame(recent, i);
        if (frame.kind() != null || joined < 0 || CFamilyHeader.named(recent.tokens()) || inFunction()) return frame;
        Header whole = header(joined, i);
        Frame earlier = frame(whole, i);
        boolean joins = "TYPE".equals(earlier.kind()) || earlier.objc() || CFunctionName.endsInParameters(whole.tokens());
        return earlier.kind() != null && joins ? earlier : frame;
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
