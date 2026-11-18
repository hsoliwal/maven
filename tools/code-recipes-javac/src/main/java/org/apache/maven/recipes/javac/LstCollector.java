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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.CompileStates;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import org.openrewrite.InMemoryExecutionContext;

/**
 * The plugin's task listener: counts the PARSE events of every file, builds a file's tree at the ANALYZE event of its
 * last top-level class (attributed and flowed, not yet desugared, under javac's default policy) inside an
 * {@link IsolationScope}, records the census values and drops the tree, records which top-level classes javac
 * generated, and at COMPILATION finished settles every row (a row is ok only if javac reported no error and generated
 * every top-level class of the file) and writes the census.
 *
 * <p>It never reports a diagnostic and never calls {@code JavaCompiler.errorCount()}, which with {@code -Werror}
 * would itself log an error; it reads {@link Log#nerrors}. It never throws into javac: a failure of its own, an
 * OutOfMemoryError included, is contained and stops it for the rest of the compile with one {@link Notices notice};
 * a closed heap gate does the same; a census that cannot be written is a notice too.
 */
public final class LstCollector implements TaskListener {
    private static final String AT_ANALYZE = "ANALYZE";
    private static final String AT_COMPILATION = "COMPILATION";

    private final Context context;
    private final PluginOptions options;
    private final BooleanSupplier headroom;
    private final Predicate<String> keep;
    private final List<Throwable> reported = new ArrayList<>();
    private final Map<String, FileState> files = new TreeMap<>();
    private final Map<JavaFileObject, FileState> byFile = new IdentityHashMap<>();
    private Optional<CompilerTreeLst> bridge = Optional.empty();
    private Optional<LstStatus> off = Optional.empty();

    private LstCollector(Context context, PluginOptions options, BooleanSupplier headroom, Predicate<String> keep) {
        this.context = context;
        this.options = options;
        this.headroom = headroom;
        this.keep = keep;
    }

    /**
     * The entry the boot plugin ({@code -Xplugin:MavenRecipes}) calls, through reflection, in the implementation's
     * class loader. No tree outlives its census row.
     *
     * @param task the task whose plugin is being initialised
     * @param headroom the heap gate, asked before each conversion
     * @param args the plugin options
     * @return the listener to register, empty (after one notice) when the options are not understood
     */
    public static Optional<TaskListener> listener(JavacTask task, BooleanSupplier headroom, String... args) {
        return start(task, headroom, path -> false, args).map(TaskListener.class::cast);
    }

    /**
     * Registers the listener on a task for a caller that runs javac in process, before the task runs.
     *
     * @param task the task, not yet run
     * @param keep which files (census paths) keep their trees for {@link #files()} and {@link #result(String)}
     * @param args the plugin options
     * @return the registered collector, empty (after one notice) when the options are not understood
     */
    public static Optional<LstCollector> register(JavacTask task, Predicate<String> keep, String... args) {
        Optional<LstCollector> collector = start(task, () -> true, keep, args);
        collector.ifPresent(task::addTaskListener);
        return collector;
    }

    private static Optional<LstCollector> start(
            JavacTask task, BooleanSupplier headroom, Predicate<String> keep, String... args) {
        try {
            Context context = ((BasicJavacTask) task).getContext();
            LstCollector collector = new LstCollector(context, PluginOptions.parse(args), headroom, keep);
            return VerboseGuard.off(task, context) ? Optional.empty() : Optional.of(collector.inContext());
        } catch (IllegalArgumentException e) {
            Notices.print(task, "options not understood, no trees and no census for this compile: " + e.getMessage());
        } catch (RuntimeException | LinkageError | AssertionError e) {
            Notices.print(task, "not started, no trees and no census for this compile: " + Notices.describe(e));
        }
        return Optional.empty();
    }

    /** Registers this collector in its compile's context, for {@link #of(JavacTask)}. */
    private LstCollector inContext() {
        context.put(LstCollector.class, this);
        return this;
    }

    /**
     * The collector registered on a task. Ask before the task runs: javac drops a task's context when {@code call()}
     * returns.
     *
     * @param task a task whose plugin was initialised
     * @return the collector, empty when the plugin was not initialised on that task or the task has finished
     */
    public static Optional<LstCollector> of(JavacTask task) {
        return Optional.ofNullable(((BasicJavacTask) task).getContext())
                .map(context -> context.get(LstCollector.class));
    }

    /** @return every parsed file, ordered by path; a tree only for the files the caller keeps */
    public List<FileLst> files() {
        return files.values().stream().map(FileState::fileLst).toList();
    }

    /** @return the census rows, ordered by path */
    public List<CensusRow> census() {
        return files.values().stream().map(FileState::row).toList();
    }

    /** @return the kept conversion result of a file, also for print mismatches; empty when none was kept */
    public Optional<LstResult> result(String path) {
        return Optional.ofNullable(files.get(path)).flatMap(FileState::result);
    }

    @Override
    public void finished(TaskEvent event) {
        try {
            switch (event.getKind()) {
                case PARSE -> parsed((JCCompilationUnit) event.getCompilationUnit());
                case ANALYZE -> analyzed(event);
                case GENERATE ->
                    state(event.getSourceFile()).ifPresent(s -> s.generated((Symbol) event.getTypeElement()));
                case COMPILATION -> compilationFinished();
                default -> {}
            }
        } catch (Throwable problem) {
            stop(problem);
        }
    }

    /** No trees for the rest of the compile, after a failure of the plugin's own; the census is still written. */
    private void stop(Throwable problem) {
        bridge = Optional.empty();
        if (off.isEmpty() || off.get() == LstStatus.HEAP) {
            off = Optional.of(LstStatus.STOPPED);
            Notices.print(context, "stopped, no trees for the rest of this compile: " + Notices.describe(problem));
        }
    }

    private Optional<FileState> state(JavaFileObject file) {
        return Optional.ofNullable(byFile.get(file));
    }

    private void parsed(JCCompilationUnit unit) {
        FileState state = files.computeIfAbsent(SourcePaths.key(unit.getSourceFile(), options.root()), FileState::new);
        state.parsed(unit);
        byFile.put(unit.getSourceFile(), state);
    }

    private void analyzed(TaskEvent event) {
        JCCompilationUnit unit = (JCCompilationUnit) event.getCompilationUnit();
        Optional<FileState> state = state(event.getSourceFile());
        if (state.isPresent()
                && event.getTypeElement() != null
                && state.get().analyzed(unit, (Symbol) event.getTypeElement())) {
            build(state.get(), unit, AT_ANALYZE);
        }
    }

    private void build(FileState state, JCCompilationUnit unit, String event) {
        Optional<LstStatus> skip = skipReason(state, unit);
        if (skip.isPresent()) {
            state.skip(skip.get());
        } else {
            convert(state, unit, event);
        }
    }

    private Optional<LstStatus> skipReason(FileState state, JCCompilationUnit unit) {
        if (hasErrors()) {
            return Optional.of(LstStatus.COMPILE_ERRORS);
        } else if (SourcePaths.isUnder(unit.getSourceFile(), options.buildDir())) {
            return Optional.of(LstStatus.GENERATED);
        } else if (unit.getModuleDecl() != null) {
            return Optional.of(LstStatus.MODULE_INFO);
        } else if (state.desugared(Enter.instance(context), CompileStates.instance(context))) {
            return Optional.of(LstStatus.DESUGARED);
        }
        return off.or(this::heapGate);
    }

    /** Closes the gate for the rest of the compile when the heap headroom is below the plugin's requirement. */
    private Optional<LstStatus> heapGate() {
        if (headroom.getAsBoolean()) {
            return Optional.empty();
        }
        bridge = Optional.empty();
        off = Optional.of(LstStatus.HEAP);
        Notices.print(context, "heap headroom below the gate, no trees for the rest of this compile");
        return off;
    }

    private void convert(FileState state, JCCompilationUnit unit, String event) {
        try {
            state.built(bridge().convert(unit, options.root()), event, keep.test(state.key()));
            reported.stream().findFirst().ifPresent(problem -> state.failed(problem.toString()));
        } catch (IsolationBreach breach) {
            state.failed("isolation: " + breach.getMessage());
            stop(breach);
        } catch (IOException | RuntimeException | Error e) {
            failed(state, e);
        } finally {
            reported.clear();
        }
    }

    /** A VirtualMachineError, also one javac wrapped, stops the plugin; any other problem fails the file only. */
    private void failed(FileState state, Throwable problem) {
        if (CompilerTreeLst.fatal(problem)) {
            state.failed("contained " + problem);
            stop(problem);
        } else {
            state.failed(problem.toString());
        }
    }

    private CompilerTreeLst bridge() {
        if (bridge.isEmpty()) {
            bridge = Optional.of(
                    new CompilerTreeLst(context, new InMemoryExecutionContext(reported::add), options.check()));
        }
        return bridge.get();
    }

    private boolean hasErrors() {
        return Log.instance(context).nerrors > 0;
    }

    private void compilationFinished() {
        files.values().stream().filter(FileState::pending).forEach(this::finishPending);
        boolean errors = hasErrors();
        files.values().forEach(state -> state.finish(errors));
        byFile.clear();
        bridge = Optional.empty();
        options.census().ifPresent(this::writeCensus);
    }

    /** A file without classes is built now; one with classes was not analyzed, or the plugin was off by then. */
    private void finishPending(FileState state) {
        Optional<JCCompilationUnit> classless = state.classless();
        if (classless.isPresent()) {
            build(state, classless.get(), AT_COMPILATION);
        } else {
            state.skip(off.orElse(LstStatus.INCOMPLETE));
        }
    }

    private void writeCensus(Path path) {
        LstCensus.write(path, options.censusJoined(), census()).ifPresent(problem -> Notices.print(context, problem));
    }
}
