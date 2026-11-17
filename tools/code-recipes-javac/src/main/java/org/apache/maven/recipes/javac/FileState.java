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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.comp.CompileStates;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;

/**
 * The census state of one source file during a compilation; javac calls listeners on one thread. It holds javac's
 * unit only for a file that declares no class (built when the compilation finishes), and the tree only when a caller
 * asked to keep it; otherwise nothing of a converted file outlives its census values.
 */
final class FileState {
    private static final String NOT_BUILT = "-";
    private static final int DETAIL_LIMIT = 300;

    private final String key;
    private final Set<Symbol> analyzed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Symbol> generated = Collections.newSetFromMap(new IdentityHashMap<>());
    private Optional<List<Symbol>> topLevel = Optional.empty();
    private int parseEvents;
    private Optional<JCCompilationUnit> classless = Optional.empty();
    private Optional<LstResult> result = Optional.empty();
    private boolean printEqual;
    private MethodCalls calls = new MethodCalls(0, 0);
    private LstStatus status = LstStatus.PENDING;
    private String detail = "";
    private String builtAt = NOT_BUILT;

    FileState(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    /** Counts a PARSE event; keeps the unit only when it declares no class, so no ANALYZE event will come. */
    void parsed(JCCompilationUnit unit) {
        parseEvents++;
        boolean declaresClass = unit.getTypeDecls().stream().anyMatch(JCClassDecl.class::isInstance);
        classless = declaresClass || isPackageOrModuleInfo(unit) ? Optional.empty() : Optional.of(unit);
    }

    /** The unit of a file without classes, built when the compilation finishes. */
    Optional<JCCompilationUnit> classless() {
        return classless;
    }

    /**
     * Records one ANALYZE event of this file.
     *
     * @return true when every top-level class of the file is analyzed (for a file without classes, at its first
     *     event: package-info and module-info) and no tree is built yet
     */
    boolean analyzed(JCCompilationUnit unit, Symbol type) {
        List<Symbol> expected = topLevel.orElseGet(() -> types(unit));
        topLevel = Optional.of(expected);
        if (expected.isEmpty() || expected.contains(type)) {
            analyzed.add(type);
        }
        return pending() && !analyzed.isEmpty() && analyzed.containsAll(expected);
    }

    /** Records one GENERATE event; only the file's top-level classes count. */
    void generated(Symbol type) {
        if (topLevelTypes().contains(type)) {
            generated.add(type);
        }
    }

    /** The symbols of the file's top-level classes, interfaces, enums and records, once it was analyzed. */
    List<Symbol> topLevelTypes() {
        return topLevel.orElse(List.of());
    }

    private static List<Symbol> types(JCCompilationUnit unit) {
        return unit.getTypeDecls().stream()
                .filter(JCClassDecl.class::isInstance)
                .map(decl -> (Symbol) ((JCClassDecl) decl).sym)
                .filter(Objects::nonNull)
                .toList();
    }

    private static boolean isPackageOrModuleInfo(JCCompilationUnit unit) {
        return unit.getSourceFile().isNameCompatible("package-info", javax.tools.JavaFileObject.Kind.SOURCE)
                || unit.getModuleDecl() != null;
    }

    /**
     * True when javac already desugared a class of the file, so its trees no longer match the source: the class env
     * reached TRANSTYPES, or javac dropped the env, which it does once the class is generated (all classes of the file
     * were analyzed when this is asked, so a missing env is never a class javac has not reached yet).
     */
    boolean desugared(Enter enter, CompileStates states) {
        return topLevelTypes().stream()
                .map(type -> Optional.ofNullable(enter.getEnv((TypeSymbol) type)))
                .anyMatch(env ->
                        env.map(e -> states.isDone(e, CompileState.TRANSTYPES)).orElse(true));
    }

    boolean pending() {
        return status == LstStatus.PENDING;
    }

    /** The conversion's census values; the tree itself only when the caller keeps it. */
    void built(LstResult converted, String event, boolean keep) {
        result = keep ? Optional.of(converted) : Optional.empty();
        printEqual = converted.printEqual();
        calls = converted.calls();
        status = converted.printEqual() ? LstStatus.OK : LstStatus.PRINT_MISMATCH;
        detail = converted.detail().isEmpty() ? "" : ": " + oneLine(converted.detail());
        builtAt = event;
        classless = Optional.empty();
    }

    void skip(LstStatus reason) {
        skip(reason, "");
    }

    private void skip(LstStatus reason, String why) {
        result = Optional.empty();
        classless = Optional.empty();
        printEqual = false;
        calls = new MethodCalls(0, 0);
        status = reason;
        detail = why;
        builtAt = NOT_BUILT;
    }

    void failed(String why) {
        skip(LstStatus.FAILED, ": " + oneLine(why));
    }

    /**
     * The compilation finished: a tree counts only if javac reported no error and generated every top-level class of
     * the file; otherwise the compile did not finish what the tree was built from.
     */
    void finish(boolean errors) {
        if (errors) {
            skip(LstStatus.COMPILE_ERRORS);
        } else if (built() && generated.size() < topLevelTypes().size()) {
            skip(
                    LstStatus.NOT_FINISHED,
                    " (" + generated.size() + "/" + topLevelTypes().size() + " classes generated)");
        }
    }

    private boolean built() {
        return status == LstStatus.OK || status == LstStatus.PRINT_MISMATCH;
    }

    private static String oneLine(String text) {
        String line = text.replaceAll("[\t\r\n]+", " ");
        return line.length() > DETAIL_LIMIT ? line.substring(0, DETAIL_LIMIT) : line;
    }

    Optional<LstResult> result() {
        return result;
    }

    CensusRow row() {
        return new CensusRow(
                key,
                parseEvents,
                printEqual,
                calls.attributed(),
                calls.unattributed(),
                status.label() + detail,
                builtAt);
    }

    FileLst fileLst() {
        return new FileLst(row(), status == LstStatus.OK ? result.map(LstResult::lst) : Optional.empty());
    }
}
