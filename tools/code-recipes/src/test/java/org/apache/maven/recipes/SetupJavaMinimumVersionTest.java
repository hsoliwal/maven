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

class SetupJavaMinimumVersionTest implements RewriteTest {
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new SetupJavaMinimumVersion(21));
    }

    private static SourceSpecs workflow(String before, String after) {
        return yaml(before, after, spec -> spec.path(WORKFLOW));
    }

    private static SourceSpecs workflow(String unchanged) {
        return yaml(unchanged, spec -> spec.path(WORKFLOW));
    }

    @Test
    void raisesUnquotedScalar() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: 17 # the build JDK
                """,
                """
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: 21 # the build JDK
                """));
    }

    @Test
    void raisesQuotedScalarsKeepingTheirQuotes() {
        rewriteRun(workflow(
                """
                jobs:
                  single:
                    steps:
                      - uses: actions/setup-java@v5
                        with:
                          java-version: '11'
                  double:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: "17"
                """,
                """
                jobs:
                  single:
                    steps:
                      - uses: actions/setup-java@v5
                        with:
                          java-version: '21'
                  double:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: "21"
                """));
    }

    @Test
    void raisesFlowMatrixListAndDropsDuplicates() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        os: [ubuntu-latest, windows-latest]
                        java: ['17', '21', '25']
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
                        os: [ubuntu-latest, windows-latest]
                        java: ['21', '25']
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void raisesMatrixListWhoseRaisedEntriesCollide() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        jdk: [8, 11, 17, 25]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{matrix.jdk}}
                """,
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        jdk: [21, 25]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{matrix.jdk}}
                """));
    }

    @Test
    void keepsTheListSpacingWhenRaisedEntriesAreDropped() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [ 8, 11, 17, 21 ]
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
                        java: [ 21 ]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void raisesBlockMatrixList() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java:
                          - "17"
                          - "21"
                          - "25-ea"
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
                        java:
                          - "21"
                          - "25-ea"
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void keepsExpressionsAndNames() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [lts, '${{ inputs.java }}', 21]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ inputs.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: lts
                """));
    }

    @Test
    void keepsMultiLineValuesThatInstallSeveralJdks() {
        rewriteRun(workflow(
                """
                jobs:
                  test:
                    strategy:
                      matrix:
                        java: [ 8, 25 ]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            ${{ matrix.java }}
                            27
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            11
                            17
                """));
    }

    @Test
    void keepsValuesAtOrAboveTheMinimum() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ['21', '25']
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: '25'
                """));
    }

    @Test
    void secondRunChangesNothing() {
        String raised =
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ['21', '25']
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: 21
                """;
        rewriteRun(workflow(raised));
    }

    @Test
    void leavesMatrixListsOtherStepsReadAlone() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        node: [16, 18]
                        java: [17]
                    steps:
                      - uses: actions/setup-node@v4
                        with:
                          node-version: ${{ matrix.node }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        node: [16, 18]
                        java: [21]
                    steps:
                      - uses: actions/setup-node@v4
                        with:
                          node-version: ${{ matrix.node }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void leavesYamlOutsideWorkflowsAlone() {
        rewriteRun(yaml(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ['17']
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: 17
                """,
                spec -> spec.path("config/ci-template.yml")));
    }

    @Test
    void honoursAnotherMinimum() {
        rewriteRun(
                spec -> spec.recipe(new SetupJavaMinimumVersion(25)),
                workflow(
                        """
                        jobs:
                          build:
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: '21'
                        """,
                        """
                        jobs:
                          build:
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: '25'
                        """));
    }

    @Test
    void leavesAliasesAloneAndRaisesTheOtherSteps() {
        rewriteRun(workflow(
                """
                x-jdk: &jdk '17'
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: *jdk
                      - uses: actions/setup-java@v4
                        with:
                          java-version: 17
                """,
                """
                x-jdk: &jdk '17'
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: *jdk
                      - uses: actions/setup-java@v4
                        with:
                          java-version: 21
                """));
    }

    @Test
    void raisesWorkflowsWithTheYamlExtension() {
        rewriteRun(yaml(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: '1.8'
                """,
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: '21'
                """,
                spec -> spec.path(".github/workflows/release.yaml")));
    }

    @Test
    void leavesJavaVersionOfOtherActionsAlone() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    steps:
                      - uses: graalvm/setup-graalvm@v1
                        with:
                          java-version: '17'
                """));
    }

    @Test
    void raisesSingleVersionBlockScalarsKeepingTheirStyle() {
        rewriteRun(workflow(
                """
                jobs:
                  literal:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            17
                  folded:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: >-
                            11
                          distribution: temurin
                """,
                """
                jobs:
                  literal:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: |
                            21
                  folded:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: >-
                            21
                          distribution: temurin
                """));
    }

    @Test
    void raisesBlockScalarMatrixEntries() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java:
                          - |
                            17
                          - 25
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
                        java:
                          - |
                            21
                          - 25
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void raisesIncludeEntriesAndDropsRaisedDuplicates() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [17, 25]
                        os: [ubuntu-latest, windows-latest]
                        include:
                          - java: 17
                            os: macos-latest
                          - java: 21
                            os: macos-latest
                          - java: '11'
                            experimental: true
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
                        include:
                          - java: 21
                            os: macos-latest
                          - java: '21'
                            experimental: true
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void raisesIncludeOnlyMatrix() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        include:
                          - java: '17'
                            os: ubuntu-latest
                          - java: '21'
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
                        include:
                          - java: '21'
                            os: ubuntu-latest
                          - java: '21'
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void raisesExcludesOfRenamedValuesAndKeepsExcludesOfMergedValues() {
        rewriteRun(workflow(
                """
                jobs:
                  renamed:
                    strategy:
                      matrix:
                        java: [17, 25]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: 17
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  merged:
                    strategy:
                      matrix:
                        java: [17, 21]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: 17
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  renamed:
                    strategy:
                      matrix:
                        java: [21, 25]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: 21
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  merged:
                    strategy:
                      matrix:
                        java: [21]
                        os: [ubuntu-latest, windows-latest]
                        exclude:
                          - java: 17
                            os: windows-latest
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void dropsTheCommentOfADroppedMiddleOrLastEntry() {
        rewriteRun(workflow(
                """
                jobs:
                  middle:
                    strategy:
                      matrix:
                        java:
                          - 25 # newest
                          - 17 # current
                          - 21 # target
                        os: [ubuntu-latest]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  last:
                    strategy:
                      matrix:
                        java:
                          - 21 # target
                          - 17 # old
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """,
                """
                jobs:
                  middle:
                    strategy:
                      matrix:
                        java:
                          - 25 # newest
                          - 21 # target
                        os: [ubuntu-latest]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  last:
                    strategy:
                      matrix:
                        java:
                          - 21 # target
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void rejectsMinimumOutsideTheRange() {
        assertTrue(new SetupJavaMinimumVersion(7).validate().isInvalid());
        assertTrue(new SetupJavaMinimumVersion(100).validate().isInvalid());
        assertTrue(new SetupJavaMinimumVersion(null).validate().isValid());
        assertTrue(new SetupJavaMinimumVersion(21).validate().isValid());
    }
}
