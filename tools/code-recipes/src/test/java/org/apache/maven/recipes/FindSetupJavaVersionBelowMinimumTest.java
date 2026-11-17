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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

class FindSetupJavaVersionBelowMinimumTest implements RewriteTest {
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSetupJavaVersionBelowMinimum(21));
    }

    private static SourceSpecs workflow(String before, String after) {
        return yaml(before, after, spec -> spec.path(WORKFLOW));
    }

    private static SourceSpecs workflow(String unchanged) {
        return yaml(unchanged, spec -> spec.path(WORKFLOW));
    }

    @Test
    void marksFlowMappingInputsBelowTheMinimumOrReadingTheMatrix() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with: {java-version: '17', distribution: temurin}
                      - {uses: actions/setup-java@v4, with: {java-version: '${{ matrix.java }}'}}
                      - uses: actions/setup-java@v4
                        with: {java-version: 21}
                      - uses: actions/setup-node@v4
                        with: {java-version: 11}
                """,
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with: ~~(java-version in a flow mapping is not raised)~~>{java-version: '17', distribution: temurin}
                      - {uses: actions/setup-java@v4, with: ~~(java-version in a flow mapping is not raised)~~>{java-version: '${{ matrix.java }}'}}
                      - uses: actions/setup-java@v4
                        with: {java-version: 21}
                      - uses: actions/setup-node@v4
                        with: {java-version: 11}
                """));
    }

    @Test
    void marksFlowMappingMatricesWithAnAxisBelowTheMinimum() {
        rewriteRun(workflow(
                """
                jobs:
                  low:
                    strategy:
                      matrix: {java: ['17', '25'], os: [ubuntu-22.04]}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  nested:
                    strategy: {matrix: {jdk: [11]}}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.jdk }}
                  raised:
                    strategy:
                      matrix: {java: [21, 25], node: [16]}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  low:
                    strategy:
                      matrix: ~~(matrix in a flow mapping is not raised)~~>{java: ['17', '25'], os: [ubuntu-22.04]}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  nested:
                    strategy: {matrix: ~~(matrix in a flow mapping is not raised)~~>{jdk: [11]}}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.jdk }}
                  raised:
                    strategy:
                      matrix: {java: [21, 25], node: [16]}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void marksFlowMappingIncludeAndExcludeObjectsBelowTheMinimum() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [21, 25]
                        os: [ubuntu-latest, windows-latest]
                        include: [{java: 17, os: macos-latest}, {java: 25, os: macos-latest}]
                        exclude:
                          - {java: '11', os: windows-latest}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [21, 25]
                        os: [ubuntu-latest, windows-latest]
                        include: [~~(matrix in a flow mapping is not raised)~~>{java: 17, os: macos-latest}, {java: 25, os: macos-latest}]
                        exclude:
                          - ~~(matrix in a flow mapping is not raised)~~>{java: '11', os: windows-latest}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void marksMultiLineValuesWithAVersionBelowTheMinimumOrAMatrixReference() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            ${{ matrix.java }}
                            27
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            21
                            25
                """,
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ~~(multi-line java-version is not raised)~~>|
                            ${{ matrix.java }}
                            27
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            21
                            25
                """));
    }

    @Test
    void marksCompositeActionsBelowTheMinimum() {
        rewriteRun(
                yaml(
                        """
                        runs:
                          using: composite
                          steps:
                            - uses: actions/setup-java@v4
                              with:
                                java-version: 11
                            - uses: actions/setup-java@v4
                              with:
                                java-version: 21
                            - uses: actions/setup-java@v4
                              with: {java-version: 8}
                        """,
                        """
                        runs:
                          using: composite
                          steps:
                            - uses: actions/setup-java@v4
                              with:
                                java-version: ~~(java-version of a composite action is not raised)~~>11
                            - uses: actions/setup-java@v4
                              with:
                                java-version: 21
                            - uses: actions/setup-java@v4
                              with: ~~(java-version in a flow mapping is not raised)~~>{java-version: 8}
                        """,
                        spec -> spec.path("action.yml")),
                yaml(
                        """
                        runs:
                          using: composite
                          steps:
                            - uses: actions/setup-java@v4
                              with:
                                java-version: 17
                        """,
                        spec -> spec.path("config/action.yml.txt")));
    }

    @Test
    void marksExcludeValuesThatMatchNoAxisValue() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [21, 25]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: 17
                            os: windows-latest
                          - java: 21
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [21, 25]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: ~~(Java 17 matches no value of matrix axis java)~~>17
                            os: windows-latest
                          - java: 21
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void leavesShapesTheChangeRecipeRaisesAlone() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [17, 25]
                        exclude:
                          - java: 17
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: '11'
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            17
                """));
    }

    @Test
    void rejectsMinimumOutsideTheRange() {
        assertTrue(new FindSetupJavaVersionBelowMinimum(7).validate().isInvalid());
        assertTrue(new FindSetupJavaVersionBelowMinimum(null).validate().isValid());
    }
}
