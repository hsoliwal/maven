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
import java.lang.ref.SoftReference;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * included, is logged as a warning through {@link System.Logger} (java.util.logging by default). A
 * {@link LinkageError} (a class missing from the plugin jar, or compiled for a newer Java) and an
 * {@link OutOfMemoryError} turn it off after one warning: it drops what it holds, so javac goes on with the heap it
 * would have had without the plugin, and writes no tables. Between events the atoms are held softly, so the collector
 * clears them before javac itself would run out of heap (the plugin then turns off the same way). Only other virtual
 * machine errors propagate. Each javac
 * callback ({@link #init}, {@link TaskListener#finished}) runs its whole body inside one {@code try}, with no lambda
 * or other class of the plugin linked before it. Whenever a compilation's tables cannot be written, the tables in
 * {@code out} are removed, so no tables of an earlier compilation stay as if they were this one's
 * ({@link InlineTables#discard}). Arguments: {@link InlineOptions}. {@link #prune} drops the rows of deleted sources
 * without a compilation.
 */
public final class InlineClustering implements Plugin {
    static final String NAME = "CodeClustering";
    /** The directory under {@code out} of the tables of generated sources. */
    static final String GENERATED = "generated";
    /**
     * The files of a table set ({@link InlineTables}). Kept here, in the one class the core extension checks before it
     * wires the plugin, so that a failed init can remove a set without any other class of the plugin.
     */
    static final List<String> TABLE_FILES = List.of("ATOM_HASHES.tsv", "CLUSTERS.tsv", "PLANE_SUMMARY.tsv",
            "FILE_PAIRS.tsv", "FILE_CLUSTERS.tsv", "JNI.tsv", "ATOM_SOURCES.tsv");
    /**
     * The marker of a set that holds only the sources compiled since its tables were removed. The core extension
     * writes the same file when it leaves a merging execution alone.
     */
    static final String INCOMPLETE = "INCOMPLETE.txt";
    static final String INCOMPLETE_TEXT = "These tables hold only the sources compiled since the tables were removed"
            + " (a compilation that could not write them, or an execution the core extension left alone). A full"
            + " compilation (mvn clean compile) writes the whole set again.\n";
    private static final String OUT = "out=";
    private static final String URI_SCHEME = "file:";
    private static final System.Logger LOG = System.getLogger(InlineClustering.class.getName());

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Whether a failure of the plugin propagates: a virtual machine error other than {@link StackOverflowError} and
     * {@link OutOfMemoryError} (an internal error). Everything else is logged and, when it is a {@link #disabling}
     * one, turns the plugin off.
     */
    static boolean fatal(Throwable failure) {
        return failure instanceof VirtualMachineError && !(failure instanceof StackOverflowError)
                && !(failure instanceof OutOfMemoryError);
    }

    /** A failure after which the plugin stops for the compilation: a class it cannot link, or the heap ran out. */
    static boolean disabling(Throwable failure) {
        return failure instanceof LinkageError || failure instanceof OutOfMemoryError;
    }

    private static void warn(String message, Throwable failure) {
        LOG.log(Level.WARNING, "code clustering: " + message);
        if (failure != null) LOG.log(Level.DEBUG, "code clustering failure", failure);
    }

    /**
     * Adds the listener. Bad arguments, and a plugin jar that misses a class ({@link LinkageError}), disable the
     * plugin with one warning and remove the tables {@code out=} names ({@link #forget}). The whole body is one
     * {@code try}: nothing of the plugin links before it.
     */
    @Override
    public void init(JavacTask task, String... args) {
        try {
            task.addTaskListener(new Listener(task, InlineOptions.parse(args)));
        } catch (Throwable failure) {
            if (fatal(failure)) throw (Error) failure;
            LOG.log(Level.WARNING, "code clustering plugin disabled: " + failure);
            forget(args);
        }
    }

    /**
     * Removes the table sets of the first {@code out=} word (a file URI, as the core extension passes it, or a path),
     * as {@link #forget(Path, boolean)} does.
     */
    private static void forget(String... args) {
        try {
            for (String word : args) {
                if (!word.startsWith(OUT) || word.length() == OUT.length()) continue;
                String value = word.substring(OUT.length());
                forget(value.startsWith(URI_SCHEME) ? Path.of(URI.create(value)) : Path.of(value),
                        List.of(args).contains("full=true"));
                return;
            }
        } catch (Throwable failure) {
            if (fatal(failure)) throw (Error) failure;
            LOG.log(Level.DEBUG, "code clustering: tables not removed", failure);
        }
    }

    /**
     * Removes the table sets in {@code out} and {@code out/generated} with JDK classes only (another class of the plugin
     * may be the one that is missing); without {@code full} an existing directory is marked {@value #INCOMPLETE}. Best
     * effort: a failure is logged at DEBUG level.
     */
    static void forget(Path out, boolean full) {
        try {
            remove(out, full);
            remove(out.resolve(GENERATED), full);
        } catch (Throwable failure) {
            if (fatal(failure)) throw (Error) failure;
            LOG.log(Level.DEBUG, "code clustering: tables not removed", failure);
        }
    }

    private static void remove(Path dir, boolean full) throws IOException {
        if (!Files.isDirectory(dir)) return;
        for (String table : TABLE_FILES) Files.deleteIfExists(dir.resolve(table));
        if (!full) Files.writeString(dir.resolve(INCOMPLETE), INCOMPLETE_TEXT, StandardCharsets.UTF_8);
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
     * Collects the atoms after PARSE and the native methods after ANALYZE; writes the tables at the end. Between events
     * the units (main and generated sources) are held only softly: the collector clears them before javac itself would
     * run out of heap, so what the plugin holds never fails javac; the plugin then turns off with one warning and
     * writes no tables. The first {@link #disabling} failure (the plugin jar misses a class, or was replaced while
     * javac runs; the heap ran out in the plugin's own work) turns it off too, the units dropped. Turning off, and a
     * failed COMPILATION step, remove the tables in {@code out}.
     */
    private static final class Listener implements TaskListener {
        private final InlineOptions options;
        private final JavacTask task;
        private final Trees trees;
        private final SoftReference<InlineTables.Units[]> held;
        private boolean off;

        Listener(JavacTask task, InlineOptions options) {
            this.options = options;
            this.task = task;
            this.trees = Trees.instance(task);
            this.held = new SoftReference<>(new InlineTables.Units[] {new InlineTables.Units(options.roots()),
                new InlineTables.Units(options.generated().map(List::of).orElse(List.of()))});
        }

        /** One step per event; the whole body is one {@code try}, nothing of the plugin links before it. */
        @Override
        public void finished(TaskEvent event) {
            if (off) return;
            try {
                InlineTables.Units[] units = held.get();
                if (units == null) cleared(event);
                else step(event, units);
            } catch (Throwable failure) {
                if (fatal(failure)) throw (Error) failure;
                failed(event, failure);
            }
        }

        private void step(TaskEvent event, InlineTables.Units[] units) throws IOException {
            TaskEvent.Kind kind = event.getKind();
            if (kind == TaskEvent.Kind.PARSE) parsed(event.getCompilationUnit(), units);
            else if (kind == TaskEvent.Kind.ANALYZE) analyzed(event.getCompilationUnit(), event.getTypeElement(), units);
            else if (kind == TaskEvent.Kind.COMPILATION) written(units);
        }

        /** The collector cleared the units to give javac heap: off, one warning, the tables removed. */
        private void cleared(TaskEvent event) {
            off = true;
            warn(event.getKind() + " not recorded: the heap ran short and the collector dropped the plugin's atoms;"
                    + " plugin off for this compilation, tables not written (raise -Xmx)", null);
            discard();
        }

        /** Logs the failure once; a disabling one drops the units first, so the heap they held is free again. */
        private void failed(TaskEvent event, Throwable failure) {
            off = disabling(failure);
            if (off) held.clear();
            String effect = off ? "; plugin off for this compilation, tables not written ("
                    + (failure instanceof OutOfMemoryError ? "raise -Xmx" : "incomplete plugin jar?") + ")" : "";
            warn(event.getKind() + " not recorded: " + failure + effect, failure);
            if (off || event.getKind() == TaskEvent.Kind.COMPILATION) discard();
        }

        /**
         * Removes the tables this compilation cannot write ({@link InlineTables#discard}); when that fails, a class of
         * the plugin may be missing, so with JDK classes only ({@link #forget(Path, boolean)}).
         */
        private void discard() {
            try {
                InlineTables.discard(options);
            } catch (Throwable failure) {
                if (fatal(failure)) throw (Error) failure;
                LOG.log(Level.DEBUG, "code clustering: tables not removed, removing them with JDK classes", failure);
                forget(options.out(), options.full());
            }
        }

        private void parsed(CompilationUnitTree unit, InlineTables.Units[] units) throws IOException {
            Optional<Path> file = UnitPaths.file(unit);
            if (file.isEmpty()) return;
            String path = UnitPaths.relative(file.get(), unit, roots(file.get()));
            List<Atom> atoms = new ArrayList<>();
            AtomPlanes.collect(unit, trees.getSourcePositions(), path, atoms);
            units[index(file.get())].parsed(path, file.get(), atoms);
        }

        private void analyzed(CompilationUnitTree unit, TypeElement type, InlineTables.Units[] units) {
            Optional<Path> file = unit == null ? Optional.empty() : UnitPaths.file(unit);
            TreePath tree = type == null ? null : trees.getPath(type);
            if (file.isEmpty() || tree == null) return;
            String path = UnitPaths.relative(file.get(), unit, roots(file.get()));
            units[index(file.get())].natives(file.get(),
                    NativeMethods.of(trees, task.getElements(), task.getTypes(), tree, path));
        }

        /**
         * Writes the tables; a full compilation without generated sources removes the generated tables. Running out of
         * heap here, after javac's own work, turns the plugin off like anywhere else ({@link #failed}).
         */
        private void written(InlineTables.Units[] units) throws IOException {
            Path out = options.out();
            if (!units[0].isEmpty()) LOG.log(Level.DEBUG, "code clustering: " + InlineTables.write(options, out, units[0]));
            Path gen = out.resolve(GENERATED);
            if (!units[1].isEmpty()) LOG.log(Level.DEBUG, "code clustering: " + InlineTables.write(options, gen, units[1]));
            else if (options.full() && options.generated().isPresent()) InlineTables.clear(gen);
        }

        private boolean isGenerated(Path file) {
            return options.generated().filter(file::startsWith).isPresent();
        }

        private List<Path> roots(Path file) {
            return isGenerated(file) ? List.of(options.generated().orElseThrow()) : options.roots();
        }

        /** The units a file goes to: 0 the main ones, 1 those of generated sources. */
        private int index(Path file) {
            return isGenerated(file) ? 1 : 0;
        }
    }
}
