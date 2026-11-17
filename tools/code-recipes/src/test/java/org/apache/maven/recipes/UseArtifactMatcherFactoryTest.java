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

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class UseArtifactMatcherFactoryTest implements RewriteTest {
    private static final String FILTERS = """
            package org.apache.maven.shared.artifact.filter;
            public class PatternIncludesArtifactFilter {
                public PatternIncludesArtifactFilter(java.util.Collection<String> patterns) {}
                public PatternIncludesArtifactFilter(java.util.Collection<String> patterns, boolean transitive) {}
            }
            """;

    private static final String EXCLUDES = """
            package org.apache.maven.shared.artifact.filter;
            public class PatternExcludesArtifactFilter extends PatternIncludesArtifactFilter {
                public PatternExcludesArtifactFilter(java.util.Collection<String> patterns) { super(patterns); }
            }
            """;

    private static final String STRICT = """
            package org.apache.maven.shared.artifact.filter;
            public class StrictPatternIncludesArtifactFilter {
                public StrictPatternIncludesArtifactFilter(java.util.List<String> patterns) {}
            }
            """;

    private static final String COLLECTION = """
            package org.apache.maven.shared.artifact.filter.collection;
            public class ScopeFilter { public ScopeFilter(String include, String exclude) {} }
            """;

    private static final String GROUP_IDS = """
            package org.apache.maven.shared.artifact.filter.collection;
            public class GroupIdFilter {
                public GroupIdFilter(String include, String exclude) {}
                public GroupIdFilter(String include, String exclude, boolean exactMatch) {}
            }
            """;

    private static final String TYPES = """
            package org.apache.maven.shared.artifact.filter.collection;
            public class TypeFilter { public TypeFilter(String include, String exclude) {} }
            """;

    private static final String DEPENDENCY_GROUP_IDS = """
            package org.apache.maven.plugins.dependency.utils.filters;
            public class GroupIdFilter { public GroupIdFilter(String include, String exclude, boolean exactMatch) {} }
            """;

    private static final String SESSION = """
            package org.apache.maven.api;
            public interface Session {}
            """;

    private static final String MARKS_PATTERN_FILTERS_OF_MAVEN4_PLUGINS_BEFORE = """
            import java.util.List;
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
            import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;

            class Mojo {
                Session session;
                Object[] filters(List<String> includes, boolean transitive) {
                    return new Object[] {
                        new PatternIncludesArtifactFilter(includes, transitive),
                        new PatternExcludesArtifactFilter(List.of("*:*:test-jar"))
                    };
                }
            }
            """;

    private static final String MARKS_PATTERN_FILTERS_OF_MAVEN4_PLUGINS_AFTER = """
            import java.util.List;
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
            import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;

            class Mojo {
                Session session;
                Object[] filters(List<String> includes, boolean transitive) {
                    return new Object[] {
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().includes(includes).transitive(transitive).build())~~>*/new PatternIncludesArtifactFilter(includes, transitive),
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().excludes(List.of("*:*:test-jar")).build())~~>*/new PatternExcludesArtifactFilter(List.of("*:*:test-jar"))
                    };
                }
            }
            """;

    private static final String MARKS_SCOPE_AND_COORDINATE_FILTERS_BEFORE = """
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
            import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
            import org.apache.maven.shared.artifact.filter.collection.TypeFilter;

            class Mojo {
                Session session;
                Object[] filters(String excludeTypes, boolean exact) {
                    return new Object[] {
                        new ScopeFilter("runtime", null),
                        new GroupIdFilter("org.a,org.b", null),
                        new GroupIdFilter(null, "org.c", true),
                        new GroupIdFilter("org.d", "", exact),
                        new TypeFilter("", excludeTypes)
                    };
                }
            }
            """;

    private static final String MARKS_SCOPE_AND_COORDINATE_FILTERS_AFTER = """
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
            import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
            import org.apache.maven.shared.artifact.filter.collection.TypeFilter;

            class Mojo {
                Session session;
                Object[] filters(String excludeTypes, boolean exact) {
                    return new Object[] {
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().includeScope("runtime").build())~~>*/new ScopeFilter("runtime", null),
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().includeValues(Coordinate.GROUP_ID, List.of("org.a", "org.b")).groupIdPrefixMatch(true).build())~~>*/new GroupIdFilter("org.a,org.b", null),
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().excludeValues(Coordinate.GROUP_ID, List.of("org.c")).build())~~>*/new GroupIdFilter(null, "org.c", true),
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().includeValues(Coordinate.GROUP_ID, List.of("org.d")).groupIdPrefixMatch(!exact).build())~~>*/new GroupIdFilter("org.d", "", exact),
                        /*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().excludeValues(Coordinate.TYPE, List.of(excludeTypes.split(","))).build())~~>*/new TypeFilter("", excludeTypes)
                    };
                }
            }
            """;

    private static final String MARKS_THE_DEPENDENCY_PLUGIN_GROUP_ID_FILTER_BEFORE = """
            import org.apache.maven.api.Session;
            import org.apache.maven.plugins.dependency.utils.filters.GroupIdFilter;

            class Mojo {
                Session session;
                Object[] filters() {
                    return new Object[] {new GroupIdFilter("org.a", null, true), new GroupIdFilter("org.a", null, false)};
                }
            }
            """;

    private static final String MARKS_THE_DEPENDENCY_PLUGIN_GROUP_ID_FILTER_AFTER = """
            import org.apache.maven.api.Session;
            import org.apache.maven.plugins.dependency.utils.filters.GroupIdFilter;

            class Mojo {
                Session session;
                Object[] filters() {
                    return new Object[] {/*~~(ArtifactMatcherFactory: ArtifactMatcherRequest.builder().includeValues(Coordinate.GROUP_ID, List.of("org.a")).build())~~>*/new GroupIdFilter("org.a", null, true), /*~~(ArtifactMatcherFactory: no 1:1 request, this GroupIdFilter matches a group id and the group ids nested under it (org.example matches org.example.foo, not org.examples), while groupIdPrefixMatch(true) matches any prefix and the default matches exactly)~~>*/new GroupIdFilter("org.a", null, false)};
                }
            }
            """;

    private static final String LEAVES_MAVEN3_PLUGINS_UNCHANGED_SOURCE = """
            import java.util.List;
            import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
            import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;

            class Maven3Mojo {
                Object[] filters(List<String> includes) {
                    return new Object[] {new PatternIncludesArtifactFilter(includes), new ScopeFilter("test", null)};
                }
            }
            """;

    private static final String LEAVES_UNMAPPED_FILTERS_AND_OTHER_TYPES_UNCHANGED_SOURCE = """
            import java.util.List;
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;

            class Mojo {
                Session session;
                Object[] filters(List<String> includes) {
                    return new Object[] {new StrictPatternIncludesArtifactFilter(includes), new StringBuilder("x")};
                }
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseArtifactMatcherFactory())
                .parser(JavaParser.fromJavaVersion()
                        .dependsOn(FILTERS, EXCLUDES, STRICT, COLLECTION, GROUP_IDS, TYPES, DEPENDENCY_GROUP_IDS, SESSION));
    }

    @Test
    void marksPatternFiltersOfMaven4Plugins() {
        rewriteRun(java(MARKS_PATTERN_FILTERS_OF_MAVEN4_PLUGINS_BEFORE, MARKS_PATTERN_FILTERS_OF_MAVEN4_PLUGINS_AFTER));
    }

    @Test
    void marksScopeAndCoordinateFilters() {
        rewriteRun(java(MARKS_SCOPE_AND_COORDINATE_FILTERS_BEFORE, MARKS_SCOPE_AND_COORDINATE_FILTERS_AFTER));
    }

    @Test
    void marksTheDependencyPluginGroupIdFilter() {
        rewriteRun(java(MARKS_THE_DEPENDENCY_PLUGIN_GROUP_ID_FILTER_BEFORE, MARKS_THE_DEPENDENCY_PLUGIN_GROUP_ID_FILTER_AFTER));
    }

    @Test
    void leavesMaven3PluginsUnchanged() {
        rewriteRun(java(LEAVES_MAVEN3_PLUGINS_UNCHANGED_SOURCE));
    }

    @Test
    void leavesUnmappedFiltersAndOtherTypesUnchanged() {
        rewriteRun(java(LEAVES_UNMAPPED_FILTERS_AND_OTHER_TYPES_UNCHANGED_SOURCE));
    }
}
