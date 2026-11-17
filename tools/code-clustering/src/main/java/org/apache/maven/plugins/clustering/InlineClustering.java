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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.TypeElement;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * The javac plugin {@value #NAME}: atomizes the sources of the compilation that runs it, from the trees javac has
 * already built, so that a build gets its atom tables without a second parse and without a separate goal.
 * <pre>
 * javac -Xplugin:"CodeClustering out=&lt;dir&gt; minTokens=12 radius=3" -processorpath &lt;plugin.jar&gt; ...
 * </pre>
 * <ul>
 *   <li>after PARSE, each compilation unit goes through the same atom collection as {@link AtomPlanes#scan} (the tree
 *       is still the parser's: javac adds default constructors and record members only later, in ENTER);</li>
 *   <li>after ANALYZE, the methods declared {@code native} of each attributed class are recorded with their binary
 *       class names and argument descriptors ({@link NativeMethods});</li>
 *   <li>when the compilation ends, the units of this run are merged into the tables already in {@code out} (replace
 *       them with {@code full=true}) and every table is written again ({@link InlineTables}). Sources under
 *       {@code generated} get their own tables in {@code out/generated}.</li>
 * </ul>
 * The plugin never fails a compilation: bad arguments disable it and any failure, errors such as a stack overflow
 * included, is logged as a warning through {@link System.Logger} (java.util.logging by default); a
 * {@link LinkageError} (a class missing from the plugin jar) turns it off after one warning. Only virtual machine
 * errors other than {@link StackOverflowError} propagate. Arguments: {@link InlineOptions}. {@link #prune} drops the
 * rows of deleted sources without a compilation.
 */
public final class InlineClustering implements Plugin {
    static final String NAME = "CodeClustering";
    /** The directory under {@code out} of the tables of generated sources. */
    static final String GENERATED = "generated";
    private static final System.Logger LOG = System.getLogger(InlineClustering.class.getName());

    @Override
    public String getName() {
        return NAME;
    }

    /** One step of the listener. */
    @FunctionalInterface
    interface Step {
        void run() throws IOException;
    }

    /**
     * Runs the step; logs its failure as one warning and returns {@code true} when it failed. A virtual machine error
     * other than {@link StackOverflowError} (out of memory, an internal error) is rethrown.
     */
    static boolean guarded(String what, Step step) {
        Optional<Throwable> failure = attempt(step);
        failure.ifPresent(f -> warn(what + " not recorded: " + f, f));
        return failure.isPresent();
    }

    /** Runs the step and returns what it threw, empty when it did not; rethrows {@link #fatal} errors. */
    static Optional<Throwable> attempt(Step step) {
        try {
            step.run();
            return Optional.empty();
        } catch (Throwable failure) {
            if (fatal(failure)) throw (Error) failure;
            return Optional.of(failure);
        }
    }

    /** A virtual machine error other than {@link StackOverflowError}: out of memory, an internal error. */
    static boolean fatal(Throwable failure) {
        return failure instanceof VirtualMachineError && !(failure instanceof StackOverflowError);
    }

    private static void warn(String message, Throwable failure) {
        LOG.log(Level.WARNING, "code clustering: " + message);
        LOG.log(Level.DEBUG, "code clustering failure", failure);
    }

    /**
     * Adds the listener. Bad arguments, and a plugin jar that misses a class ({@link LinkageError}), disable the
     * plugin with one warning.
     */
    @Override
    public void init(JavacTask task, String... args) {
        attempt(() -> task.addTaskListener(new Listener(task, InlineOptions.parse(args))))
                .ifPresent(f -> LOG.log(Level.WARNING, "code clustering plugin disabled: " + f));
    }

    /**
     * Drops the rows of source files that no longer exist from the tables that the plugin arguments name ({@code out},
     * and {@code out/generated} with {@code generated}), without a compilation: for a build that compiles stale
     * sources only and found nothing stale after a file was deleted. Tables without such rows stay as they are.
     * Returns a summary line. The core extension calls it reflectively, from a class loader of the plugin jar.
     */
    public static String prune(String... args) throws IOException {
        InlineOptions options = InlineOptions.parse(args);
        String main = InlineTables.prune(options, options.out(), options.roots()).orElse("no source deleted");
        if (options.generated().isEmpty()) return main;
        Path gen = options.out().resolve(GENERATED);
        return main + " | generated: " + InlineTables.prune(options, gen, List.of(options.generated().get()))
                .orElse("no source deleted");
    }

    /**
     * Collects the atoms after PARSE and the native methods after ANALYZE; writes the tables at the end. The first
     * {@link LinkageError} (the plugin jar misses a class, or was replaced while javac runs) turns it off for the rest
     * of the compilation, with one warning and no tables written.
     */
    private static final class Listener implements TaskListener {
        private final InlineOptions options;
        private final JavacTask task;
        private final Trees trees;
        private final InlineTables.Units units;
        private final InlineTables.Units generated;
        private boolean off;

        Listener(JavacTask task, InlineOptions options) {
            this.options = options;
            this.task = task;
            this.trees = Trees.instance(task);
            this.units = new InlineTables.Units(options.roots());
            this.generated = new InlineTables.Units(options.generated().map(List::of).orElse(List.of()));
        }

        @Override
        public void finished(TaskEvent event) {
            if (off) return;
            attempt(() -> step(event)).ifPresent(f -> failed(event, f));
        }

        private void step(TaskEvent event) throws IOException {
            switch (event.getKind()) {
                case PARSE -> parsed(event.getCompilationUnit());
                case ANALYZE -> analyzed(event.getCompilationUnit(), event.getTypeElement());
                case COMPILATION -> written();
                default -> { }
            }
        }

        private void failed(TaskEvent event, Throwable failure) {
            off = failure instanceof LinkageError;
            String effect = off ? "; plugin off for this compilation, tables not written (incomplete plugin jar?)" : "";
            warn(event.getKind() + " not recorded: " + failure + effect, failure);
        }

        private void parsed(CompilationUnitTree unit) throws IOException {
            Optional<Path> file = UnitPaths.file(unit);
            if (file.isEmpty()) return;
            String path = UnitPaths.relative(file.get(), unit, roots(file.get()));
            List<Atom> atoms = new ArrayList<>();
            AtomPlanes.collect(unit, trees.getSourcePositions(), path, atoms);
            units(file.get()).parsed(path, file.get(), atoms);
        }

        private void analyzed(CompilationUnitTree unit, TypeElement type) {
            Optional<Path> file = unit == null ? Optional.empty() : UnitPaths.file(unit);
            TreePath tree = type == null ? null : trees.getPath(type);
            if (file.isEmpty() || tree == null) return;
            String path = UnitPaths.relative(file.get(), unit, roots(file.get()));
            units(file.get()).natives(file.get(), NativeMethods.of(trees, task.getElements(), task.getTypes(), tree, path));
        }

        /** Writes the tables; a full compilation without generated sources removes the generated tables. */
        private void written() throws IOException {
            Path out = options.out();
            if (!units.isEmpty()) LOG.log(Level.DEBUG, "code clustering: " + InlineTables.write(options, out, units));
            Path gen = out.resolve(GENERATED);
            if (!generated.isEmpty()) LOG.log(Level.DEBUG, "code clustering: " + InlineTables.write(options, gen, generated));
            else if (options.full() && options.generated().isPresent()) InlineTables.clear(gen);
        }

        private boolean isGenerated(Path file) {
            return options.generated().filter(file::startsWith).isPresent();
        }

        private List<Path> roots(Path file) {
            return isGenerated(file) ? List.of(options.generated().orElseThrow()) : options.roots();
        }

        private InlineTables.Units units(Path file) {
            return isGenerated(file) ? generated : units;
        }
    }
}
