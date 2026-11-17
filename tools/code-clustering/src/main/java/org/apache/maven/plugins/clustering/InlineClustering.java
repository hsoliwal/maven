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
 *   <li>when the compilation ends, the units of this run are merged into the tables already in {@code out} and every
 *       table is written again ({@link InlineTables}).</li>
 * </ul>
 * The plugin never fails a compilation: bad arguments disable it and any failure is logged as a warning through
 * {@link System.Logger} (java.util.logging by default). Arguments: {@link InlineOptions}.
 */
public final class InlineClustering implements Plugin {
    static final String NAME = "CodeClustering";
    private static final System.Logger LOG = System.getLogger(InlineClustering.class.getName());

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        try {
            task.addTaskListener(new Listener(task, InlineOptions.parse(args)));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "code clustering plugin disabled: " + e.getMessage());
        }
    }

    /** Collects the atoms after PARSE and the native methods after ANALYZE; writes the tables at the end. */
    private static final class Listener implements TaskListener {
        private final InlineOptions options;
        private final JavacTask task;
        private final Trees trees;
        private final InlineTables.Units units = new InlineTables.Units();

        Listener(JavacTask task, InlineOptions options) {
            this.options = options;
            this.task = task;
            this.trees = Trees.instance(task);
        }

        @Override
        public void finished(TaskEvent event) {
            try {
                switch (event.getKind()) {
                    case PARSE -> parsed(event.getCompilationUnit());
                    case ANALYZE -> analyzed(event.getCompilationUnit(), event.getTypeElement());
                    case COMPILATION -> written();
                    default -> { }
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                LOG.log(Level.WARNING, "code clustering: " + event.getKind() + " not recorded: " + e);
                LOG.log(Level.DEBUG, "code clustering failure", e);
            }
        }

        private void parsed(CompilationUnitTree unit) throws IOException {
            Optional<Path> file = UnitPaths.file(unit);
            if (file.isEmpty()) return;
            String path = UnitPaths.relative(file.get(), unit, options.roots());
            List<Atom> atoms = new ArrayList<>();
            AtomPlanes.collect(unit, trees.getSourcePositions(), path, atoms);
            units.parsed(path, file.get(), atoms);
        }

        private void analyzed(CompilationUnitTree unit, TypeElement type) {
            Optional<Path> file = unit == null ? Optional.empty() : UnitPaths.file(unit);
            TreePath tree = type == null ? null : trees.getPath(type);
            if (file.isEmpty() || tree == null) return;
            String path = UnitPaths.relative(file.get(), unit, options.roots());
            units.natives(path, NativeMethods.of(trees, task.getElements(), task.getTypes(), tree, path));
        }

        private void written() throws IOException {
            if (units.isEmpty()) return;
            String summary = InlineTables.write(options, units);
            LOG.log(Level.DEBUG, () -> "code clustering: " + summary + " in " + options.out());
        }
    }
}
