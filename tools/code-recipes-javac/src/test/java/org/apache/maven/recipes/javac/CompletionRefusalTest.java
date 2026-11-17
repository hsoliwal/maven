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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type.ClassType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion refusal, alone: at the first ANALYZE event every class javac has not completed is refused, then
 * touched the way javac's lazy accessors touch classes ({@code complete()}, {@code getSuperclass()},
 * {@code getInterfaces()}, {@code getTypeParameters()}, {@code flags()}, {@code members()}), then restored. Every
 * snapshotted field and every completer must be the very object it was, nothing may be entered into the symbol
 * table, and the rest of the compile, in which javac itself completes {@code lib.Base} for {@code B}, must report
 * exactly what plain javac reports (without the field snapshot, {@code B} fails with "cannot find symbol").
 */
class CompletionRefusalTest {
    @Test
    void refusedClassesAreRestoredToTheVeryFieldsTheyHad() throws IOException {
        Fixture fixture = IsolationFixtures.completion(true).named("refusal");
        Path src = fixture.write(Javac.WORK.resolve("refusal").toAbsolutePath());
        List<String> mismatches = new ArrayList<>();
        int[] refusals = {0};
        String plain = compile(fixture, src, null, refusals);
        String refused = compile(fixture, src, mismatches, refusals);
        assertEquals(List.of(), mismatches);
        assertTrue(refusals[0] > 0, "nothing was refused");
        assertEquals(plain, refused);
    }

    /** Compiles with or without the refusal probe; returns diagnostics and class hashes as one text. */
    private static String compile(Fixture fixture, Path src, List<String> mismatches, int[] refusals)
            throws IOException {
        Path classes = Javac.emptyDirectory(src.resolveSibling("classes-" + (mismatches == null ? "plain" : "on")));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask)
                    javac.getTask(output, files, null, options(fixture, classes), null, sources(files, fixture, src));
            if (mismatches != null) {
                task.addTaskListener(probe(task, mismatches, refusals));
            }
            boolean success = task.call();
            return success + "\n" + output + Javac.hashes(classes);
        }
    }

    private static Iterable<? extends javax.tools.JavaFileObject> sources(
            StandardJavaFileManager files, Fixture fixture, Path src) {
        return files.getJavaFileObjectsFromPaths(
                fixture.files().keySet().stream().map(src::resolve).toList());
    }

    private static List<String> options(Fixture fixture, Path classes) {
        List<String> options = new ArrayList<>(fixture.options());
        options.addAll(List.of("-Xlint:all", "-proc:none", "-d", classes.toString()));
        return options;
    }

    private static TaskListener probe(JavacTask task, List<String> mismatches, int[] refusals) {
        return new TaskListener() {
            private boolean done;

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ANALYZE && !done) {
                    done = true;
                    refuseTouchRestore(Symtab.instance(((BasicJavacTask) task).getContext()), mismatches, refusals);
                }
            }
        };
    }

    private static void refuseTouchRestore(Symtab symtab, List<String> mismatches, int[] refusals) {
        Map<ClassSymbol, List<Object>> before = fields(symtab);
        CompletionRefusal refusal = new CompletionRefusal(symtab);
        refusal.install();
        before.keySet().forEach(CompletionRefusalTest::touch);
        refusal.restore();
        refusals[0] = refusal.refusals();
        compare(before, fields(symtab), mismatches);
        if (refusal.stray() != 0 || refusal.classesNow() != refusal.classes()) {
            mismatches.add("stray=" + refusal.stray() + " entered=" + (refusal.classesNow() - refusal.classes()));
        }
    }

    private static void compare(
            Map<ClassSymbol, List<Object>> before, Map<ClassSymbol, List<Object>> after, List<String> mismatches) {
        before.forEach((c, fields) -> {
            if (!sameObjects(fields, after.get(c))) {
                mismatches.add(c.flatName() + " " + fields + " -> " + after.get(c));
            }
        });
    }

    private static void touch(ClassSymbol c) {
        c.complete();
        c.getSuperclass();
        c.getInterfaces();
        c.getTypeParameters();
        c.flags();
        c.members();
    }

    /** Every class javac has not completed, with the identity of the fields the refusal restores. */
    private static Map<ClassSymbol, List<Object>> fields(Symtab symtab) {
        Map<ClassSymbol, List<Object>> fields = new java.util.IdentityHashMap<>();
        for (ClassSymbol c : symtab.getAllClasses()) {
            if (!c.completer.isTerminal()) {
                fields.put(c, fieldsOf(c));
            }
        }
        return fields;
    }

    private static List<Object> fieldsOf(ClassSymbol c) {
        List<Object> fields =
                new ArrayList<>(java.util.Arrays.asList(c.completer, c.flags_field, c.members_field, c.type, c.kind));
        if (c.type instanceof ClassType t) {
            fields.addAll(java.util.Arrays.asList(
                    t.supertype_field,
                    t.interfaces_field,
                    t.typarams_field,
                    t.allparams_field,
                    t.all_interfaces_field));
        }
        return fields;
    }

    private static boolean sameObjects(List<Object> a, List<Object> b) {
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i) && !(a.get(i) instanceof Long && a.get(i).equals(b.get(i)))) {
                return false;
            }
        }
        return true;
    }
}
