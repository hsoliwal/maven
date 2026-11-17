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
package org.apache.maven.recipes.javac;

import com.sun.tools.javac.parser.DocCommentParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.parser.Tokens.Comment;
import com.sun.tools.javac.tree.DCTree.DCDocComment;
import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.Log;

/**
 * The doc comment table the conversion sees instead of javac's: comments and their text are read from javac's table
 * (map reads), and each doc tree is parsed new from javac's comment token, as javac's {@code LazyDocCommentTable}
 * does, so javac's cached doc trees are never created or written. That matters because {@code -Xdoclint}, whose
 * listener runs after this plugin's, would reuse a tree whose symbols someone else set. Without a doc context, the
 * table is {@link #NONE}: the conversion keeps every comment as text and still prints it exactly.
 */
final class FreshDocs implements DocCommentTable {
    /** A table with no doc comments. */
    static final DocCommentTable NONE = new FreshDocs(null, null, null);

    private final DocCommentTable javac;
    private final ParserFactory parsers;
    private final DiagnosticSource source;

    private FreshDocs(DocCommentTable javac, ParserFactory parsers, DiagnosticSource source) {
        this.javac = javac;
        this.parsers = parsers;
        this.source = source;
    }

    /** The table for one unit; javac's table may be absent (a unit without doc comments). */
    static DocCommentTable of(Context context, JCCompilationUnit unit) {
        return unit.docComments == null
                ? NONE
                : new FreshDocs(
                        unit.docComments,
                        ParserFactory.instance(context),
                        new DiagnosticSource(unit.sourcefile, Log.instance(context)));
    }

    @Override
    public boolean hasComment(JCTree tree) {
        return javac != null && javac.hasComment(tree);
    }

    @Override
    public Comment getComment(JCTree tree) {
        return javac == null ? null : javac.getComment(tree);
    }

    @Override
    public String getCommentText(JCTree tree) {
        return javac == null ? null : javac.getCommentText(tree);
    }

    @Override
    public DCDocComment getCommentTree(JCTree tree) {
        Comment comment = getComment(tree);
        return comment == null ? null : new DocCommentParser(parsers, source, comment).parse();
    }

    @Override
    public void putComment(JCTree tree, Comment comment) {
        // javac's table is never written by the conversion
    }
}
