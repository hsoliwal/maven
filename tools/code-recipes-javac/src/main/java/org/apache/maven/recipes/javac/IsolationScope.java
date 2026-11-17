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

import java.util.Optional;

import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.DocTreeMaker;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;

/**
 * Makes one conversion a read-only observer of javac. Opened in this order and closed in reverse:
 *
 * <ol>
 *   <li>{@link LogSandbox}: diagnostics, writers, counters, deferred lint, the Log's source;
 *   <li>{@link CompletionRefusal}: classes javac has not completed stay so;
 *   <li>{@link FreshDocs} in place of the unit's doc table, with the {@link ShadowContext} (javac's Attr never runs);
 *   <li>{@link RecordAnnotationGuard}: record-component annotation types.
 * </ol>
 *
 * Everything is set up before the first write to javac, so opening cannot stop half way, and closing restores
 * without allocating, so it also runs under an OutOfMemoryError. Then the tripwires are compared: a captured
 * diagnostic, a class added to the symbol table, a completer someone else replaced, a diagnostic handler left behind,
 * and with {@code check} the {@link TreeDigest}. Any of them makes {@link #close()} throw {@link IsolationBreach}.
 */
final class IsolationScope {
    private final JCCompilationUnit unit;
    private final Optional<Context> docContext;
    private final Context context;
    private final LogSandbox log;
    private final CompletionRefusal refusal;
    private final RecordAnnotationGuard records;
    private final DocCommentTable javacDocs;
    private final DocCommentTable freshDocs;
    private final DocTreeMaker docTrees;
    private final int docPosition;
    private final Optional<Long> digest;

    private IsolationScope(Context context, JCCompilationUnit unit, Optional<Context> docContext, boolean check) {
        this.unit = unit;
        this.context = context;
        this.docContext = docContext;
        this.records = new RecordAnnotationGuard(unit);
        this.digest = check ? Optional.of(TreeDigest.of(unit)) : Optional.empty();
        this.javacDocs = unit.docComments;
        this.freshDocs = docContext.map(shadow -> FreshDocs.of(context, unit)).orElse(FreshDocs.NONE);
        this.docTrees = DocTreeMaker.instance(context);
        this.docPosition = docTrees.pos;
        this.log = new LogSandbox(context, unit.getSourceFile());
        this.refusal = new CompletionRefusal(Symtab.instance(context));
    }

    /**
     * Opens the scope around one conversion.
     *
     * @param context the compile's context
     * @param unit the unit to convert
     * @param docContext the {@link ShadowContext} for Javadoc, or empty to keep doc comments as text
     * @param check whether to compare the {@link TreeDigest} too
     */
    static IsolationScope open(Context context, JCCompilationUnit unit, Optional<Context> docContext, boolean check) {
        IsolationScope scope = new IsolationScope(context, unit, docContext, check);
        scope.log.install();
        scope.refusal.install();
        unit.docComments = scope.freshDocs;
        return scope;
    }

    /** The context the conversion runs with: the shadow when Javadoc is converted, else the compile's. */
    Context visitorContext() {
        return docContext.orElse(context);
    }

    /** Restores javac's state (allocation-free), then fires the tripwires. */
    void close() {
        records.restore();
        unit.docComments = javacDocs;
        docTrees.pos = docPosition;
        refusal.restore();
        log.restore();
        breach().ifPresent(what -> {
            throw new IsolationBreach(what);
        });
    }

    private Optional<String> breach() {
        if (log.captured() > 0) {
            return Optional.of(log.captured() + " javac diagnostic(s), first " + log.firstCode());
        }
        if (log.handlerKept() || refusal.stray() > 0) {
            return Optional.of("javac state replaced: diagnostic handler or " + refusal.stray() + " completer(s)");
        }
        int added = refusal.classesNow() - refusal.classes();
        if (added != 0) {
            return Optional.of(added + " class(es) entered into javac's symbol table");
        }
        return digest.filter(before -> before != TreeDigest.of(unit)).map(before -> "javac's trees changed");
    }
}
