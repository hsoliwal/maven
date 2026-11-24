/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.recipes;

import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.java.Assertions.mavenProject;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

/** Recipe-first, opt-in module DB configuration contract; never invokes Maven goals. */
class EnableM3LifecycleModuleDbTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.apache.maven.recipes.EnableM3LifecycleModuleDb");
    }

    @Test
    void addsOptInPropertiesWithoutChangingTheBuildLifecycle() {
        rewriteRun(pomXml(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>native-m3db</artifactId>
                  <version>1.0</version>
                </project>
                """,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>native-m3db</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.backend>embedded</m3.lifecycle.backend>
                    <m3.lifecycle.incremental>true</m3.lifecycle.incremental>
                  </properties>
                </project>
                """));
    }

    @Test
    void preservesExplicitDisabledValueWhileAddingOnlyMissingBackend() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>example</groupId>
                  <artifactId>partial-opt-out</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.incremental>false</m3.lifecycle.incremental>
                  </properties>
                </project>
                """,
                """
                <project>
                  <groupId>example</groupId>
                  <artifactId>partial-opt-out</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.backend>embedded</m3.lifecycle.backend>
                    <m3.lifecycle.incremental>false</m3.lifecycle.incremental>
                  </properties>
                </project>
                """));
    }

    @Test
    void respectsExistingBackendWhenOnlyIncrementalPropertyIsMissing() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>example</groupId>
                  <artifactId>partial-backend</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.backend>sqlite</m3.lifecycle.backend>
                  </properties>
                </project>
                """,
                """
                <project>
                  <groupId>example</groupId>
                  <artifactId>partial-backend</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.backend>sqlite</m3.lifecycle.backend>
                    <m3.lifecycle.incremental>true</m3.lifecycle.incremental>
                  </properties>
                </project>
                """));
    }

    @Test
    void preservesReactorParentOptOutWithoutAddingChildOverrides() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>example</groupId>
                          <artifactId>parent</artifactId>
                          <version>1.0</version>
                          <packaging>pom</packaging>
                          <modules>
                            <module>child</module>
                          </modules>
                          <properties>
                            <m3.lifecycle.incremental>false</m3.lifecycle.incremental>
                            <m3.lifecycle.backend>sqlite</m3.lifecycle.backend>
                          </properties>
                        </project>
                        """),
                mavenProject("child", pomXml(
                        """
                        <project>
                          <parent>
                            <groupId>example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <relativePath>../pom.xml</relativePath>
                          </parent>
                          <artifactId>child</artifactId>
                        </project>
                        """)));
    }

    @Test
    void preservesExplicitOptOutAndBackendSelection() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>example</groupId>
                  <artifactId>explicit-policy</artifactId>
                  <version>1.0</version>
                  <properties>
                    <m3.lifecycle.incremental>false</m3.lifecycle.incremental>
                    <m3.lifecycle.backend>sqlite</m3.lifecycle.backend>
                  </properties>
                </project>
                """));
    }
}
