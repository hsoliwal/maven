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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;
import org.openrewrite.ExecutionContext;
import org.openrewrite.FileAttributes;
import org.openrewrite.internal.EncodingDetectingInputStream;
import org.openrewrite.java.internal.DefaultJavaTypeFactory;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.internal.JavaTypeFactory;
import org.openrewrite.java.isolated.ReloadableJava21ParserVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

/**
 * Builds the OpenRewrite tree ({@link J.CompilationUnit}) of one compilation unit from the trees a javac compile has
 * already parsed and attributed. It reads no source file through a parser: the text is the character content javac
 * parsed, and the tree, end positions, doc comments and symbols are javac's.
 *
 * <p>The conversion is OpenRewrite's own {@link ReloadableJava21ParserVisitor} (rewrite-java-21, Apache-2.0), the
 * class rewrite's {@code Java21Parser} runs after its private javac; here it runs on the build's javac instead. One
 * instance holds one {@link JavaTypeCache} and serves one compilation.
 */
public final class CompilerTreeLst {
    private final Context context;
    private final JavaTypeFactory typeFactory = new DefaultJavaTypeFactory(new JavaTypeCache());
    private final ExecutionContext executionContext;

    /**
     * @param context the javac context of the compile whose trees are converted
     * @param executionContext receives errors the conversion reports without throwing
     */
    public CompilerTreeLst(Context context, ExecutionContext executionContext) {
        this.context = context;
        this.executionContext = executionContext;
    }

    /**
     * Converts one compilation unit of a task; the design's entry point for callers that hold a {@link JavacTask}.
     *
     * @param task the running javac task
     * @param unit a unit of that task, attributed and not yet desugared
     * @param relativeTo the directory the tree's source path is made relative to
     * @param ctx receives errors the conversion reports without throwing
     * @return the OpenRewrite tree
     * @throws IOException if javac cannot give the unit's character content
     */
    public static J.CompilationUnit toLst(
            JavacTask task, CompilationUnitTree unit, Path relativeTo, ExecutionContext ctx) throws IOException {
        Context context = ((BasicJavacTask) task).getContext();
        return new CompilerTreeLst(context, ctx)
                .convert((JCCompilationUnit) unit, relativeTo)
                .lst();
    }

    /**
     * Converts one compilation unit and checks that the tree prints back the source.
     *
     * @param unit the unit, attributed and not yet desugared
     * @param root the directory the tree's source path is made relative to
     * @return the tree, whether it prints the exact source, and its method-call attribution counts
     * @throws IOException if javac cannot give the unit's character content
     */
    public LstResult convert(JCCompilationUnit unit, Path root) throws IOException {
        JavaFileObject file = unit.getSourceFile();
        String text = file.getCharContent(true).toString();
        Optional<Path> path = SourcePaths.ofFile(file);
        // J.CompilationUnit.withCharset is declared to return SourceFile
        J.CompilationUnit lst = (J.CompilationUnit)
                visit(unit, text, SourcePaths.relative(file, root), path).withCharset(compileCharset());
        return new LstResult(lst, printsSource(lst, text, path), MethodCalls.count(lst));
    }

    private J.CompilationUnit visit(JCCompilationUnit unit, String text, Path sourcePath, Optional<Path> path) {
        // UTF-8 carries any text javac decoded without loss; the unit's charset is set to the compile's afterwards
        EncodingDetectingInputStream source = new EncodingDetectingInputStream(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        FileAttributes attributes = path.map(FileAttributes::fromPath).orElse(null);
        ReloadableJava21ParserVisitor visitor = new ReloadableJava21ParserVisitor(
                sourcePath, attributes, source, List.of(), typeFactory, executionContext, context);
        return (J.CompilationUnit) visitor.scan(unit, Space.EMPTY);
    }

    private Charset compileCharset() {
        String encoding = Options.instance(context).get("-encoding");
        return encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    }

    private static boolean printsSource(J.CompilationUnit lst, String text, Optional<Path> path) throws IOException {
        if (!lst.printAll().equals(text)) {
            return false;
        }
        return path.isEmpty() || Arrays.equals(lst.printAllAsBytes(), Files.readAllBytes(path.get()));
    }
}
