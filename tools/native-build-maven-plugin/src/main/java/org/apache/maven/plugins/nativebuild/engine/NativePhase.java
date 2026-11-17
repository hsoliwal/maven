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
package org.apache.maven.plugins.nativebuild.engine;

/**
 * The steps of a native build, in order, with their polyglot task names, the Maven phase whose goal runs them, and
 * the aggregation target of a root {@code m3-build.tsv} (the polyglot lifecycle participant binds only validate,
 * compile, test, package and verify, so configure belongs to compile and install to package there).
 */
public enum NativePhase {
    /** Creates the out-of-tree build directory (root manifests only; goals create it themselves). */
    PREPARE("native-prepare", "generate-sources", "compile"),
    /** autoreconf, when the configure script is missing. */
    BOOTSTRAP("native-bootstrap", "generate-sources", "compile"),
    /** cmake, configure or meson setup. */
    CONFIGURE("native-configure", "generate-sources", "compile"),
    /** The build itself. */
    BUILD("native-build", "compile", "compile"),
    /** ctest, make check or meson test. */
    TEST("native-test", "test", "test"),
    /** Install into the stage directory. */
    INSTALL("native-install", "prepare-package", "package");

    private final String task;
    private final String mavenPhase;
    private final String aggregate;

    NativePhase(String task, String mavenPhase, String aggregate) {
        this.task = task;
        this.mavenPhase = mavenPhase;
        this.aggregate = aggregate;
    }

    /** The polyglot task name. */
    public String task() {
        return task;
    }

    /** The Maven phase whose goal runs this step. */
    public String mavenPhase() {
        return mavenPhase;
    }

    /** The root-manifest aggregation target that depends on this step. */
    public String aggregate() {
        return aggregate;
    }
}
