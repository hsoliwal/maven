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

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.maven.plugins.clustering.Lexer.Kind;
import org.apache.maven.plugins.clustering.Lexer.Token;

/**
 * Preprocessor lines in the token stream of a C, C++ or Objective-C file: a {@code #} that is the first token of its
 * line starts a directive, which runs to the end of the line and on over every line that ends in a backslash. The
 * lexical front end ({@link BraceBlocks}) treats a directive as a boundary, so {@code #include} or
 * {@code #if defined(X)} before a function does not become part of its header, and a brace inside a directive opens
 * nothing; a directive inside an open parenthesis is skipped instead, and the header goes on.
 */
final class Preprocessor {
    static final Set<String> EXTENSIONS = Set.of("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm");

    private Preprocessor() { }

    /** The directive tokens of a file with one of the {@link #EXTENSIONS}; none for other files. */
    static BitSet directives(List<Token> tokens, String extension) {
        return EXTENSIONS.contains(extension) ? directives(tokens) : new BitSet();
    }

    static BitSet directives(List<Token> tokens) {
        BitSet marked = new BitSet();
        IntStream.range(0, tokens.size()).filter(i -> starts(tokens, i)).forEach(i -> marked.set(i, end(tokens, i) + 1));
        return marked;
    }

    /** Whether token {@code i} is a {@code #} at the start of its line. */
    static boolean starts(List<Token> t, int i) {
        return t.get(i).kind() == Kind.OP && t.get(i).text().equals("#")
                && (i == 0 || t.get(i - 1).endLine() < t.get(i).line());
    }

    /** The last token of the directive that starts at {@code i}. */
    static int end(List<Token> t, int i) {
        int j = i;
        while (j + 1 < t.size() && (t.get(j + 1).line() == t.get(j).endLine()
                || t.get(j).text().equals("\\") && t.get(j + 1).line() == t.get(j).endLine() + 1)) {
            j++;
        }
        return j;
    }
}
