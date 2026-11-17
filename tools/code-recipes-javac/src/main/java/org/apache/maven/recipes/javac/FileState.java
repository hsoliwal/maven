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
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;

/** The census state of one source file during a compilation; javac calls listeners on one thread. */
final class FileState {
    private static final String NOT_BUILT = "-";

    private final String key;
    private final Set<Symbol> analyzed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int parseEvents;
    private Optional<JCCompilationUnit> unit = Optional.empty();
    private Optional<LstResult> result = Optional.empty();
    private LstStatus status = LstStatus.PENDING;
    private String detail = "";
    private String builtAt = NOT_BUILT;

    FileState(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    void parsed(JCCompilationUnit parsedUnit) {
        parseEvents++;
        unit = Optional.of(parsedUnit);
    }

    Optional<JCCompilationUnit> unit() {
        return unit;
    }

    /**
     * Records one ANALYZE event of this file.
     *
     * @return true when every top-level class of the file is analyzed (for a file without classes, at its first
     *     event: package-info and module-info) and no tree is built yet
     */
    boolean analyzed(Symbol type) {
        List<Symbol> expected = topLevelTypes();
        if (expected.isEmpty() || expected.contains(type)) {
            analyzed.add(type);
        }
        return pending() && !analyzed.isEmpty() && analyzed.containsAll(expected);
    }

    /** The symbols of the file's top-level classes, interfaces, enums and records. */
    List<Symbol> topLevelTypes() {
        return unit.map(tree -> tree.getTypeDecls().stream()
                        .filter(JCClassDecl.class::isInstance)
                        .map(decl -> (Symbol) ((JCClassDecl) decl).sym)
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    boolean pending() {
        return status == LstStatus.PENDING;
    }

    void built(LstResult converted, String event) {
        result = Optional.of(converted);
        status = converted.printEqual() ? LstStatus.OK : LstStatus.PRINT_MISMATCH;
        builtAt = event;
        unit = Optional.empty();
    }

    void skip(LstStatus reason) {
        result = Optional.empty();
        status = reason;
        detail = "";
    }

    void failed(String why) {
        skip(LstStatus.FAILED);
        detail = ": " + why.replaceAll("[\\t\\r\\n]+", " ");
    }

    Optional<LstResult> result() {
        return result;
    }

    CensusRow row() {
        MethodCalls calls = result.map(LstResult::calls).orElse(new MethodCalls(0, 0));
        boolean printEqual = result.map(LstResult::printEqual).orElse(false);
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
