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
package org.apache.maven.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.Type;
import org.apache.maven.api.services.ArtifactMatcher;
import org.apache.maven.api.services.ArtifactMatcherFactory;
import org.apache.maven.api.services.ArtifactMatcherRequest;
import org.apache.maven.api.services.ArtifactMatcherRequest.Coordinate;
import org.apache.maven.api.services.ArtifactMatcherResult;
import org.apache.maven.api.services.VersionParser;
import org.apache.maven.di.Injector;
import org.apache.maven.impl.resolver.MavenVersionScheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link DefaultArtifactMatcherFactory}: syntax, scopes, values, trails, selection and requests.
 */
class DefaultArtifactMatcherFactoryTest {

    private static final VersionParser PARSER =
            new DefaultVersionParser(new DefaultModelVersionParser(new MavenVersionScheme()));

    private final ArtifactMatcherFactory factory = new DefaultArtifactMatcherFactory(PARSER);

    private final Dependency jar = dependency("org.example", "core", "jar", "", "1.1", "compile");

    private final Dependency tests = dependency("org.example", "core", "test-jar", "tests", "1.1", "test");

    private final Dependency other = dependency("com.other", "util", "jar", "", "2.0", "runtime");

    @Test
    void mavenInjectorResolvesTheFactory() {
        Injector injector = Injector.create();
        try {
            injector.bindInstance(VersionParser.class, PARSER);
            injector.bindImplicit(DefaultArtifactMatcherFactory.class);
            ArtifactMatcherFactory service = injector.getInstance(ArtifactMatcherFactory.class);
            assertInstanceOf(DefaultArtifactMatcherFactory.class, service);
            assertSame(service, injector.getInstance(ArtifactMatcherFactory.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void requestWithoutCriteriaIncludesAll() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(null, null);
        assertSame(factory.includesAll(), matcher);
        assertTrue(factory.isIncludesAll(matcher));
        assertTrue(factory.isIncludesAll(factory.createArtifactMatcher(
                ArtifactMatcherRequest.builder().transitive(true).build())));
        assertFalse(factory.isIncludesAll(factory.createIncludeOnlyMatcher(List.of("*"))));
        assertTrue(matcher.matches(jar) && matcher.matches(other));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> factory.createArtifactMatcher(null));
        ArtifactMatcher matcher = factory.createIncludeOnlyMatcher(List.of("org.example"));
        assertThrows(NullPointerException.class, () -> matcher.matches(null));
        assertThrows(NullPointerException.class, () -> matcher.matches(jar, null));
        assertThrows(NullPointerException.class, () -> factory.select(null, List.of(jar)));
        assertThrows(NullPointerException.class, () -> factory.isIncludesAll(null));
        assertThrows(NullPointerException.class, () -> new DefaultArtifactMatcherFactory(null));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "org.example|true|true|false",
                "org.*|true|true|false",
                "org.exampl?|true|true|false",
                "org.example:core|true|true|false",
                "org.example:core:jar|true|false|false",
                "org.example:core:jar:1.1|true|false|false",
                "org.example:core:test-jar:tests|false|true|false",
                "org.example:core:test-jar:tests:1.1|false|true|false",
                "org.example:core:*:*:[1.0,2.0)|true|true|false",
                "org.example:core:*:*:(1.0,1.1]|true|true|false",
                "*:*:*:*:[2.0]|false|false|true",
                "*:*:*:*:(,1.0],[2.0,)|false|false|true",
                "*:core|true|true|false",
                "*:jar|true|false|true",
                "*:2.0|false|false|true",
                "*:core:*|true|true|false",
                "*:test-jar:*|false|true|false",
                "*:*:tests|false|true|false",
                "*:org.example:jar|true|false|false",
                "core:*:*|true|true|false",
                "org.example:*:tests|false|true|false",
                "org.example:*:*|true|true|false",
                "::tests|false|true|false",
                "*|true|true|true",
                "*:*|true|true|true",
                "*:*:*|true|true|true",
            })
    void patternSyntax(String pattern, boolean matchesJar, boolean matchesTests, boolean matchesOther) {
        ArtifactMatcher matcher = factory.createIncludeOnlyMatcher(List.of(pattern));
        assertEquals(matchesJar, matcher.matches(jar), "jar");
        assertEquals(matchesTests, matcher.matches(tests), "tests");
        assertEquals(matchesOther, matcher.matches(other), "other");
    }

    @Test
    void versionRangeOnlyInTheFifthToken() {
        assertFalse(factory.createIncludeOnlyMatcher(List.of("org.example:core:jar:[1.0,2.0)"))
                .matches(jar));
        assertTrue(factory.createIncludeOnlyMatcher(List.of("org.example:core:jar:*:[1.0,2.0)"))
                .matches(jar));
    }

    @Test
    void emptyPatternMatchesAllAndBadPatternsAreRejected() {
        assertTrue(factory.createIncludeOnlyMatcher(List.of("")).matches(other));
        assertThrows(IllegalArgumentException.class, () -> factory.createIncludeOnlyMatcher(List.of(":")));
        assertThrows(IllegalArgumentException.class, () -> factory.createIncludeOnlyMatcher(List.of("a:b:c:d:e:f")));
        assertThrows(IllegalArgumentException.class, () -> factory.createIncludeOnlyMatcher(List.of("a:b:c:d:[1.0")));
    }

    @Test
    void rangesThatCanHoldNoVersionAreRejectedAsInMaven3() {
        List.of("[1.0,1.0)", "(1.0,1.0]", "(1.0,1.0.0.Final]", "[1.0,2.0),(3.0,3.0)")
                .forEach(range -> assertThrows(
                        IllegalArgumentException.class,
                        () -> factory.createIncludeOnlyMatcher(List.of("a:b:c:d:" + range)),
                        range));
        assertTrue(
                factory.createIncludeOnlyMatcher(List.of("*:*:*:*:[1.1,1.1]")).matches(jar));
    }

    @Test
    void firstMatchingPatternDecides() {
        ArtifactMatcher includes = factory.createIncludeOnlyMatcher(List.of("!*:*:test-jar", "org.example"));
        assertTrue(includes.matches(jar));
        assertFalse(includes.matches(tests));
        ArtifactMatcher excludes = factory.createExcludeOnlyMatcher(List.of("!org.example:core:jar", "org.example"));
        assertTrue(excludes.matches(jar));
        assertFalse(excludes.matches(tests));
        assertTrue(excludes.matches(other));
        assertFalse(factory.createIncludeOnlyMatcher(List.of("!!org.example")).matches(jar));
    }

    @Test
    void includesAndExcludesCompose() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(List.of("org.example", "com.*"), List.of("*:util"));
        assertTrue(matcher.matches(jar));
        assertFalse(matcher.matches(other));
        assertTrue(matcher.and(factory.createIncludeOnlyMatcher(List.of("*:*:jar")))
                .test(jar));
        assertFalse(matcher.negate().test(jar));
    }

    @Test
    void plainArtifactUsesExtensionAsTypeAndHasNoScope() {
        Artifact artifact = artifact("org.example", "core", "zip", "dist", "1.1");
        assertTrue(factory.createIncludeOnlyMatcher(List.of("*:*:zip:dist")).matches(artifact));
        ArtifactMatcher compileOnly = factory.createArtifactMatcher(
                ArtifactMatcherRequest.builder().excludeScope("compile").build());
        assertFalse(compileOnly.matches(artifact), "Maven 3 ScopeFilter excludes artifacts without scope");
    }

    @Test
    void emptyClassifierIsNoClassifier() {
        assertFalse(factory.createIncludeOnlyMatcher(List.of("*:*:*:?*:*")).matches(jar));
        assertTrue(factory.createIncludeOnlyMatcher(List.of("*:*:*:?*:*")).matches(tests));
        assertTrue(factory.createIncludeOnlyMatcher(List.of("*:*:*:**:*")).matches(jar));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "-",
            value = {
                "compile|-|compile provided system compile-only -",
                "runtime|-|compile runtime compile-only -",
                "test|-|compile provided system runtime test compile-only -",
                "provided|-|provided",
                "system|-|system",
                "test|test|compile provided system runtime test compile-only -",
                "-|compile|runtime test",
                "-|runtime|provided system test",
                "-|provided|compile system runtime test compile-only -",
                "-|system|compile provided runtime test compile-only -",
                "-|-|compile provided system runtime test compile-only -",
            })
    void scopes(String include, String exclude, String selected) {
        ArtifactMatcher matcher = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includeScope(include)
                .excludeScope(exclude)
                .build());
        List<String> scopes = Arrays.asList("compile", "provided", "system", "runtime", "test", "compile-only", null);
        List<String> actual = new ArrayList<>();
        scopes.stream()
                .filter(scope -> matcher.matches(scoped(scope)))
                .forEach(scope -> actual.add(scope == null ? "-" : scope));
        assertEquals(selected, String.join(" ", actual));
    }

    @Test
    void invalidScopesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> scopeMatcher("cOmpile", null));
        assertThrows(IllegalArgumentException.class, () -> scopeMatcher(null, "import"));
        assertThrows(IllegalArgumentException.class, () -> scopeMatcher(null, "test"));
        assertTrue(scopeMatcher("compile", "nonsense").matches(jar), "the include scope wins");
        assertTrue(factory.isIncludesAll(scopeMatcher("", "")));
    }

    @Test
    void coordinateValues() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includeValues(Coordinate.GROUP_ID, List.of("org.example", "com.other"))
                .excludeValues(Coordinate.TYPE, List.of("test-jar"))
                .build());
        assertTrue(matcher.matches(jar));
        assertFalse(matcher.matches(tests));
        assertTrue(matcher.matches(other));
        ArtifactMatcher classifier = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includeValues(Coordinate.CLASSIFIER, List.of("tests"))
                .excludeValues(Coordinate.ARTIFACT_ID, List.of("util"))
                .build());
        assertFalse(classifier.matches(jar));
        assertTrue(classifier.matches(tests));
    }

    @Test
    void groupIdsMatchExactlyUnlessPrefixMatchIsSet() {
        Dependency sub = dependency("org.example.foo", "b", "jar", "", "1.0", "compile");
        ArtifactMatcherRequest.ArtifactMatcherRequestBuilder builder =
                ArtifactMatcherRequest.builder().includeValues(Coordinate.GROUP_ID, List.of("org.example"));
        assertFalse(factory.createArtifactMatcher(builder.build()).matches(sub));
        assertTrue(
                factory.createArtifactMatcher(builder.groupIdPrefixMatch(true).build())
                        .matches(sub));
        ArtifactMatcher exclude = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .excludeValues(Coordinate.GROUP_ID, List.of("org.ex"))
                .groupIdPrefixMatch(true)
                .build());
        assertFalse(exclude.matches(sub));
        assertTrue(exclude.matches(other));
    }

    @Test
    void transitiveRequestsLookAtTrailsOfMoreThanOneElement() {
        Dependency root = dependency("my", "project", "jar", "", "1.0", "compile");
        List<Dependency> trail = List.of(root, other, jar);
        ArtifactMatcherRequest.ArtifactMatcherRequestBuilder builder =
                ArtifactMatcherRequest.builder().includes(List.of("com.other"));
        assertFalse(factory.createArtifactMatcher(builder.build()).matches(jar, trail));
        ArtifactMatcher transitive =
                factory.createArtifactMatcher(builder.transitive(true).build());
        assertTrue(transitive.matches(jar, trail));
        assertFalse(transitive.matches(jar, List.of(other)), "a one-element trail is ignored");
        assertFalse(transitive.matches(jar));
    }

    @Test
    void selectKeepsOrderAndReportsUntriggeredPatterns() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(
                List.of("org.example", "org.example", "never:matched"), List.of("*:*:test-jar", "nothing:here"));
        ArtifactMatcherResult<Dependency> result = factory.select(matcher, List.of(other, tests, jar));
        assertEquals(List.of(jar), result.getIncluded());
        assertEquals(List.of(other, tests), result.getExcluded());
        assertEquals(List.of("never:matched", "nothing:here"), result.getUntriggeredPatterns());
        assertTrue(result.hasMissedCriteria());
        assertThrows(
                UnsupportedOperationException.class, () -> result.getIncluded().clear());
    }

    @Test
    void selectStopsAtTheScopeBeforePatterns() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includeScope("provided")
                .includes(List.of("com.other"))
                .transitive(true)
                .build());
        ArtifactMatcherResult<Dependency> result =
                factory.select(matcher, List.of(jar), artifact -> List.of(other, jar));
        assertEquals(List.of(jar), result.getExcluded());
        assertEquals(List.of("com.other"), result.getUntriggeredPatterns(), "scope rejected it before patterns");
    }

    @Test
    void selectCountsTrailMatches() {
        ArtifactMatcher matcher = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includes(List.of("com.other"))
                .transitive(true)
                .build());
        ArtifactMatcherResult<Dependency> result =
                factory.select(matcher, List.of(jar), artifact -> List.of(other, jar));
        assertEquals(List.of(jar), result.getIncluded());
        assertFalse(result.hasMissedCriteria());
    }

    @Test
    void selectWithForeignMatcherReportsNoPatterns() {
        ArtifactMatcher foreign = (artifact, trail) -> artifact.groupId().startsWith("com");
        ArtifactMatcherResult<Dependency> result = factory.select(foreign, List.of(jar, other));
        assertEquals(List.of(other), result.getIncluded());
        assertEquals(List.of(jar), result.getExcluded());
        assertFalse(result.hasMissedCriteria());
        assertFalse(factory.isIncludesAll(foreign));
    }

    @Test
    void requestsCopyAndNormalizeTheirInput() {
        List<String> includes = new ArrayList<>(List.of("a:b"));
        ArtifactMatcherRequest request = ArtifactMatcherRequest.builder()
                .includes(includes)
                .includeValues(Coordinate.TYPE, List.of())
                .includeScope("")
                .build();
        includes.add("c:d");
        assertEquals(List.of("a:b"), request.getIncludes());
        assertEquals(List.of(), request.getIncludeValues(Coordinate.TYPE));
        assertEquals(null, request.getIncludeScope());
        assertThrows(
                UnsupportedOperationException.class, () -> request.getIncludes().add("x"));
    }

    @Test
    void requestsAreValues() {
        ArtifactMatcherRequest request = ArtifactMatcherRequest.builder()
                .includes(List.of("a:b"))
                .includeValues(Coordinate.TYPE, List.of())
                .build();
        ArtifactMatcherRequest same =
                ArtifactMatcherRequest.builder().includes(List.of("a:b")).build();
        assertEquals(same, request);
        assertEquals(same.hashCode(), request.hashCode());
        assertEquals(same.toString(), request.toString());
        assertNotEquals(
                same, ArtifactMatcherRequest.builder().excludes(List.of("a:b")).build());
        assertNotSame(request, same);
    }

    @Test
    void requestsRejectNulls() {
        assertThrows(
                NullPointerException.class,
                () -> ArtifactMatcherRequest.builder().includes(Arrays.asList("a", null)));
        assertThrows(
                NullPointerException.class,
                () -> ArtifactMatcherRequest.builder().includeValues(null, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> ArtifactMatcherRequest.builder().build().getIncludeValues(null));
    }

    private ArtifactMatcher scopeMatcher(String include, String exclude) {
        return factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includeScope(include)
                .excludeScope(exclude)
                .build());
    }

    private static Artifact scoped(String scope) {
        return scope == null
                ? artifact("g", "plain", "jar", "", "1.0")
                : dependency("g", "a-" + scope, "jar", "", "1.0", scope);
    }

    static Dependency dependency(
            String groupId, String artifactId, String type, String classifier, String version, String scope) {
        Dependency dependency = mock(Dependency.class);
        coordinates(dependency, groupId, artifactId, classifier, version);
        Type typeObject = mock(Type.class);
        when(typeObject.id()).thenReturn(type);
        when(dependency.type()).thenReturn(typeObject);
        when(dependency.scope()).thenReturn(DependencyScope.forId(scope));
        return dependency;
    }

    static Artifact artifact(String groupId, String artifactId, String extension, String classifier, String version) {
        Artifact artifact = mock(Artifact.class);
        coordinates(artifact, groupId, artifactId, classifier, version);
        when(artifact.extension()).thenReturn(extension);
        return artifact;
    }

    private static void coordinates(
            Artifact artifact, String groupId, String artifactId, String classifier, String version) {
        when(artifact.groupId()).thenReturn(groupId);
        when(artifact.artifactId()).thenReturn(artifactId);
        when(artifact.classifier()).thenReturn(classifier);
        when(artifact.baseVersion()).thenReturn(PARSER.parseVersion(version));
        when(artifact.toString()).thenReturn(groupId + ":" + artifactId);
    }
}
