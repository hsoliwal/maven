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

import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class Jdk21BaselineTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.apache.maven.recipes.Jdk21Baseline");
    }

    @Test
    void raisesWorkflowsAndMarksPomsInOneRun() {
        rewriteRun(
                yaml(
                        """
                        jobs:
                          build:
                            strategy:
                              matrix:
                                java: ['17', '25']
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: ${{ matrix.java }}
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: 17
                        """,
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
                        """,
                        spec -> spec.path(".github/workflows/build.yml")),
                pomXml(
                        """
                        <project>
                          <groupId>org.example</groupId>
                          <artifactId>app</artifactId>
                          <version>1</version>
                          <properties>
                            <maven.compiler.release>25</maven.compiler.release>
                          </properties>
                        </project>
                        """,
                        """
                        <project>
                          <groupId>org.example</groupId>
                          <artifactId>app</artifactId>
                          <version>1</version>
                          <properties>
                            <!--~~(Java 25 is above 21)~~>--><maven.compiler.release>25</maven.compiler.release>
                          </properties>
                        </project>
                        """));
    }

    @Test
    void marksJavaVersionsTheChangeRecipeLeaves() {
        rewriteRun(
                yaml(
                        """
                        jobs:
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
                          flow:
                            steps:
                              - uses: actions/setup-java@v4
                                with: {distribution: temurin, java-version: 17}
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: |
                                    11
                                    25
                        """,
                        """
                        jobs:
                          merged:
                            strategy:
                              matrix:
                                java: [21]
                                os: [ubuntu-latest, windows-latest]
                                exclude:
                                  - java: ~~(Java 17 matches no value of matrix axis java)~~>17
                                    os: windows-latest
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: ${{ matrix.java }}
                          flow:
                            steps:
                              - uses: actions/setup-java@v4
                                with: ~~(java-version in a flow mapping is not raised)~~>{distribution: temurin, java-version: 17}
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: ~~(multi-line java-version is not raised)~~>|
                                    11
                                    25
                        """,
                        spec -> spec.path(".github/workflows/build.yml")),
                yaml(
                        """
                        runs:
                          using: composite
                          steps:
                            - uses: actions/setup-java@v4
                              with:
                                java-version: '17'
                        """,
                        """
                        runs:
                          using: composite
                          steps:
                            - uses: actions/setup-java@v4
                              with:
                                java-version: ~~(java-version of a composite action is not raised)~~>'17'
                        """,
                        spec -> spec.path(".github/actions/setup/action.yml")));
    }
}
