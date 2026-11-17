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

import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class Jdk21CiLegTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.apache.maven.recipes.Jdk21CiLeg");
    }

    @Test
    void addsMatrixLegsAndMarksTheWorkflowsLeftWithoutJava21() {
        rewriteRun(
                yaml(
                        """
                        jobs:
                          build:
                            strategy:
                              matrix:
                                java: ['11', '17']
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
                                java: ['11', '17', '21']
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: ${{ matrix.java }}
                        """,
                        spec -> spec.path(".github/workflows/build.yml")),
                yaml(
                        """
                        jobs:
                          release:
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: 17
                        """,
                        """
                        ~~(no actions/setup-java step or reusable workflow input runs Java 21)~~>jobs:
                          release:
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: 17
                        """,
                        spec -> spec.path(".github/workflows/release.yml")),
                yaml(
                        """
                        jobs:
                          build:
                            steps:
                              - uses: actions/setup-java@v4
                                with:
                                  java-version: 21
                        """,
                        spec -> spec.path(".github/workflows/current.yml")));
    }
}
