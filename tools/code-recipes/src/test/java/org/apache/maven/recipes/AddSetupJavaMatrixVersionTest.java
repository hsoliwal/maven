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

class AddSetupJavaMatrixVersionTest implements RewriteTest {
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSetupJavaMatrixVersion(21));
    }

    private static SourceSpecs workflow(String before, String after) {
        return yaml(before, after, spec -> spec.path(WORKFLOW));
    }

    private static SourceSpecs workflow(String unchanged) {
        return yaml(unchanged, spec -> spec.path(WORKFLOW));
    }

    private static String flowJob(String list) {
        return """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: %s
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """.formatted(list);
    }

    @Test
    void insertsIntoFlowListInAscendingOrder() {
        rewriteRun(workflow(flowJob("['17', '25']"), flowJob("['17', '21', '25']")));
    }

    @Test
    void comparesMajorVersionsAsNumbersNotText() {
        rewriteRun(workflow(flowJob("[ 9, 11, 25 ]"), flowJob("[ 9, 11, 21, 25 ]")));
    }

    @Test
    void appendsToFlowListBelowTheVersion() {
        rewriteRun(workflow(flowJob("[ 11, 17.0.2 ]"), flowJob("[ 11, 17.0.2, 21 ]")));
    }

    @Test
    void prependsToFlowListAboveTheVersion() {
        rewriteRun(workflow(flowJob("[\"25\", \"26-ea\"]"), flowJob("[\"21\", \"25\", \"26-ea\"]")));
    }

    @Test
    void addsToSingleEntryFlowList() {
        rewriteRun(workflow(flowJob("['17']"), flowJob("['17', '21']")));
    }

    @Test
    void keepsTrailingCommaOfFlowList() {
        rewriteRun(workflow(flowJob("[11, 17,]"), flowJob("[11, 17, 21,]")));
    }

    @Test
    void copiesQuotingStyleOfTheEntryBefore() {
        rewriteRun(workflow(flowJob("[11, '17', \"25\"]"), flowJob("[11, '17', '21', \"25\"]")));
    }

    @Test
    void copiesAnEntryWithoutItsAnchor() {
        rewriteRun(workflow(flowJob("[&oldest 17]"), flowJob("[&oldest 17, 21]")));
    }

    @Test
    void keepsCommentsOfMultiLineFlowList() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [ # versions
                          '11', # oldest
                          '17' # newest
                        ]
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
                        java: [ # versions
                          '11', # oldest
                          '17', # newest
                          '21'
                        ]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void insertsIntoMultiLineFlowListBeforeAHigherVersion() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: [
                          '17', # LTS
                          '25' # current
                        ]
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
                        java: [
                          '17', # LTS
                          '21',
                          '25' # current
                        ]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void insertsIntoBlockListKeepingComments() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java:
                          - 17 # LTS
                          - '25' # current
                        os: [ubuntu-latest]
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
                          - 17 # LTS
                          - 21
                          - '25' # current
                        os: [ubuntu-latest]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void prependsToBlockListKeepingTheKeyComment() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: # versions
                          - "25"
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
                        java: # versions
                          - "21"
                          - "25"
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void appendsToBlockListKeepingTheCommentOfTheLastEntry() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java:
                          - "11"
                          - "17" # newest
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
                          - "11"
                          - "17" # newest
                          - "21"
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void appendsToBlockListAtTheEndOfTheFile() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{matrix.jdk}}
                    strategy:
                      matrix:
                        jdk:
                        - 8
                        - 17 # last
                """,
                """
                jobs:
                  build:
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{matrix.jdk}}
                    strategy:
                      matrix:
                        jdk:
                        - 8
                        - 17 # last
                        - 21
                """));
    }

    @Test
    void addsToEveryAxisTheJobReadsAsInTheReadme() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ['11', '17']
                        jdk:
                          - 17 # LTS
                          - 25 # current
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.jdk }}
                """,
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ['11', '17', '21']
                        jdk:
                          - 17 # LTS
                          - 21
                          - 25 # current
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.jdk }}
                """));
    }

    @Test
    void addsToEveryJobWhoseSetupJavaReadsTheAxis() {
        rewriteRun(workflow(
                """
                jobs:
                  unit:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  lint:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - run: echo ${{ matrix.java }}
                """,
                """
                jobs:
                  unit:
                    strategy:
                      matrix:
                        java: [17, 21]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  lint:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - run: echo ${{ matrix.java }}
                """));
    }

    @Test
    void addsTheVersionOfTheOption() {
        rewriteRun(
                spec -> spec.recipe(new AddSetupJavaMatrixVersion(25)),
                workflow(flowJob("[17, 21, 26]"), flowJob("[17, 21, 25, 26]")));
    }

    @Test
    void defaultsTo21() {
        rewriteRun(
                spec -> spec.recipe(new AddSetupJavaMatrixVersion(null)),
                workflow(flowJob("[17]"), flowJob("[17, 21]")));
    }

    @Test
    void changesYamlExtensionToo() {
        rewriteRun(yaml(flowJob("[17]"), flowJob("[17, 21]"), spec -> spec.path(".github/workflows/ci.yaml")));
    }

    @Test
    void keepsListsThatHoldTheMajorVersion() {
        rewriteRun(
                workflow(flowJob("['21']")),
                yaml(flowJob("[17, 21.0.2]"), spec -> spec.path(".github/workflows/patch.yml")),
                yaml(flowJob("[17, 21-ea]"), spec -> spec.path(".github/workflows/ea.yml")));
    }

    @Test
    void keepsListsWithEntriesThatAreNotVersions() {
        rewriteRun(
                workflow(flowJob("[17, '${{ inputs.java }}']")),
                yaml(flowJob("[17, lts]"), spec -> spec.path(".github/workflows/name.yml")),
                yaml(flowJob("[]"), spec -> spec.path(".github/workflows/empty.yml")),
                yaml(flowJob("[17, [25]]"), spec -> spec.path(".github/workflows/nested.yml")));
    }

    @Test
    void keepsBlockListWithLiteralEntry() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java:
                          - |
                            17
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void keepsAliasesAndAnchoredLists() {
        rewriteRun(workflow(
                """
                x-versions: &versions [17]
                jobs:
                  alias:
                    strategy:
                      matrix:
                        java: *versions
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  anchored:
                    strategy:
                      matrix:
                        java: &own [17]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  aliasEntry:
                    strategy:
                      matrix:
                        java: [17, *versions]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void keepsIncludeExcludeAndExpressionAxes() {
        rewriteRun(workflow(
                """
                jobs:
                  build:
                    strategy:
                      matrix:
                        java: ${{ fromJSON(needs.setup.outputs.versions) }}
                        include:
                          - java: 17
                        exclude:
                          - java: 11
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  includeOnly:
                    strategy:
                      matrix:
                        include:
                          - java: 17
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void keepsAxesThatNoSetupJavaStepReads() {
        rewriteRun(workflow(
                """
                jobs:
                  graal:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - uses: graalvm/setup-graalvm@v1
                        with:
                          java-version: ${{ matrix.java }}
                  suffix:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}-ea
                  flow:
                    strategy:
                      matrix:
                        java: [17]
                    steps:
                      - uses: actions/setup-java@v4
                        with: {java-version: '${{ matrix.java }}'}
                  flowMatrix:
                    strategy:
                      matrix: {java: [17]}
                    steps:
                      - uses: actions/setup-java@v4
                        with:
                          java-version: ${{ matrix.java }}
                  reusable:
                    strategy:
                      matrix:
                        java: [17]
                    uses: ./.github/workflows/build.yml
                    with:
                      java-version: ${{ matrix.java }}
                """));
    }

    @Test
    void keepsFilesOutsideWorkflows() {
        rewriteRun(
                yaml(flowJob("[17]"), spec -> spec.path(".github/ci.yml")),
                yaml(flowJob("[17]"), spec -> spec.path("ci.yml")));
    }

    @Test
    void validatesTheVersionRange() {
        assertTrue(new AddSetupJavaMatrixVersion(null).validate().isValid());
        assertTrue(new AddSetupJavaMatrixVersion(8).validate().isValid());
        assertTrue(new AddSetupJavaMatrixVersion(99).validate().isValid());
        assertFalse(new AddSetupJavaMatrixVersion(7).validate().isValid());
        assertFalse(new AddSetupJavaMatrixVersion(100).validate().isValid());
    }
}
