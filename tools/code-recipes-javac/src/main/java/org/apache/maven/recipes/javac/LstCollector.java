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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.comp.CompileStates;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import org.openrewrite.InMemoryExecutionContext;

/**
 * Task listener of {@link MavenRecipesPlugin}: counts the PARSE events of every file, builds a file's tree at the
 * ANALYZE event of its last top-level class (attributed and flowed, not yet desugared), and at COMPILATION finished
 * drops every tree when the compile had errors and writes the census.
 *
 * <p>It never reports a diagnostic and never calls {@code JavaCompiler.errorCount()}, which with {@code -Werror}
 * would itself log an error; it reads {@link Log#nerrors}. It never throws into javac: a failure of its own stops it
 * for the rest of the compile with one {@link Notices notice}, and a census that cannot be written is a notice too.
 */
public final class LstCollector implements TaskListener {
    private static final String AT_ANALYZE = "ANALYZE";
    private static final String AT_COMPILATION = "COMPILATION";

    private final Context context;
    private final PluginOptions options;
    private final List<Throwable> reported = new ArrayList<>();
    private final CompilerTreeLst bridge;
    private final Map<String, FileState> files = new TreeMap<>();
    private final Map<CompilationUnitTree, FileState> byUnit = new IdentityHashMap<>();
    private boolean stopped;

    LstCollector(Context context, PluginOptions options) {
        this.context = context;
        this.options = options;
        this.bridge = new CompilerTreeLst(context, new InMemoryExecutionContext(reported::add));
    }

    /**
     * The collector the plugin registered on a task. Ask before the task runs: javac drops a task's context when
     * {@code call()} returns.
     *
     * @param task a task whose plugin was initialised
     * @return the collector, empty when the plugin was not initialised on that task or the task has finished
     */
    public static Optional<LstCollector> of(JavacTask task) {
        return Optional.ofNullable(((BasicJavacTask) task).getContext())
                .map(context -> context.get(LstCollector.class));
    }

    /** @return every parsed file, ordered by path */
    public List<FileLst> files() {
        return files.values().stream().map(FileState::fileLst).toList();
    }

    /** @return the census rows, ordered by path */
    public List<CensusRow> census() {
        return files.values().stream().map(FileState::row).toList();
    }

    /** @return the conversion result of a file, kept also for print mismatches; empty when none was built */
    public Optional<LstResult> result(String path) {
        return Optional.ofNullable(files.get(path)).flatMap(FileState::result);
    }

    @Override
    public void finished(TaskEvent event) {
        if (stopped) {
            return;
        }
        try {
            switch (event.getKind()) {
                case PARSE -> parsed((JCCompilationUnit) event.getCompilationUnit());
                case ANALYZE -> analyzed(event);
                case COMPILATION -> compilationFinished();
                default -> {}
            }
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            stop(e);
        }
    }

    /** Drops every tree and the census for the rest of the compile, after a failure of the plugin's own. */
    private void stop(Throwable problem) {
        stopped = true;
        files.clear();
        byUnit.clear();
        Notices.print(context, "stopped, no trees and no census for this compile: " + Notices.describe(problem));
    }

    private void parsed(JCCompilationUnit unit) {
        FileState state = files.computeIfAbsent(SourcePaths.key(unit.getSourceFile(), options.root()), FileState::new);
        state.parsed(unit);
        byUnit.put(unit, state);
    }

    private void analyzed(TaskEvent event) {
        FileState state = byUnit.get(event.getCompilationUnit());
        if (state != null && event.getTypeElement() != null && state.analyzed((Symbol) event.getTypeElement())) {
            build(state, AT_ANALYZE);
        }
    }

    private void build(FileState state, String event) {
        JCCompilationUnit unit = state.unit().orElseThrow();
        byUnit.remove(unit);
        if (hasErrors()) {
            state.skip(LstStatus.COMPILE_ERRORS);
        } else if (SourcePaths.isUnder(unit.getSourceFile(), options.buildDir())) {
            state.skip(LstStatus.GENERATED);
        } else if (unit.getModuleDecl() != null) {
            state.skip(LstStatus.MODULE_INFO);
        } else if (desugared(state)) {
            state.skip(LstStatus.DESUGARED);
        } else {
            convert(state, unit, event);
        }
    }

    private void convert(FileState state, JCCompilationUnit unit, String event) {
        int before = reported.size();
        try {
            state.built(bridge.convert(unit, options.root()), event);
        } catch (IOException | RuntimeException | LinkageError | StackOverflowError e) {
            state.failed(e.toString());
        }
        if (reported.size() > before) {
            state.failed(reported.get(before).toString());
        }
    }

    /**
     * True when javac already desugared a class of the file, so its trees no longer match the source: the class env
     * reached TRANSTYPES, or javac dropped the env, which it does once the class is generated (all classes of the file
     * were analyzed when this runs, so a missing env is never a class javac has not reached yet).
     */
    private boolean desugared(FileState state) {
        Enter enter = Enter.instance(context);
        CompileStates states = CompileStates.instance(context);
        return state.topLevelTypes().stream()
                .map(type -> Optional.ofNullable(enter.getEnv((TypeSymbol) type)))
                .anyMatch(env ->
                        env.map(e -> states.isDone(e, CompileState.TRANSTYPES)).orElse(true));
    }

    private boolean hasErrors() {
        return Log.instance(context).nerrors > 0;
    }

    private void compilationFinished() {
        files.values().stream().filter(FileState::pending).forEach(this::finishPending);
        if (hasErrors()) {
            files.values().forEach(state -> state.skip(LstStatus.COMPILE_ERRORS));
        }
        options.census().ifPresent(this::writeCensus);
    }

    /** A file without classes and without an ANALYZE event is built now; one with classes was not analyzed. */
    private void finishPending(FileState state) {
        if (state.unit().isPresent() && state.topLevelTypes().isEmpty()) {
            build(state, AT_COMPILATION);
        } else {
            state.skip(LstStatus.INCOMPLETE);
        }
    }

    private void writeCensus(Path path) {
        try {
            LstCensus.write(path, census());
        } catch (IOException | RuntimeException e) {
            Notices.print(context, "census not written to " + path + ": " + Notices.describe(e));
        }
    }
}
