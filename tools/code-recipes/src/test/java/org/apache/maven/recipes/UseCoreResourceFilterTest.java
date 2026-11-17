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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openrewrite.java.Assertions.java;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class UseCoreResourceFilterTest implements RewriteTest {

    /** Minimal shapes of the maven-filtering and Maven 4 API types the recipe looks at. */
    private static final String[] STUBS = {
        """
        package org.apache.maven.shared.filtering;
        public class MavenFilteringException extends Exception {}
        """,
        """
        package org.apache.maven.shared.filtering;
        public class AbstractMavenFilteringRequest {
            public void setEscapeString(String escapeString) {}
            public void setMavenProject(Object project) {}
            public void setAdditionalProperties(java.util.Properties properties) {}
        }
        """,
        """
        package org.apache.maven.shared.filtering;
        public class MavenResourcesExecution extends AbstractMavenFilteringRequest {
            public void setOutputDirectory(java.io.File outputDirectory) {}
            public void setEncoding(String encoding) {}
            public void setFilterFilenames(boolean filterFilenames) {}
            public void setOverwrite(boolean overwrite) {}
        }
        """,
        """
        package org.apache.maven.shared.filtering;
        public interface MavenResourcesFiltering {
            void filterResources(MavenResourcesExecution execution) throws MavenFilteringException;
        }
        """,
        """
        package org.apache.maven.api;
        public interface Session {}
        """
    };

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseCoreResourceFilter()).parser(JavaParser.fromJavaVersion().dependsOn(STUBS));
    }

    @Test
    void marksTheCallOfAMaven4PluginWithTheMapping() {
        rewriteRun(java(
                """
                package org.example;

                import java.io.File;
                import org.apache.maven.api.Session;
                import org.apache.maven.shared.filtering.MavenFilteringException;
                import org.apache.maven.shared.filtering.MavenResourcesExecution;
                import org.apache.maven.shared.filtering.MavenResourcesFiltering;

                class ResourcesMojo {
                    Session session;
                    MavenResourcesFiltering filtering;

                    void execute(File out) throws MavenFilteringException {
                        MavenResourcesExecution execution = new MavenResourcesExecution();
                        execution.setOutputDirectory(out);
                        execution.setEncoding("UTF-8");
                        execution.setEscapeString("\\\\");
                        execution.setMavenProject(null);
                        execution.setFilterFilenames(true);
                        filtering.filterResources(execution);
                    }
                }
                """,
                """
                package org.example;

                import java.io.File;
                import org.apache.maven.api.Session;
                import org.apache.maven.shared.filtering.MavenFilteringException;
                import org.apache.maven.shared.filtering.MavenResourcesExecution;
                import org.apache.maven.shared.filtering.MavenResourcesFiltering;

                class ResourcesMojo {
                    Session session;
                    MavenResourcesFiltering filtering;

                    void execute(File out) throws MavenFilteringException {
                        MavenResourcesExecution execution = new MavenResourcesExecution();
                        execution.setOutputDirectory(out);
                        execution.setEncoding("UTF-8");
                        execution.setEscapeString("\\\\");
                        execution.setMavenProject(null);
                        execution.setFilterFilenames(true);
                        /*~~(ResourceFilter.copy(ResourceFilterRequest); builder: setEncoding -> encoding, \
                setEscapeString -> escapeString, setOutputDirectory -> outputDirectory; callback: setMavenProject; \
                no equivalent: setFilterFilenames)~~>*/filtering.filterResources(execution);
                    }
                }
                """));
    }

    @Test
    void marksACallWhoseExecutionComesFromElsewhere() {
        rewriteRun(java(
                """
                import org.apache.maven.api.Session;
                import org.apache.maven.shared.filtering.MavenFilteringException;
                import org.apache.maven.shared.filtering.MavenResourcesExecution;
                import org.apache.maven.shared.filtering.MavenResourcesFiltering;

                class Mojo {
                    Session session;

                    void run(MavenResourcesFiltering f, MavenResourcesExecution e) throws MavenFilteringException {
                        f.filterResources(e);
                    }
                }
                """,
                """
                import org.apache.maven.api.Session;
                import org.apache.maven.shared.filtering.MavenFilteringException;
                import org.apache.maven.shared.filtering.MavenResourcesExecution;
                import org.apache.maven.shared.filtering.MavenResourcesFiltering;

                class Mojo {
                    Session session;

                    void run(MavenResourcesFiltering f, MavenResourcesExecution e) throws MavenFilteringException {
                        /*~~(ResourceFilter.copy(ResourceFilterRequest))~~>*/f.filterResources(e);
                    }
                }
                """));
    }

    @Test
    void leavesMaven3PluginsUnchanged() {
        rewriteRun(java(
                """
                import org.apache.maven.shared.filtering.MavenFilteringException;
                import org.apache.maven.shared.filtering.MavenResourcesExecution;
                import org.apache.maven.shared.filtering.MavenResourcesFiltering;

                class Maven3Mojo {
                    void run(MavenResourcesFiltering f) throws MavenFilteringException {
                        MavenResourcesExecution e = new MavenResourcesExecution();
                        e.setEncoding("UTF-8");
                        f.filterResources(e);
                    }
                }
                """));
    }

    @Test
    void leavesOtherCodeUnchanged() {
        rewriteRun(java(
                """
                import org.apache.maven.api.Session;

                class Unrelated {
                    Session session;

                    void filterResources(Object execution) {
                        filterResources(null);
                    }
                }
                """));
    }

    @Test
    void describesEveryMappedSetterAndOverwrite() {
        assertEquals(
                "ResourceFilter.copy(ResourceFilterRequest); builder: setOverwrite -> changeDetection, "
                        + "setResources -> roots; callback: setFilters; no equivalent: addFilterWrapper",
                ResourceFilterOptions.describe(List.of("setResources", "setOverwrite", "setFilters", "addFilterWrapper")));
        assertEquals(17, ResourceFilterOptions.BUILDER.size());
    }
}
