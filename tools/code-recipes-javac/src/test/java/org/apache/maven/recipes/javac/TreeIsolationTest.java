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

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.code.Symbol.RecordComponent;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener or plugin that runs after this one at the same ANALYZE event (Error Prone, NullAway, the Checker
 * Framework) must see javac's trees exactly as javac left them: rewrite's parser visitor writes types into the
 * annotation trees of record components ({@code extractRecordComponentAnnotations}), which the plugin must undo.
 */
class TreeIsolationTest {
    @Test
    void aLaterListenerSeesJavacsTreesUnchanged() throws IOException {
        Fixture fixture = IsolationFixtures.records();
        Path src = fixture.write(Javac.WORK.resolve("tree-isolation").toAbsolutePath());
        List<String> plain = observe(src, fixture, false);
        List<String> with = observe(src, fixture, true);
        assertFalse(plain.isEmpty());
        assertTrue(plain.stream().anyMatch(line -> line.startsWith("record ")), plain::toString);
        assertEquals(plain, with);
    }

    /** What a listener registered after the plugin sees at each ANALYZE event: every node's type and symbol. */
    private static List<String> observe(Path src, Fixture fixture, boolean plugin) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> seen = new ArrayList<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = task(javac, files, src, fixture, plugin);
            if (plugin) {
                Javac.registerPlugin(task, Javac.PluginArgs.DEFAULT.of(src.resolveSibling("census.tsv"), src));
            }
            task.addTaskListener(observer(seen));
            assertTrue(task.call());
        }
        return seen;
    }

    private static JavacTask task(
            JavaCompiler javac, StandardJavaFileManager files, Path src, Fixture fixture, boolean plugin) {
        String classes =
                src.resolveSibling(plugin ? "classes-plugin" : "classes-plain").toString();
        List<Path> sources = fixture.files().keySet().stream().map(src::resolve).toList();
        List<String> options = List.of("-proc:none", "-d", classes);
        return (JavacTask) javac.getTask(null, files, null, options, null, files.getJavaFileObjectsFromPaths(sources));
    }

    private static TaskListener observer(List<String> seen) {
        return new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ANALYZE) {
                    record((JCCompilationUnit) event.getCompilationUnit(), seen);
                }
            }
        };
    }

    private static void record(JCCompilationUnit unit, List<String> seen) {
        new Recorder(seen).scan(unit);
    }

    /** Every node's tag, type and symbol, and the annotation trees of every record component with their types. */
    private static final class Recorder extends TreeScanner {
        private final List<String> seen;

        Recorder(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public void scan(JCTree tree) {
            if (tree != null) {
                seen.add(tree.getTag() + " " + tree.type + " " + TreeInfo.symbolFor(tree));
            }
            super.scan(tree);
        }

        @Override
        public void visitClassDef(JCClassDecl tree) {
            if (tree.sym != null && tree.sym.isRecord()) {
                tree.sym.getRecordComponents().forEach(this::components);
            }
            super.visitClassDef(tree);
        }

        private void components(RecordComponent component) {
            for (JCAnnotation annotation : component.getOriginalAnnos()) {
                seen.add(
                        "record " + component + " " + annotation.annotationType + " " + annotation.annotationType.type);
            }
        }
    }
}
