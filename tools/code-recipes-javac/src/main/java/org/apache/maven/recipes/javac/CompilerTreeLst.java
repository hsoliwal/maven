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
import org.openrewrite.ExecutionContext;
import org.openrewrite.FileAttributes;
import org.openrewrite.internal.EncodingDetectingInputStream;
import org.openrewrite.java.internal.DefaultJavaTypeFactory;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.isolated.ReloadableJava21ParserVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

/**
 * Builds the OpenRewrite tree ({@link J.CompilationUnit}) of one compilation unit from the trees a javac compile has
 * already parsed and attributed. It reads no source file through a parser: the text is the character content javac
 * parsed, and the tree, end positions, doc comments and symbols are javac's.
 *
 * <p>The conversion is OpenRewrite's own {@link ReloadableJava21ParserVisitor} (rewrite-java-21, Apache-2.0), the
 * class rewrite's {@code Java21Parser} runs after its private javac; here it runs on the build's javac instead, and
 * {@link FoldedStrings} then splits the string chains javac's parser folded. Each conversion runs inside an
 * {@link IsolationScope}, so it only reads javac's state: it completes no class, attributes no Javadoc with javac's
 * Attr, reports nothing and leaves javac's trees as they were. Each file gets a type cache of its own, so nothing of
 * a file outlives its tree. The tree's charset is the one javac decoded the file with ({@link CompileCharset}).
 */
public final class CompilerTreeLst {
    private static final String STRING = "java.lang.String";
    private static final int RESERVE_BYTES = 1 << 20;

    private final Context context;
    private final ExecutionContext executionContext;
    private final boolean check;
    private final Optional<Context> docContext;
    private byte[] reserve = new byte[RESERVE_BYTES];

    /**
     * @param context the javac context of the compile whose trees are converted
     * @param executionContext receives errors the conversion reports without throwing
     */
    public CompilerTreeLst(Context context, ExecutionContext executionContext) {
        this(context, executionContext, false);
    }

    /**
     * @param context the javac context of the compile whose trees are converted
     * @param executionContext receives errors the conversion reports without throwing
     * @param check whether every conversion also compares an identity digest of javac's trees
     */
    CompilerTreeLst(Context context, ExecutionContext executionContext, boolean check) {
        this.context = context;
        this.executionContext = executionContext;
        this.check = check;
        this.docContext = ShadowContext.of(context);
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
     * @throws IsolationBreach if the conversion reached javac state it must not touch (restored)
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
     * @throws IsolationBreach if the conversion reached javac state it must not touch (restored)
     */
    public LstResult convert(JCCompilationUnit unit, Path root) throws IOException {
        IsolationScope scope = IsolationScope.open(context, unit, docContext, check);
        try {
            return convert(scope.visitorContext(), unit, root);
        } catch (RuntimeException | Error e) {
            if (fatal(e)) {
                reserve = null;
            }
            throw e;
        } finally {
            scope.close();
        }
    }

    /**
     * Whether a problem is a VirtualMachineError (OutOfMemoryError, StackOverflowError, InternalError), also when
     * javac wrapped it ({@code ClientCodeException} around a file object's or listener's error).
     */
    static boolean fatal(Throwable problem) {
        return problem instanceof VirtualMachineError || problem.getCause() instanceof VirtualMachineError;
    }

    /** Whether the memory held back for an OutOfMemoryError is still held (the first such error releases it). */
    boolean reserved() {
        return reserve != null;
    }

    private LstResult convert(Context visitorContext, JCCompilationUnit unit, Path root) throws IOException {
        JavaFileObject file = unit.getSourceFile();
        String text = file.getCharContent(true).toString();
        Optional<Path> path = SourcePaths.ofFile(file);
        JavaTypeCache types = new JavaTypeCache();
        J.CompilationUnit visited = new Visit(visitorContext, types, executionContext).run(unit, text, root, path);
        FoldedStrings.Result unfolded = FoldedStrings.unfold(visited, Optional.ofNullable(types.get(STRING)));
        // J.CompilationUnit.withCharset is declared to return SourceFile
        J.CompilationUnit lst = (J.CompilationUnit) unfolded.lst().withCharset(CompileCharset.of(file, context));
        boolean printEqual = unfolded.unsplit() == 0 && printsSource(lst, text, path);
        return new LstResult(lst, printEqual, MethodCalls.count(lst), detail(unfolded.unsplit()));
    }

    private static String detail(int unsplit) {
        return unsplit == 0
                ? ""
                : unsplit + " string chain(s) folded by javac left as one literal (longer than "
                        + FoldedStrings.MAX_LITERALS + " literals, escaped surrogates, or unreadable)";
    }

    /** One run of rewrite's parser visitor with a type cache of its own. */
    private record Visit(Context visitorContext, JavaTypeCache types, ExecutionContext executionContext) {
        J.CompilationUnit run(JCCompilationUnit unit, String text, Path root, Optional<Path> path) {
            FileAttributes attributes = path.map(FileAttributes::fromPath).orElse(null);
            Path sourcePath = SourcePaths.relative(unit.getSourceFile(), root);
            ReloadableJava21ParserVisitor visitor = new ReloadableJava21ParserVisitor(
                    sourcePath,
                    attributes,
                    utf8(text),
                    List.of(),
                    new DefaultJavaTypeFactory(types),
                    executionContext,
                    visitorContext);
            return (J.CompilationUnit) visitor.scan(unit, Space.EMPTY);
        }

        /** UTF-8 carries any text javac decoded without loss; the unit's charset is set to the compile's afterwards. */
        private static EncodingDetectingInputStream utf8(String text) {
            return new EncodingDetectingInputStream(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        }
    }

    private static boolean printsSource(J.CompilationUnit lst, String text, Optional<Path> path) throws IOException {
        if (!lst.printAll().equals(text)) {
            return false;
        }
        return path.isEmpty() || Arrays.equals(lst.printAllAsBytes(), Files.readAllBytes(path.get()));
    }
}
