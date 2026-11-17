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

import javax.tools.JavaFileObject;

import java.io.PrintWriter;
import java.io.Writer;

import com.sun.tools.javac.code.DeferredLintHandler;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.Log;

/**
 * The backstop around one conversion: nothing the conversion does may reach javac's output or counters. It is a
 * diagnostic handler of its own that counts and drops every diagnostic ({@code Log.DeferredDiagnosticHandler} would
 * pass {@code NON_DEFERRABLE} ones through); all five {@link Log.WriterKind} writers point to a null writer
 * ({@code printVerbose} and {@code printRawLines} bypass the handler); the deferred lint handler is immediate; and the
 * Log's source and its four counters are restored exactly. Tests assert that it never captures anything: the
 * conversion must not run the javac code that reports.
 *
 * <p>{@link #install()} and {@link #restore()} allocate nothing, so they also run under an OutOfMemoryError.
 */
final class LogSandbox extends Log.DiagnosticHandler {
    private static final Log.WriterKind[] KINDS = Log.WriterKind.values();

    private final Log log;
    private final DeferredLintHandler lint;
    private final JavaFileObject file;
    private final PrintWriter none = new PrintWriter(Writer.nullWriter());
    private final PrintWriter[] writers = new PrintWriter[KINDS.length];
    private final int[] counters = new int[4];
    private DiagnosticPosition lintPosition;
    private JavaFileObject source;
    private int captured;
    private String firstCode = "";
    private boolean handlerKept;

    LogSandbox(Context context, JavaFileObject file) {
        this.log = Log.instance(context);
        this.lint = DeferredLintHandler.instance(context);
        this.file = file;
    }

    /** Takes over javac's diagnostics, writers, deferred lint and source; allocates nothing. */
    void install() {
        counters[0] = log.nerrors;
        counters[1] = log.nwarnings;
        counters[2] = log.nsuppressederrors;
        counters[3] = log.nsuppressedwarns;
        for (int i = 0; i < KINDS.length; i++) {
            writers[i] = log.getWriter(KINDS[i]);
            log.setWriter(KINDS[i], none);
        }
        install(log);
        lintPosition = lint.immediate();
        source = log.useSource(file);
    }

    /**
     * Gives everything back to javac exactly as it was; allocates nothing. The handler goes last: javac asserts that
     * it is the top one, which fails only if the conversion left a handler of its own, a breach {@link #handlerKept()}
     * reports.
     */
    void restore() {
        log.useSource(source);
        lint.setPos(lintPosition);
        for (int i = 0; i < KINDS.length; i++) {
            log.setWriter(KINDS[i], writers[i]);
        }
        restoreCounters();
        try {
            log.popDiagnosticHandler(this);
        } catch (AssertionError stray) {
            handlerKept = true;
        }
    }

    private void restoreCounters() {
        log.nerrors = counters[0];
        log.nwarnings = counters[1];
        log.nsuppressederrors = counters[2];
        log.nsuppressedwarns = counters[3];
    }

    /** True when another diagnostic handler was left above this one, so it could not be removed. */
    boolean handlerKept() {
        return handlerKept;
    }

    @Override
    public void report(JCDiagnostic diagnostic) {
        if (captured++ == 0) {
            firstCode = diagnostic.getCode();
        }
    }

    /** How many diagnostics the conversion caused; javac never saw them. */
    int captured() {
        return captured;
    }

    /** The code of the first captured diagnostic, or the empty string. */
    String firstCode() {
        return firstCode;
    }
}
