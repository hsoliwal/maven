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
import static org.openrewrite.maven.Assertions.pomXml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class FindJavaLevelAboveMaximumTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindJavaLevelAboveMaximum(21));
    }

    @Test
    void marksPropertiesAboveTheMaximum() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>23</maven.compiler.target>
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
                    <maven.compiler.source>17</maven.compiler.source>
                    <!--~~(Java 23 is above 21)~~>--><maven.compiler.target>23</maven.compiler.target>
                  </properties>
                </project>
                """));
    }

    @Test
    void marksCompilerPluginConfigurationAndExecutions() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>23</release>
                        </configuration>
                        <executions>
                          <execution>
                            <id>java25</id>
                            <configuration>
                              <release>25</release>
                            </configuration>
                          </execution>
                          <execution>
                            <id>java21</id>
                            <configuration>
                              <release>21</release>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <!--~~(Java 23 is above 21)~~>--><release>23</release>
                        </configuration>
                        <executions>
                          <execution>
                            <id>java25</id>
                            <configuration>
                              <!--~~(Java 25 is above 21)~~>--><release>25</release>
                            </configuration>
                          </execution>
                          <execution>
                            <id>java21</id>
                            <configuration>
                              <release>21</release>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """));
    }

    @Test
    void resolvesPropertyReferences() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>25</java.level>
                  </properties>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <configuration>
                            <source>${java.level}</source>
                          </configuration>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>25</java.level>
                  </properties>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <configuration>
                            <!--~~(Java 25 is above 21)~~>--><source>${java.level}</source>
                          </configuration>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """));
    }

    @Test
    void leavesLevelsAtOrBelowTheMaximumAndOtherPluginsAlone() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>17</release>
                        </configuration>
                      </plugin>
                      <plugin>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <configuration>
                          <release>25</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """));
    }

    @Test
    void marksProfileProperties() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <profiles>
                    <profile>
                      <id>next</id>
                      <properties>
                        <maven.compiler.testRelease>25</maven.compiler.testRelease>
                      </properties>
                    </profile>
                  </profiles>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <profiles>
                    <profile>
                      <id>next</id>
                      <properties>
                        <!--~~(Java 25 is above 21)~~>--><maven.compiler.testRelease>25</maven.compiler.testRelease>
                      </properties>
                    </profile>
                  </profiles>
                </project>
                """));
    }

    @Test
    void resolvesPomPropertiesBeforeSystemProperties() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.version>25</java.version>
                    <java.specification.version>23</java.specification.version>
                    <maven.compiler.release>${java.version}</maven.compiler.release>
                    <maven.compiler.testRelease>${java.specification.version}</maven.compiler.testRelease>
                  </properties>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.version>25</java.version>
                    <java.specification.version>23</java.specification.version>
                    <!--~~(Java 25 is above 21)~~>--><maven.compiler.release>${java.version}</maven.compiler.release>
                    <!--~~(Java 23 is above 21)~~>--><maven.compiler.testRelease>${java.specification.version}</maven.compiler.testRelease>
                  </properties>
                </project>
                """));
    }

    @Test
    void ignoresASystemPropertyWhenThePomDefinesTheKey() {
        String key = "code.recipes.test.level";
        System.setProperty(key, "25");
        try {
            rewriteRun(pomXml(
                    """
                    <project>
                      <groupId>org.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1</version>
                      <properties>
                        <code.recipes.test.level>17</code.recipes.test.level>
                        <maven.compiler.release>${code.recipes.test.level}</maven.compiler.release>
                      </properties>
                    </project>
                    """));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void fallsBackToSystemPropertiesForKeysThePomDoesNotDefine() {
        String key = "code.recipes.test.undefined";
        System.setProperty(key, "25");
        try {
            rewriteRun(pomXml(
                    """
                    <project>
                      <groupId>org.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1</version>
                      <properties>
                        <maven.compiler.release>${code.recipes.test.undefined}</maven.compiler.release>
                      </properties>
                    </project>
                    """,
                    """
                    <project>
                      <groupId>org.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1</version>
                      <properties>
                        <!--~~(Java 25 is above 21)~~>--><maven.compiler.release>${code.recipes.test.undefined}</maven.compiler.release>
                      </properties>
                    </project>
                    """));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void resolvesProfileTagsAgainstTheirOwnProfileProperties() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>17</java.level>
                    <maven.compiler.release>${java.level}</maven.compiler.release>
                  </properties>
                  <profiles>
                    <profile>
                      <id>next</id>
                      <properties>
                        <java.level>25</java.level>
                        <next.test.level>${java.level}</next.test.level>
                        <maven.compiler.release>${java.level}</maven.compiler.release>
                        <maven.compiler.testRelease>${next.test.level}</maven.compiler.testRelease>
                      </properties>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                              <source>${java.level}</source>
                            </configuration>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>17</java.level>
                    <maven.compiler.release>${java.level}</maven.compiler.release>
                  </properties>
                  <profiles>
                    <profile>
                      <id>next</id>
                      <properties>
                        <java.level>25</java.level>
                        <next.test.level>${java.level}</next.test.level>
                        <!--~~(Java 25 is above 21)~~>--><maven.compiler.release>${java.level}</maven.compiler.release>
                        <!--~~(Java 25 is above 21)~~>--><maven.compiler.testRelease>${next.test.level}</maven.compiler.testRelease>
                      </properties>
                      <build>
                        <plugins>
                          <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                              <!--~~(Java 25 is above 21)~~>--><source>${java.level}</source>
                            </configuration>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """));
    }

    @Test
    void marksLevelsInCompilerArgs() {
        rewriteRun(pomXml(
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>22</java.level>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <compilerArgs>
                            <arg>-Xlint:all</arg>
                            <arg>--release</arg>
                            <arg>25</arg>
                            <arg>--source=23</arg>
                            <arg>-target</arg>
                            <arg>${java.level}</arg>
                            <arg>--release</arg>
                            <arg>21</arg>
                            <arg>24</arg>
                          </compilerArgs>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """,
                """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1</version>
                  <properties>
                    <java.level>22</java.level>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <compilerArgs>
                            <arg>-Xlint:all</arg>
                            <arg>--release</arg>
                            <!--~~(Java 25 is above 21)~~>--><arg>25</arg>
                            <!--~~(Java 23 is above 21)~~>--><arg>--source=23</arg>
                            <arg>-target</arg>
                            <!--~~(Java 22 is above 21)~~>--><arg>${java.level}</arg>
                            <arg>--release</arg>
                            <arg>21</arg>
                            <arg>24</arg>
                          </compilerArgs>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """));
    }

    @Test
    void rejectsMaximumOutsideTheRange() {
        assertTrue(new FindJavaLevelAboveMaximum(1).validate().isInvalid());
        assertTrue(new FindJavaLevelAboveMaximum(21).validate().isValid());
    }
}
