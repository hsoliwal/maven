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
     * javac desugared a class of the file before all its classes were analyzed: under javac's default policy
     * ({@code byTodo}) a file with several top-level classes, whose first class javac generates before it attributes
     * the next; the trees no longer match the source. The plugin sets no compile policy, which would change javac's
     * diagnostics and class output on failing compiles.
     */
    DESUGARED("skipped: desugared before its last class was analyzed"),
    /** The compilation finished before every class of the file was analyzed. */
    INCOMPLETE("skipped: analysis incomplete"),
    /**
     * A tree was built, but javac did not generate every top-level class of the file although it reported no error
     * (a compiler crash, {@code -proc:only}, a {@code should-stop} option, or an implicitly compiled source under
     * {@code -implicit:none}); the detail gives how many classes were generated.
     */
    NOT_FINISHED("skipped: compile did not finish"),
    /** The heap gate closed before the file: less free heap than the plugin requires; no trees for the rest. */
    HEAP("skipped: heap headroom"),
    /** The plugin stopped earlier in the compile (a failure of its own, contained); no trees for the rest. */
    STOPPED("skipped: plugin stopped"),
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
