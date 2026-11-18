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
package org.apache.maven.artifact.resolver.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.Type;
import org.apache.maven.api.services.ArtifactMatcher;
import org.apache.maven.api.services.ArtifactMatcherFactory;
import org.apache.maven.api.services.ArtifactMatcherRequest;
import org.apache.maven.api.services.ArtifactMatcherRequest.Coordinate;
import org.apache.maven.api.services.ArtifactMatcherResult;
import org.apache.maven.api.services.VersionParser;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.impl.DefaultArtifactMatcherFactory;
import org.apache.maven.impl.DefaultModelVersionParser;
import org.apache.maven.impl.DefaultVersionParser;
import org.apache.maven.impl.resolver.MavenVersionScheme;
import org.apache.maven.shared.artifact.filter.PatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.collection.ClassifierFilter;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.artifact.filter.collection.TypeFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Parity of {@link DefaultArtifactMatcherFactory} with the code it replaces: Apache Maven Common Artifact Filters
 * 3.4.0 ({@code PatternIncludesArtifactFilter}, {@code PatternExcludesArtifactFilter}, and the {@code collection}
 * filters {@code ScopeFilter}, {@code GroupIdFilter}, {@code ArtifactIdFilter}, {@code TypeFilter},
 * {@code ClassifierFilter}). Each case runs the same input through the Maven 3 filter (on a Maven 3 artifact) and
 * through the matcher (on the equivalent Maven 4 dependency) and compares the answers. The cases are the test
 * vectors of that library (master a11a231759800e38a5c46822f1cc04b867bc5670) and generated corpora. The table of
 * suites, cases and differences is written to {@code target/artifact-matcher-parity.tsv}.
 */
class ArtifactMatcherFactoryParityTest {

    private static final VersionParser PARSER =
            new DefaultVersionParser(new DefaultModelVersionParser(new MavenVersionScheme()));

    private static final ArtifactMatcherFactory FACTORY = new DefaultArtifactMatcherFactory(PARSER);

    private static final Map<String, Tally> TABLE = new TreeMap<>();

    private static final String EXACT_MASTER = "groupid-exact-master-vector";

    /** Suite of the 3.4.0 behaviours that the library's master changed and the matcher follows master in. */
    private static final String KNOWN = "known-differences";

    /** The reference of the suites that are not compared with the 3.4.0 jar alone. */
    private static final Map<String, String> REFERENCES = Map.of(
            "groupid-exact-master-vector",
            "master test expectations (no released jar)",
            "upstream-missed-criteria",
            "3.4.0 jar; after include: master test expectation",
            "missed-criteria",
            "3.4.0 jar with duplicated patterns counted once, as master does",
            KNOWN,
            "3.4.0 jar behaviour that master changed (asserted as is)");

    /** The artifacts of the generated pattern corpus, as {@code g:a:t:c:v:scope} (empty classifier: none). */
    private static final List<String> CORPUS = List.of(
            "org.example:core:jar::1.1:compile",
            "org.example:core:test-jar:tests:1.1:test",
            "org.example.foo:core:jar::2.0:runtime",
            "com.other:util:jar::2.0:runtime",
            "org.example:jar:pom::1.0:compile",
            "core:org.example:jar:sources:1.1-SNAPSHOT:provided",
            "org.example:core:jar:1.1:1.1:system",
            "org.example:core:zip:jdk8:1.1:compile");

    /** Token pools of the generated pattern corpus. */
    private static final List<String> TOKENS =
            List.of("*", "", "org.example", "core", "jar", "tests", "1.1", "org.*", "c?re", "*o*", "x");

    private static final List<List<String>> FIVE_TOKEN_POOLS = List.of(
            List.of("*", "org.example", "org.*"),
            List.of("*", "core", "co*"),
            List.of("*", "jar", "test-jar"),
            List.of("*", "tests", "", "1.1", "jdk?"),
            List.of("*", "1.1", "[1.0,2.0)", "(1.1,)", "2.*", "(,1.0],[2.0,)"));

    private static final List<List<String>> TRAILS = List.of(
            List.of(),
            List.of("current:project:jar::1.0:compile"),
            List.of(
                    "current:project:jar::1.0:compile",
                    "com.other:util:jar::2.0:compile",
                    "org.example:core:jar::1.1:compile"),
            List.of("current:project:jar::1.0:compile", "org.example:core:test-jar:tests:1.1:test"));

    private static final List<String> RANGES = List.of(
            "[1.0,2.0)",
            "(1.0,2.0]",
            "[1.0]",
            "(,1.0]",
            "[1.5,)",
            "(1.0,2.0),[3.0,)",
            "[1.0-SNAPSHOT,1.0]",
            "[1.0-alpha,1.0]",
            "(1.0-rc1,1.0.1)",
            "[1.0-sp1,2)",
            "[1.0,1.0)",
            "(1.0,1.0]",
            "(1.0,1.0.0.Final]",
            "[1.0,2.0),(3.0,3.0)");

    private static final List<String> VERSIONS = List.of(
            "0.9",
            "1",
            "1.0",
            "1.0-SNAPSHOT",
            "1.0-alpha-1",
            "1.0.0",
            "1.0-ga",
            "1.0-final",
            "1.0-RC1",
            "1.0-rc-2",
            "1.0-sp1",
            "1.0.1-SNAPSHOT",
            "1.1",
            "1.5",
            "2.0",
            "2.0-SNAPSHOT",
            "3.0",
            "1.0-beta");

    private static final List<String> FILTER_SCOPES =
            Arrays.asList(null, "", "compile", "runtime", "test", "provided", "system", "cOmpile", "import");

    private static final List<String> SUBJECT_SCOPES =
            List.of("compile", "provided", "test", "runtime", "system", "", "compile-only", "test-runtime");

    @AfterAll
    static void writeParityTable() throws IOException {
        StringBuilder tsv = new StringBuilder("suite\treference\tcases\tequal\tdifferent\tfirst_differences\n");
        TABLE.forEach((suite, tally) -> tsv.append(tally.row(suite)).append('\n'));
        Path target = Path.of(System.getProperty("basedir", "."), "target", "artifact-matcher-parity.tsv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, tsv, StandardCharsets.UTF_8);
    }

    @Test
    void upstreamPatternVectors() {
        UpstreamVectors.ALL.forEach(vector -> comparePatterns(
                "upstream-pattern-vectors", vector.patterns(), vector.transitive(), vector.artifacts()));
        assertNoDifference("upstream-pattern-vectors");
    }

    @Test
    void upstreamMissedCriteriaVector() {
        Subject subject = Subject.of("org.python:jython-standalone:jar::1.0:compile", null);
        List<String> patterns = List.of("org.python:jython-standalone", "org.python:jython-standalone");
        PatternIncludesArtifactFilter v3 = new PatternIncludesArtifactFilter(patterns);
        String suite = "upstream-missed-criteria";
        compare(
                suite,
                "before",
                v3.hasMissedCriteria(),
                select(patterns, List.of()).hasMissedCriteria());
        v3.include(subject.v3());
        compare(suite, "after", false, select(patterns, List.of(subject)).hasMissedCriteria());
        compare(KNOWN, "3.4.0 reports a matched duplicated pattern as missed", true, v3.hasMissedCriteria());
        Stream.of(suite, KNOWN).forEach(ArtifactMatcherFactoryParityTest::assertNoDifference);
    }

    @Test
    void globsAgainstGroupIdsExhaustive() {
        List<String> patterns = words("ab*?", 5);
        List<Subject> subjects = words("ab", 5).stream()
                .map(value -> Subject.of(value + ":a:t::1:compile", null))
                .toList();
        patterns.forEach(pattern -> comparePatterns("glob-exhaustive", List.of(pattern), false, subjects));
        assertNoDifference("glob-exhaustive");
    }

    @Test
    void generatedPatternCorpus() {
        List<Subject> subjects =
                CORPUS.stream().map(spec -> Subject.of(spec, null)).toList();
        generatedPatterns().forEach(pattern -> comparePatterns("pattern-corpus", List.of(pattern), false, subjects));
        assertNoDifference("pattern-corpus");
    }

    @Test
    void negationAndOrder() {
        List<String> pool = List.of(
                "org.example", "!org.example", "*:*:test-jar", "!*:*:test-jar", "!*:core", "*:core", "!", "com.*");
        List<Subject> subjects =
                CORPUS.stream().map(spec -> Subject.of(spec, null)).toList();
        pool.forEach(first ->
                pool.forEach(second -> comparePatterns("negation-order", List.of(first, second), false, subjects)));
        assertNoDifference("negation-order");
    }

    @Test
    void transitiveTrails() {
        List<Subject> subjects = TRAILS.stream()
                .flatMap(trail -> Stream.of(Subject.of(CORPUS.get(3), trail), Subject.of(CORPUS.get(4), trail)))
                .toList();
        List<String> patterns = List.of("com.other", "*:*:tests", "current*", "!com.other", "org.example:jar");
        Stream.of(true, false)
                .forEach(transitive -> patterns.forEach(
                        pattern -> comparePatterns("transitive", List.of(pattern), transitive, subjects)));
        assertNoDifference("transitive");
    }

    @Test
    void versionRanges() {
        List<Subject> subjects = VERSIONS.stream()
                .map(version -> Subject.of("g:a:jar::" + version + ":compile", null))
                .toList();
        RANGES.forEach(range -> comparePatterns("version-ranges", List.of("*:*:*:*:" + range), false, subjects));
        assertNoDifference("version-ranges");
    }

    @Test
    void missedCriteriaOverTheCorpus() {
        List<Subject> subjects =
                CORPUS.stream().map(spec -> Subject.of(spec, null)).toList();
        List<List<String>> lists = List.of(
                List.of("org.example", "never", "*:*:test-jar"),
                List.of("*:core", "org.example", "!com.other"),
                List.of("x:y", "x:y", "*:util"));
        lists.forEach(patterns -> {
            compareMissed(patterns, subjects, false);
            compareMissed(patterns, subjects, true);
        });
        assertNoDifference("missed-criteria");
    }

    @Test
    void scopeFilter() {
        List<Subject> subjects = Stream.concat(
                        SUBJECT_SCOPES.stream().map(scope -> "g:a-" + scope + ":jar::1.0:" + scope),
                        Stream.of("g:none:jar::1.0"))
                .map(spec -> Subject.of(spec, null))
                .toList();
        FILTER_SCOPES.forEach(include -> FILTER_SCOPES.forEach(exclude -> compareSets(
                "scope-filter",
                include + "|" + exclude,
                subjects,
                set -> new ScopeFilter(include, exclude).filter(set),
                () -> ArtifactMatcherRequest.builder().includeScope(include).excludeScope(exclude))));
        assertNoDifference("scope-filter");
    }

    @Test
    void coordinateValueFilters() {
        compareFeature(Coordinate.GROUP_ID, List.of("org.example", "org", "com.other", "o", "org.example.foo", "x"));
        compareFeature(Coordinate.ARTIFACT_ID, List.of("core", "util", "jar", "x"));
        compareFeature(Coordinate.TYPE, List.of("jar", "test-jar", "pom", "x"));
        compareFeature(Coordinate.CLASSIFIER, List.of("tests", "sources", "jdk8", "x"));
        assertNoDifference("value-filters");
    }

    /**
     * The exact group id match of the library's master ({@code GroupIdFilter(include, exclude, true)}, since 3.5.0)
     * has no released jar: the expected answers are those of its test {@code exactMatchIgnoresLongerGroupIds}.
     */
    @Test
    void groupIdExactMatchMasterVector() {
        List<String> groupId = List.of("org.example");
        exactMatchVector(
                "prefix",
                "[org.example:a, org.example.foo:b]",
                builder -> builder.includeValues(Coordinate.GROUP_ID, groupId).groupIdPrefixMatch(true));
        exactMatchVector("include", "[org.example:a]", b -> b.includeValues(Coordinate.GROUP_ID, groupId));
        exactMatchVector("exclude", "[org.example.foo:b]", b -> b.excludeValues(Coordinate.GROUP_ID, groupId));
        assertNoDifference(EXACT_MASTER);
    }

    private static void exactMatchVector(
            String name, String expected, UnaryOperator<ArtifactMatcherRequest.ArtifactMatcherRequestBuilder> request) {
        List<Subject> subjects = List.of(
                Subject.of("org.example:a:jar::1.0:compile", null),
                Subject.of("org.example.foo:b:jar::1.0:compile", null));
        compare(EXACT_MASTER, name, expected, selected(subjects, request.apply(ArtifactMatcherRequest.builder())));
    }

    // ---- comparisons

    private static void comparePatterns(
            String suite, List<String> patterns, boolean transitive, List<Subject> subjects) {
        String v3 = outcome(
                () -> new PatternIncludesArtifactFilter(patterns).toString().isEmpty() ? "" : "OK");
        String v4 = outcome(
                () -> FACTORY.createIncludeOnlyMatcher(patterns).toString().isEmpty() ? "" : "OK");
        compare(suite, "compile " + patterns, v3, v4);
        if ("OK".equals(v3) && "OK".equals(v4)) {
            compareDecisions(suite, patterns, transitive, subjects);
        }
    }

    private static void compareDecisions(
            String suite, List<String> patterns, boolean transitive, List<Subject> subjects) {
        PatternIncludesArtifactFilter includes = new PatternIncludesArtifactFilter(patterns, transitive);
        PatternExcludesArtifactFilter excludes = new PatternExcludesArtifactFilter(patterns, transitive);
        ArtifactMatcher include = matcher(ArtifactMatcherRequest.builder().includes(patterns), transitive);
        ArtifactMatcher exclude = matcher(ArtifactMatcherRequest.builder().excludes(patterns), transitive);
        subjects.forEach(subject -> {
            String name = patterns + " " + transitive + " " + subject;
            compare(suite, "include " + name, includes.include(subject.v3()), subject.matchedBy(include));
            compare(suite, "exclude " + name, excludes.include(subject.v3()), subject.matchedBy(exclude));
        });
    }

    private static ArtifactMatcher matcher(
            ArtifactMatcherRequest.ArtifactMatcherRequestBuilder builder, boolean transitive) {
        return FACTORY.createArtifactMatcher(builder.transitive(transitive).build());
    }

    private static void compareMissed(List<String> patterns, List<Subject> subjects, boolean exclude) {
        PatternIncludesArtifactFilter v3 =
                exclude ? new PatternExcludesArtifactFilter(patterns) : new PatternIncludesArtifactFilter(patterns);
        subjects.forEach(subject -> v3.include(subject.v3()));
        Logger logger = mock(Logger.class);
        when(logger.isWarnEnabled()).thenReturn(true);
        v3.reportMissedCriteria(logger);
        ArtifactMatcherRequest.ArtifactMatcherRequestBuilder builder = ArtifactMatcherRequest.builder();
        ArtifactMatcherRequest request = (exclude ? builder.excludes(patterns) : builder.includes(patterns)).build();
        List<Dependency> artifacts = subjects.stream().map(Subject::v4).toList();
        List<String> untriggered = FACTORY.select(FACTORY.createArtifactMatcher(request), artifacts)
                .getUntriggeredPatterns();
        List<String> reference = new ArrayList<>(new LinkedHashSet<>(missed(logger)));
        compare("missed-criteria", patterns + " exclude=" + exclude, reference.toString(), untriggered.toString());
    }

    /** The patterns that {@code reportMissedCriteria} logged, in log order. */
    private static List<String> missed(Logger logger) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        try {
            verify(logger).warn(message.capture());
        } catch (AssertionError none) {
            return List.of();
        }
        Matcher line = Pattern.compile("o  '(.*)'").matcher(message.getValue());
        return line.results().map(result -> result.group(1)).toList();
    }

    private static void compareFeature(Coordinate coordinate, List<String> pool) {
        List<Subject> subjects =
                CORPUS.stream().map(spec -> Subject.of(spec, null)).toList();
        List<String> lists = valueLists(pool);
        lists.forEach(include -> lists.forEach(exclude -> compareSets(
                "value-filters",
                coordinate + " " + include + "|" + exclude,
                subjects,
                set -> featureFilter(coordinate, include, exclude).filter(set),
                () -> ArtifactMatcherRequest.builder()
                        .includeValues(coordinate, values(include))
                        .excludeValues(coordinate, values(exclude))
                        .groupIdPrefixMatch(true))));
    }

    private static ArtifactsFilter featureFilter(Coordinate coordinate, String include, String exclude) {
        return switch (coordinate) {
            case GROUP_ID -> new GroupIdFilter(include, exclude);
            case ARTIFACT_ID -> new ArtifactIdFilter(include, exclude);
            case TYPE -> new TypeFilter(include, exclude);
            case CLASSIFIER -> new ClassifierFilter(include, exclude);
        };
    }

    /** As the Maven 3 feature filters read a comma-separated list. */
    private static List<String> values(String list) {
        return list != null && !list.isEmpty() ? Arrays.asList(list.split(",")) : List.of();
    }

    /** {@code null}, the empty list, single values, pairs, and single values with a trailing comma. */
    private static List<String> valueLists(List<String> pool) {
        List<String> lists = new ArrayList<>(Arrays.asList(null, ""));
        lists.addAll(pool);
        pool.forEach(value -> lists.add(value + ","));
        IntStream.range(0, pool.size())
                .forEach(i ->
                        IntStream.range(i + 1, pool.size()).forEach(j -> lists.add(pool.get(i) + "," + pool.get(j))));
        return lists;
    }

    private static void compareSets(
            String suite,
            String name,
            List<Subject> subjects,
            V3SetFilter v3,
            Supplier<ArtifactMatcherRequest.ArtifactMatcherRequestBuilder> request) {
        Set<Artifact> v3Set = subjects.stream().map(Subject::v3).collect(Collectors.toCollection(LinkedHashSet::new));
        compare(suite, name, outcome(() -> ids(v3.filter(v3Set))), selected(subjects, request.get()));
    }

    private static String selected(
            List<Subject> subjects, ArtifactMatcherRequest.ArtifactMatcherRequestBuilder request) {
        return outcome(() -> {
            ArtifactMatcher matcher = FACTORY.createArtifactMatcher(request.build());
            List<Dependency> artifacts = subjects.stream().map(Subject::v4).toList();
            return FACTORY.select(matcher, artifacts).getIncluded().stream()
                    .map(dependency -> dependency.groupId() + ":" + dependency.artifactId())
                    .toList()
                    .toString();
        });
    }

    private static String ids(Collection<Artifact> artifacts) {
        return artifacts.stream()
                .map(artifact -> artifact.getGroupId() + ":" + artifact.getArtifactId())
                .toList()
                .toString();
    }

    /** The answer, or {@code ERROR} when the code under comparison throws. */
    private static String outcome(ThrowingSupplier answer) {
        try {
            return answer.get();
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private static ArtifactMatcherResult<Dependency> select(List<String> patterns, List<Subject> subjects) {
        ArtifactMatcher matcher = FACTORY.createIncludeOnlyMatcher(patterns);
        return FACTORY.select(matcher, subjects.stream().map(Subject::v4).toList());
    }

    private static List<String> generatedPatterns() {
        List<String> patterns = new ArrayList<>(TOKENS);
        TOKENS.forEach(first -> TOKENS.forEach(second -> patterns.add(first + ":" + second)));
        TOKENS.forEach(first ->
                TOKENS.forEach(second -> TOKENS.forEach(third -> patterns.add(first + ":" + second + ":" + third))));
        patterns.addAll(product(FIVE_TOKEN_POOLS.subList(0, 4)));
        patterns.addAll(product(FIVE_TOKEN_POOLS));
        patterns.addAll(
                patterns.stream().limit(40).map(pattern -> "!" + pattern).toList());
        return patterns;
    }

    /** The patterns made of one token of each pool, in order. */
    private static List<String> product(List<List<String>> pools) {
        if (pools.size() == 1) {
            return pools.get(0);
        }
        List<String> last = pools.get(pools.size() - 1);
        return product(pools.subList(0, pools.size() - 1)).stream()
                .flatMap(prefix -> last.stream().map(token -> prefix + ":" + token))
                .toList();
    }

    /** All words over the alphabet up to the given length, the empty word included. */
    private static List<String> words(String alphabet, int maxLength) {
        List<String> words = new ArrayList<>(List.of(""));
        IntStream.range(0, maxLength)
                .forEach(round -> words.addAll(words.stream()
                        .filter(word -> word.length() == round)
                        .flatMap(word -> alphabet.chars().mapToObj(c -> word + (char) c))
                        .toList()));
        return words;
    }

    private static void compare(String suite, String name, Object expected, Object actual) {
        TABLE.computeIfAbsent(suite, Tally::new).add(name, expected, actual);
    }

    private static void assertNoDifference(String suite) {
        assertEquals(List.of(), TABLE.get(suite).differences, suite);
    }

    // ---- fixtures

    /** A filter of a set of Maven 3 artifacts. */
    @FunctionalInterface
    interface V3SetFilter {
        Set<Artifact> filter(Set<Artifact> artifacts) throws Exception;
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        String get() throws Exception;
    }

    /** The cases and differences of one suite. */
    static final class Tally {
        private final String reference;
        private int cases;
        private final List<String> differences = new ArrayList<>();

        Tally(String suite) {
            this.reference = REFERENCES.getOrDefault(suite, "3.4.0 jar");
        }

        void add(String name, Object expected, Object actual) {
            cases++;
            if (!expected.equals(actual)) {
                differences.add(name + ": reference=" + expected + " matcher=" + actual);
            }
        }

        String row(String suite) {
            return String.join(
                    "\t",
                    suite,
                    reference,
                    String.valueOf(cases),
                    String.valueOf(cases - differences.size()),
                    String.valueOf(differences.size()),
                    differences.stream().limit(5).collect(Collectors.joining(" ; ")));
        }
    }

    /**
     * One artifact as a Maven 3 artifact and as a Maven 4 dependency, with its dependency trail.
     *
     * @param v3 the Maven 3 artifact
     * @param v4 the Maven 4 dependency
     * @param trail the trail as Maven 4 dependencies
     * @param spec the {@code g:a:t:c:v:scope} text
     */
    record Subject(Artifact v3, Dependency v4, List<Dependency> trail, String spec) {

        static Subject of(String spec, List<String> trail) {
            String[] p = Stream.of(spec.split(":", -1))
                    .map(text -> text.isEmpty() ? null : text)
                    .toArray(String[]::new);
            String scope = p.length > 5 ? (p[5] == null ? "" : p[5]) : null;
            List<String> trailIds =
                    trail == null ? null : trail.stream().map(Subject::trailId).toList();
            return new Subject(
                    mavenThreeArtifact(p[0], p[1], p[2], p[3], p[4], scope, trailIds),
                    mavenFourDependency(p[0], p[1], p[2], p[3], p[4], scope),
                    trail == null
                            ? List.of()
                            : trail.stream().map(t -> of(t, null).v4()).toList(),
                    spec);
        }

        boolean matchedBy(ArtifactMatcher matcher) {
            return matcher.matches(v4, trail);
        }

        /** The Maven 3 dependency trail form: {@code g:a:t:v} or {@code g:a:t:c:v}. */
        static String trailId(String spec) {
            String[] p = spec.split(":", -1);
            return p[3].isEmpty()
                    ? String.join(":", p[0], p[1], p[2], p[4])
                    : String.join(":", p[0], p[1], p[2], p[3], p[4]);
        }

        @Override
        public String toString() {
            return spec + (trail.isEmpty() ? "" : " trail=" + trail.size());
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static Artifact mavenThreeArtifact(
            String g, String a, String t, String c, String v, String scope, List<String> trail) {
        Artifact artifact = mock(Artifact.class, withSettings().stubOnly());
        when(artifact.getGroupId()).thenReturn(g);
        when(artifact.getArtifactId()).thenReturn(a);
        when(artifact.getType()).thenReturn(t);
        when(artifact.hasClassifier()).thenReturn(c != null);
        when(artifact.getClassifier()).thenReturn(c);
        when(artifact.getBaseVersion()).thenReturn(v);
        when(artifact.getVersion()).thenReturn(v);
        when(artifact.getScope()).thenReturn(scope);
        when(artifact.getDependencyTrail()).thenReturn(trail);
        return artifact;
    }

    private static Dependency mavenFourDependency(String g, String a, String t, String c, String v, String scope) {
        Dependency dependency = mock(Dependency.class, withSettings().stubOnly());
        when(dependency.groupId()).thenReturn(g);
        when(dependency.artifactId()).thenReturn(a);
        Type type = mock(Type.class, withSettings().stubOnly());
        when(type.id()).thenReturn(t);
        when(dependency.type()).thenReturn(type);
        when(dependency.classifier()).thenReturn(c == null ? "" : c);
        when(dependency.baseVersion()).thenReturn(v == null ? null : PARSER.parseVersion(v));
        when(dependency.scope()).thenReturn(scope == null ? null : DependencyScope.forId(scope));
        return dependency;
    }

    /** The vectors of {@code AbstractPatternArtifactFilterTest}, master a11a231759800e38a5c46822f1cc04b867bc5670. */
    record UpstreamVectors(List<String> patterns, boolean transitive, List<Subject> artifacts) {
        private static final List<String> TRAIL =
                List.of("current:project:jar::1.0", "otherGroup:otherArtifact:jar::1.0");

        static final List<UpstreamVectors> ALL = List.of(
                plain(
                        List.of("group:artifact:*", "group2:artifact2:*"),
                        "group:artifact:jar::version",
                        "group2:artifact2:jar::version"),
                plain(List.of("group*", "group2*"), "group:artifact:jar::version", "group2:artifact2:jar::version"),
                plain(List.of("group:artifact"), "group:artifact:jar::version"),
                plain(List.of("group:artifact:jar"), "group:artifact:jar::version"),
                plain(List.of("otherGroup:artifact:jar", "otherGroup:artifact"), "group:artifact:jar::version"),
                plain(List.of("groupotherArtifact:jar", "groupotherArtifact"), "group:artifact:jar::version"),
                plain(
                        List.of("otherGroup:otherArtifact:jar", "otherGroup:otherArtifact"),
                        "group:artifact:jar::version"),
                trail(List.of("otherGroup:otherArtifact"), TRAIL),
                trail(List.of("otherGroup*"), TRAIL),
                plain(List.of("!group:artifact:jar"), "group:artifact:jar::version"),
                plain(List.of("group:*:jar"), "group:artifact:jar::version"),
                plain(List.of("*:artifact:*"), "group:artifact:jar::version"),
                plain(List.of("group:some-*-id"), "group:some-artifact-id:jar::version"),
                plain(List.of("some.group*"), "some.group.id:some-artifact-id:jar::version"),
                new UpstreamVectors(
                        List.of("*:jar:*"),
                        true,
                        List.of(
                                Subject.of("group:artifact:jar::version", null),
                                Subject.of("otherGroup:otherArtifact:ejb::version", List.of()))),
                plain(List.of("com.mycompany.*:*:jar:*:*"), "com.mycompany.myproject:some-artifact-id:jar::version"),
                plain(
                        List.of("com.mycompany.myproject:some-artifact-id:jar:*:[1.0,2.0)"),
                        "com.mycompany.myproject:some-artifact-id:jar::1.1"),
                plain(
                        List.of("org.teiid:teiid:*:jdbc:*", "org.python:jython-standalone"),
                        "org.python:jython-standalone:jar::1.0",
                        "org.teiid:teiid:jar:jdbc:1.0"),
                plain(List.of("test-group:test-artifact:*:ERROR*"), "test-group:test-artifact:::"));

        private static UpstreamVectors plain(List<String> patterns, String... artifacts) {
            return new UpstreamVectors(
                    patterns,
                    false,
                    Stream.of(artifacts).map(spec -> Subject.of(spec, null)).toList());
        }

        private static UpstreamVectors trail(List<String> patterns, List<String> trail) {
            return new UpstreamVectors(patterns, true, List.of(Subject.of("group:artifact:jar::version", trail)));
        }
    }
}
