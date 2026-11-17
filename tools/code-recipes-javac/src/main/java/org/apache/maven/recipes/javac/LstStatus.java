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

/** What happened to one parsed source file; the {@code status} column of the census. */
public enum LstStatus {
    /** Parsed; its classes are not all analyzed yet. */
    PENDING("pending"),
    /** Tree built and it prints the exact source. */
    OK("ok"),
    /**
     * Tree built but it does not print the exact source, or holds a string chain javac folded that the bridge could not
     * give back (the detail says so); recipes must not use it.
     */
    PRINT_MISMATCH("print-mismatch"),
    /** The compilation reported errors, so no tree is kept. */
    COMPILE_ERRORS("skipped: compile errors"),
    /** The file lies under the build directory, where generated sources are; rewrite-maven-plugin skips them too. */
    GENERATED("skipped: generated source"),
    /** {@code module-info.java}: OpenRewrite has no tree for it, and its {@code JavaParser.accept} excludes it. */
    MODULE_INFO("skipped: module-info has no OpenRewrite tree"),
    /**
     * javac desugared a class of the file before all its classes were analyzed (compile policy {@code byTodo}, or a
     * superclass needed by another file under {@code byfile}); the trees no longer match the source.
     */
    DESUGARED("skipped: desugared before analysis finished; compile with -XDcompilePolicy=simple"),
    /** The compilation finished before every class of the file was analyzed. */
    INCOMPLETE("skipped: analysis incomplete"),
    /** The conversion threw or reported an error; the detail follows the label. */
    FAILED("failed");

    private final String label;

    LstStatus(String label) {
        this.label = label;
    }

    /** @return the census text */
    public String label() {
        return label;
    }
}
