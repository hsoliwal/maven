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

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

/**
 * The recipe is declarative so its XPath scoping and idempotence must be exercised
 * by the same RewriteTest harness used for the other recipes in this module.
 */
class BrowserlessChromeLLMAdviceTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.apache.maven.recipes.BrowserlessChromeLLMAdvice");
    }

    @Test
    void migratesAdviceButKeepsLifecycle() {
        rewriteRun(pomXml(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <profiles>
                    <profile>
                      <id>chromellm-advice</id>
                      <properties>
                        <chromellm.backend>chrome</chromellm.backend>
                        <chromellm.chrome.profile>old-profile</chromellm.chrome.profile>
                        <chromellm.chrome.headless>true</chromellm.chrome.headless>
                      </properties>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>maven-antrun-plugin</artifactId>
                            <executions>
                              <execution>
                                <id>chromellm-pre-build</id>
                                <phase>validate</phase>
                                <configuration>
                                  <target>
                                    <condition property="chromellm.chrome.profile.default"/>
                                    <condition property="chromellm.chrome.headless.default"/>
                                    <exec>
                                      <arg value="--backend"/>
                                      <arg value="${chromellm.backend}"/>
                                      <arg value="--chrome-profile"/>
                                      <arg value="${chromellm.chrome.profile}"/>
                                      <arg value="--chrome-headless"/>
                                      <arg value="${chromellm.chrome.headless}"/>
                                    </exec>
                                  </target>
                                </configuration>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <profiles>
                    <profile>
                      <id>chromellm-advice</id>
                      <properties>
                        <chromellm.backend>cli</chromellm.backend>
                      </properties>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>maven-antrun-plugin</artifactId>
                            <executions>
                              <execution>
                                <id>chromellm-pre-build</id>
                                <phase>validate</phase>
                                <configuration>
                                  <target>
                                    <exec>
                                      <arg value="--backend"/>
                                      <arg value="${chromellm.backend}"/>
                                    </exec>
                                  </target>
                                </configuration>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """));
    }

    @Test
    void doesNotRewriteAnUnrelatedProfile() {
        rewriteRun(pomXml(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <profiles>
                    <profile>
                      <id>unrelated-advice</id>
                      <properties>
                        <chromellm.backend>chrome</chromellm.backend>
                      </properties>
                    </profile>
                  </profiles>
                </project>
                """));
    }
}
