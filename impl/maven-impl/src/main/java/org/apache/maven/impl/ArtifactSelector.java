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

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.services.ArtifactMatcher;
import org.apache.maven.api.services.ArtifactMatcherRequest;
import org.apache.maven.api.services.ArtifactMatcherRequest.Coordinate;
import org.apache.maven.api.services.ArtifactMatcherResult;
import org.apache.maven.api.services.VersionParser;

import static java.util.Objects.requireNonNull;

/**
 * The {@link ArtifactMatcher} of {@link DefaultArtifactMatcherFactory}: scope, then coordinate values, then
 * include patterns, then exclude patterns, each checked only when the previous ones accept the artifact.
 * <p>
 * The pattern decision is adapted from {@code PatternIncludesArtifactFilter.patternMatches} of Apache Maven
 * Common Artifact Filters (Apache-2.0, commit a11a231759800e38a5c46822f1cc04b867bc5670): the first pattern
 * that matches the artifact decides, and when none does, a transitive request tries the elements of a
 * dependency trail that has more than one element. The value lists follow its {@code AbstractArtifactFeatureFilter}.
 */
final class ArtifactSelector implements ArtifactMatcher {
    /** The matcher of a request without criteria. */
    static final ArtifactSelector INCLUDES_ALL =
            of(ArtifactMatcherRequest.builder().build(), null);

    private static final IntConsumer IGNORE = index -> {};

    private final ArtifactMatcherRequest request;

    private final Predicate<String> scope;

    private final Predicate<ArtifactView> values;

    private final Patterns includes;

    private final Patterns excludes;

    private ArtifactSelector(ArtifactMatcherRequest request, VersionParser parser) {
        this.request = request;
        this.scope = ArtifactScopeRule.of(request.getIncludeScope(), request.getExcludeScope());
        this.values = Stream.of(Coordinate.values())
                .map(coordinate -> valueRule(request, coordinate))
                .reduce(view -> true, Predicate::and);
        this.includes = Patterns.compile(request.getIncludes(), 0, parser);
        this.excludes = Patterns.compile(request.getExcludes(), includes.size(), parser);
    }

    /**
     * @throws IllegalArgumentException if a pattern, a version range or a scope of the request is invalid
     */
    static ArtifactSelector of(ArtifactMatcherRequest request, VersionParser parser) {
        return new ArtifactSelector(requireNonNull(request, "request"), parser);
    }

    /** {@return whether the request has no criterion} */
    boolean hasNoCriteria() {
        return includes.isEmpty()
                && excludes.isEmpty()
                && request.getIncludeScope() == null
                && request.getExcludeScope() == null
                && Stream.of(Coordinate.values())
                        .allMatch(c -> request.getIncludeValues(c).isEmpty()
                                && request.getExcludeValues(c).isEmpty());
    }

    @Override
    public boolean matches(Artifact artifact, List<? extends Artifact> trail) {
        return matches(ArtifactView.of(artifact), views(trail), IGNORE);
    }

    <A extends Artifact> ArtifactMatcherResult<A> select(
            Collection<A> artifacts, Function<? super A, ? extends List<? extends Artifact>> trails) {
        BitSet used = new BitSet();
        Map<Boolean, List<A>> split = artifacts.stream()
                .collect(Collectors.partitioningBy(
                        artifact -> matches(ArtifactView.of(artifact), views(trails.apply(artifact)), used::set)));
        return new DefaultArtifactMatcherResult<>(split.get(true), split.get(false), untriggered(used));
    }

    private boolean matches(ArtifactView view, List<ArtifactView> trail, IntConsumer used) {
        return scope.test(view.scope())
                && values.test(view)
                && (includes.isEmpty() || includes.decides(view, trail, used))
                && (excludes.isEmpty() || !excludes.decides(view, trail, used));
    }

    /** The include then exclude patterns whose index is not in {@code used}. */
    private List<String> untriggered(BitSet used) {
        return Stream.concat(includes.patterns().stream(), excludes.patterns().stream())
                .filter(pattern -> !used.get(pattern.index()))
                .map(IndexedPattern::toString)
                .toList();
    }

    /** The trail views, or none when the request is not transitive. */
    private List<ArtifactView> views(List<? extends Artifact> trail) {
        requireNonNull(trail, "trail");
        return request.isTransitive() ? trail.stream().map(ArtifactView::of).toList() : List.of();
    }

    private static Predicate<ArtifactView> valueRule(ArtifactMatcherRequest request, Coordinate coordinate) {
        ArtifactView.Field field = ArtifactView.Field.valueOf(coordinate.name());
        BiPredicate<String, String> same = sameValue(request, coordinate);
        List<String> in = request.getIncludeValues(coordinate);
        List<String> out = request.getExcludeValues(coordinate);
        return view -> {
            String value = view.get(field);
            return (in.isEmpty() || in.stream().anyMatch(wanted -> same.test(value, wanted)))
                    && out.stream().noneMatch(unwanted -> same.test(value, unwanted));
        };
    }

    private static BiPredicate<String, String> sameValue(ArtifactMatcherRequest request, Coordinate coordinate) {
        if (coordinate == Coordinate.GROUP_ID && request.isGroupIdPrefixMatch()) {
            return (value, wanted) -> value != null && value.startsWith(wanted);
        }
        return Objects::equals;
    }

    /**
     * A pattern with its index among the include then exclude patterns of the request.
     */
    private record IndexedPattern(int index, ArtifactCoordinatePattern pattern) {
        @Override
        public String toString() {
            return pattern.toString();
        }
    }

    /**
     * The distinct include or exclude patterns of a request, in request order.
     */
    private record Patterns(List<IndexedPattern> patterns) {
        static Patterns compile(List<String> sources, int offset, VersionParser parser) {
            List<String> distinct = sources.stream().distinct().toList();
            return new Patterns(IntStream.range(0, distinct.size())
                    .mapToObj(i ->
                            new IndexedPattern(offset + i, ArtifactCoordinatePattern.compile(distinct.get(i), parser)))
                    .toList());
        }

        boolean isEmpty() {
            return patterns.isEmpty();
        }

        int size() {
            return patterns.size();
        }

        /**
         * {@return whether the first pattern that matches the artifact, or else an element of its trail when
         * the trail has more than one element, is a positive one} The index of that pattern goes to {@code used}.
         */
        boolean decides(ArtifactView view, List<ArtifactView> trail, IntConsumer used) {
            Optional<IndexedPattern> first = firstMatch(view).or(() -> firstTrailMatch(trail));
            first.ifPresent(pattern -> used.accept(pattern.index()));
            return first.map(pattern -> !pattern.pattern().isNegative()).orElse(false);
        }

        private Optional<IndexedPattern> firstTrailMatch(List<ArtifactView> trail) {
            if (trail.size() <= 1) {
                return Optional.empty();
            }
            return trail.stream()
                    .map(this::firstMatch)
                    .flatMap(Optional::stream)
                    .findFirst();
        }

        private Optional<IndexedPattern> firstMatch(ArtifactView view) {
            return patterns.stream()
                    .filter(pattern -> pattern.pattern().matches(view))
                    .findFirst();
        }
    }

    @Override
    public String toString() {
        return "ArtifactMatcher[" + request + ']';
    }
}
