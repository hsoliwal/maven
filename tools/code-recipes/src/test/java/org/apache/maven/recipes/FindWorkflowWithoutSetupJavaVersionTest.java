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
package org.apache.maven.recipes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

class FindWorkflowWithoutSetupJavaVersionTest implements RewriteTest {
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String MARKER = "~~(no actions/setup-java step or reusable workflow input runs Java 21)~~>";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindWorkflowWithoutSetupJavaVersion(21));
    }

    private static SourceSpecs marked(String workflow) {
        return yaml(workflow, MARKER + workflow, spec -> spec.path(WORKFLOW));
    }

    private static SourceSpecs unmarked(String workflow) {
        return yaml(workflow, spec -> spec.path(WORKFLOW));
    }

    private static String scalarJob(String javaVersion) {
        return """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: %s
                """.formatted(javaVersion);
    }

    private static String matrixJob(String matrix) {
        return """
                jobs:
                  build:
                    strategy:
                      matrix:
                %s
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """.formatted(matrix.indent(8).stripTrailing());
    }

    @Test
    void marksWorkflowWhoseScalarValuesAllDiffer() {
        rewriteRun(marked(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: 17
                  release:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: '25'
                """));
    }

    @Test
    void putsTheMarkerAfterLeadingComments() {
        rewriteRun(yaml(
                "# SPDX-License-Identifier: Apache-2.0\nname: ci\n" + scalarJob("'17'"),
                "# SPDX-License-Identifier: Apache-2.0\n" + MARKER + "name: ci\n" + scalarJob("'17'"),
                spec -> spec.path(WORKFLOW)));
    }

    @Test
    void keepsWorkflowWithAScalarOfTheVersion() {
        rewriteRun(
                unmarked(scalarJob("21")),
                yaml(scalarJob("'21.0.5'"), spec -> spec.path(".github/workflows/patch.yml")),
                yaml(scalarJob("|\n            17\n            21"), spec -> spec.path(".github/workflows/multi.yml")));
    }

    @Test
    void marksWorkflowWhoseMatrixLacksTheVersion() {
        rewriteRun(marked(matrixJob("java: [11, 17]")));
    }

    @Test
    void keepsWorkflowWhoseMatrixListOrIncludeGivesTheVersion() {
        rewriteRun(
                unmarked(matrixJob("java: ['17', '21']")),
                yaml(matrixJob("java: [17]\ninclude:\n  - java: 21\n    os: macos-latest"),
                        spec -> spec.path(".github/workflows/include.yml")),
                yaml(matrixJob("include: [{java: 21}]"), spec -> spec.path(".github/workflows/flow-include.yml")));
    }

    @Test
    void readsFlowMappings() {
        rewriteRun(
                unmarked(
                        """
                        jobs:
                          build:
                            steps:
                              - uses: actions/setup-java@v4
                                with: {distribution: temurin, java-version: 21}
                        """),
                yaml(
                        """
                        jobs:
                          build:
                            strategy:
                              matrix: {java: [17, 21]}
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: ${{ matrix.java }}
                        """,
                        spec -> spec.path(".github/workflows/flow-matrix.yml")));
    }

    @Test
    void marksReusableWorkflowCallWhoseJavaInputsLackTheVersion() {
        rewriteRun(marked(
                """
                jobs:
                  verify:
                    uses: apache/maven-gh-actions-shared/.github/workflows/maven-verify.yml@v4
                    with:
                      jdk-matrix: '[ "11", "17" ]'
                """));
    }

    @Test
    void keepsReusableWorkflowCallThatPassesTheVersion() {
        rewriteRun(
                unmarked(
                        """
                        jobs:
                          verify:
                            uses: apache/maven-gh-actions-shared/.github/workflows/maven-verify.yml@v4
                            with:
                              jdk-matrix: '[ "17", "21" ]'
                        """),
                yaml(
                        """
                        jobs:
                          verify:
                            strategy:
                              matrix:
                                java: [17, 21]
                            uses: ./.github/workflows/build.yml
                            with:
                              java-version: ${{ matrix.java }}
                        """,
                        spec -> spec.path(".github/workflows/matrix-call.yml")));
    }

    @Test
    void marksReusableWorkflowCallWhoseMatrixLacksTheVersion() {
        rewriteRun(marked(
                """
                jobs:
                  verify:
                    strategy:
                      matrix:
                        java: [17]
                    uses: ./.github/workflows/build.yml
                    with:
                      java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void marksReusableWorkflowThatTakesTheVersionAsAnInput() {
        rewriteRun(marked(
                """
                on:
                  workflow_call:
                    inputs:
                      java-version:
                        type: string
                        default: '21'
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ inputs.java-version }}
                """));
    }

    @Test
    void keepsWorkflowsWithoutJavaSources() {
        rewriteRun(
                unmarked(
                        """
                        jobs:
                          stale:
                            steps:
                              - uses: actions/stale@v9
                        """),
                yaml(
                        """
                        jobs:
                          check:
                            uses: apache/maven-gh-actions-shared/.github/workflows/pr-check.yml@v5
                          verify:
                            uses: apache/maven-gh-actions-shared/.github/workflows/maven-verify.yml@v4
                            with:
                              java-distribution: temurin
                        """,
                        spec -> spec.path(".github/workflows/calls.yml")),
                yaml(
                        """
                        jobs:
                          build:
                            steps:
                              - uses: graalvm/setup-graalvm@v1
                                with:
                                  java-version: 17
                        """,
                        spec -> spec.path(".github/workflows/graal.yml")));
    }

    @Test
    void marksSetupJavaStepsWithoutAVersionValue() {
        rewriteRun(marked(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version-file: &file .java-version
                      - uses: actions/setup-java@v4
                        with:
                          java-version: *file
                """));
    }

    @Test
    void keepsFilesOutsideWorkflows() {
        rewriteRun(
                yaml(scalarJob("17"), spec -> spec.path(".github/actions/setup/action.yml")),
                yaml(scalarJob("17"), spec -> spec.path("ci.yml")));
    }

    @Test
    void readsTheVersionOfTheOption() {
        rewriteRun(
                spec -> spec.recipe(new FindWorkflowWithoutSetupJavaVersion(17)),
                unmarked(scalarJob("17")),
                yaml(
                        scalarJob("21"),
                        "~~(no actions/setup-java step or reusable workflow input runs Java 17)~~>" + scalarJob("21"),
                        spec -> spec.path(".github/workflows/other.yaml")));
    }

    @Test
    void validatesTheVersionRange() {
        assertTrue(new FindWorkflowWithoutSetupJavaVersion(null).validate().isValid());
        assertFalse(new FindWorkflowWithoutSetupJavaVersion(7).validate().isValid());
        assertFalse(new FindWorkflowWithoutSetupJavaVersion(100).validate().isValid());
    }
}
