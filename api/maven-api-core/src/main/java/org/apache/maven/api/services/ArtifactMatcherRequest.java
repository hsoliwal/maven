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
package org.apache.maven.api.services;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Immutable;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.NotThreadSafe;
import org.apache.maven.api.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * The criteria of an {@link ArtifactMatcher}: include and exclude coordinate patterns, a scope, and
 * lists of coordinate values. An artifact is selected when it passes every criterion that is set.
 * A request without any criterion selects every artifact.
 *
 * @since 4.2.0
 * @see ArtifactMatcherFactory#createArtifactMatcher(ArtifactMatcherRequest)
 */
@Experimental
@Immutable
public interface ArtifactMatcherRequest {

    /**
     * The coordinates that {@linkplain #getIncludeValues(Coordinate) value lists} compare.
     */
    enum Coordinate {
        /** The group id; compared exactly, or by prefix when {@link #isGroupIdPrefixMatch()} is set. */
        GROUP_ID,
        /** The artifact id, compared exactly. */
        ARTIFACT_ID,
        /** The type (the extension of an artifact that is not a dependency), compared exactly. */
        TYPE,
        /** The classifier, compared exactly; an artifact without classifier matches no value. */
        CLASSIFIER
    }

    /**
     * Returns the include patterns. When the list is not empty, an artifact is selected only if the first
     * pattern that matches it is not negated (does not start with {@code !}). The tokens of a pattern are
     * separated by {@code :}, an empty token stands for {@code *}, and the number of tokens decides what
     * is compared:
     * <ul>
     *   <li>{@code G:A:T:C:V}: group id, artifact id, type, classifier and version; the version token may
     *       be a version range such as {@code [1.0,2.0)};</li>
     *   <li>{@code G:A:T:X}: group id, artifact id, type, and X against the version or the classifier;</li>
     *   <li>{@code G:A:T}, {@code G:A:*}, {@code G:A} and {@code G}: the given leading coordinates;</li>
     *   <li>{@code *:X:*}: X against the artifact id or the type; {@code *:*:X}: X against the type or the
     *       classifier; {@code *:X:Y}: X against the group id or the artifact id, and Y against the type or
     *       the classifier; {@code X:*:*}: X against the group id or the artifact id; {@code X:*:Y}: X
     *       against the group id, and Y against the type or the classifier;</li>
     *   <li>{@code *:X}: X against the group id, the artifact id, the type or the version;
     *       {@code X:*}: X against the group id;</li>
     *   <li>{@code *}, {@code *:*}, {@code *:*:*} and the empty pattern: every artifact.</li>
     * </ul>
     * Inside a token, {@code *} matches any sequence of characters and {@code ?} any one character.
     *
     * @return the include patterns in request order, never {@code null}
     */
    @Nonnull
    List<String> getIncludes();

    /**
     * Returns the exclude patterns, with the syntax of {@link #getIncludes()}. An artifact is rejected
     * when the first pattern that matches it is not negated.
     *
     * @return the exclude patterns in request order, never {@code null}
     */
    @Nonnull
    List<String> getExcludes();

    /**
     * Returns whether patterns are also tried on the dependency trail of an artifact that no pattern
     * matches directly.
     *
     * @return whether the dependency trail is looked at
     * @see ArtifactMatcher#matches(org.apache.maven.api.Artifact, List)
     */
    boolean isTransitive();

    /**
     * Returns the scope to include: {@code compile} (compile, provided and system), {@code runtime}
     * (compile and runtime), {@code test} (the five Maven 3 scopes), {@code provided} or {@code system}
     * (that scope only). With {@code compile}, {@code runtime} and {@code test}, artifacts of other
     * scopes and artifacts without scope are included too. When set, {@link #getExcludeScope()} is ignored.
     *
     * @return the scope to include, or {@code null} for no scope criterion
     */
    @Nullable
    String getIncludeScope();

    /**
     * Returns the scope to exclude: {@code compile}, {@code runtime} (the scopes they include in
     * {@link #getIncludeScope()}, plus artifacts of other scopes or without scope), {@code provided}
     * or {@code system} (that scope only). Excluding {@code test} is an error.
     *
     * @return the scope to exclude, or {@code null} for no scope criterion
     */
    @Nullable
    String getExcludeScope();

    /**
     * Returns the values to include for a coordinate: when the list is not empty, an artifact is selected
     * only if its coordinate equals one of them.
     *
     * @param coordinate the coordinate
     * @return the values, never {@code null}
     */
    @Nonnull
    List<String> getIncludeValues(@Nonnull Coordinate coordinate);

    /**
     * Returns the values to exclude for a coordinate: an artifact whose coordinate equals one of them
     * is rejected.
     *
     * @param coordinate the coordinate
     * @return the values, never {@code null}
     */
    @Nonnull
    List<String> getExcludeValues(@Nonnull Coordinate coordinate);

    /**
     * Returns whether group id values match every group id that starts with them, so that
     * {@code org.example} also matches {@code org.example.foo}. This is the default of the Maven 3
     * {@code GroupIdFilter}; the default here is {@code false} (exact match).
     *
     * @return whether group id values are prefixes
     */
    boolean isGroupIdPrefixMatch();

    /**
     * {@return a new builder}.
     */
    @Nonnull
    static ArtifactMatcherRequestBuilder builder() {
        return new ArtifactMatcherRequestBuilder();
    }

    /**
     * Builder of {@link ArtifactMatcherRequest}. A {@code null} collection means an empty one.
     */
    @NotThreadSafe
    class ArtifactMatcherRequestBuilder {
        private List<String> includes = List.of();
        private List<String> excludes = List.of();
        private boolean transitive;
        private String includeScope;
        private String excludeScope;
        private final Map<Coordinate, List<String>> includeValues = new EnumMap<>(Coordinate.class);
        private final Map<Coordinate, List<String>> excludeValues = new EnumMap<>(Coordinate.class);
        private boolean groupIdPrefixMatch;

        ArtifactMatcherRequestBuilder() {}

        /**
         * @param includes the include patterns, or {@code null}
         * @return this builder
         * @throws NullPointerException if an element is null
         */
        public ArtifactMatcherRequestBuilder includes(Collection<String> includes) {
            this.includes = copy(includes);
            return this;
        }

        /**
         * @param excludes the exclude patterns, or {@code null}
         * @return this builder
         * @throws NullPointerException if an element is null
         */
        public ArtifactMatcherRequestBuilder excludes(Collection<String> excludes) {
            this.excludes = copy(excludes);
            return this;
        }

        /**
         * @param transitive whether patterns are also tried on dependency trails
         * @return this builder
         */
        public ArtifactMatcherRequestBuilder transitive(boolean transitive) {
            this.transitive = transitive;
            return this;
        }

        /**
         * @param includeScope the scope to include, or {@code null} or empty for none
         * @return this builder
         */
        public ArtifactMatcherRequestBuilder includeScope(String includeScope) {
            this.includeScope = emptyToNull(includeScope);
            return this;
        }

        /**
         * @param excludeScope the scope to exclude, or {@code null} or empty for none
         * @return this builder
         */
        public ArtifactMatcherRequestBuilder excludeScope(String excludeScope) {
            this.excludeScope = emptyToNull(excludeScope);
            return this;
        }

        /**
         * @param coordinate the coordinate
         * @param values the values to include, or {@code null}
         * @return this builder
         * @throws NullPointerException if coordinate or an element is null
         */
        public ArtifactMatcherRequestBuilder includeValues(Coordinate coordinate, Collection<String> values) {
            includeValues.put(requireNonNull(coordinate, "coordinate"), copy(values));
            return this;
        }

        /**
         * @param coordinate the coordinate
         * @param values the values to exclude, or {@code null}
         * @return this builder
         * @throws NullPointerException if coordinate or an element is null
         */
        public ArtifactMatcherRequestBuilder excludeValues(Coordinate coordinate, Collection<String> values) {
            excludeValues.put(requireNonNull(coordinate, "coordinate"), copy(values));
            return this;
        }

        /**
         * @param groupIdPrefixMatch whether group id values match by prefix
         * @return this builder
         */
        public ArtifactMatcherRequestBuilder groupIdPrefixMatch(boolean groupIdPrefixMatch) {
            this.groupIdPrefixMatch = groupIdPrefixMatch;
            return this;
        }

        /**
         * {@return the request}.
         */
        public ArtifactMatcherRequest build() {
            return new DefaultArtifactMatcherRequest(this);
        }

        private static List<String> copy(Collection<String> values) {
            return values != null ? List.copyOf(values) : List.of();
        }

        private static String emptyToNull(String value) {
            return value != null && !value.isEmpty() ? value : null;
        }

        private static Map<Coordinate, List<String>> nonEmpty(Map<Coordinate, List<String>> values) {
            Map<Coordinate, List<String>> result = new EnumMap<>(Coordinate.class);
            values.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return Collections.unmodifiableMap(result);
        }

        private static final class DefaultArtifactMatcherRequest implements ArtifactMatcherRequest {
            private final List<String> includes;
            private final List<String> excludes;
            private final boolean transitive;
            private final String includeScope;
            private final String excludeScope;
            private final Map<Coordinate, List<String>> includeValues;
            private final Map<Coordinate, List<String>> excludeValues;
            private final boolean groupIdPrefixMatch;

            DefaultArtifactMatcherRequest(ArtifactMatcherRequestBuilder builder) {
                this.includes = builder.includes;
                this.excludes = builder.excludes;
                this.transitive = builder.transitive;
                this.includeScope = builder.includeScope;
                this.excludeScope = builder.excludeScope;
                this.includeValues = nonEmpty(builder.includeValues);
                this.excludeValues = nonEmpty(builder.excludeValues);
                this.groupIdPrefixMatch = builder.groupIdPrefixMatch;
            }

            @Override
            public List<String> getIncludes() {
                return includes;
            }

            @Override
            public List<String> getExcludes() {
                return excludes;
            }

            @Override
            public boolean isTransitive() {
                return transitive;
            }

            @Override
            public String getIncludeScope() {
                return includeScope;
            }

            @Override
            public String getExcludeScope() {
                return excludeScope;
            }

            @Override
            public List<String> getIncludeValues(Coordinate coordinate) {
                return includeValues.getOrDefault(requireNonNull(coordinate, "coordinate"), List.of());
            }

            @Override
            public List<String> getExcludeValues(Coordinate coordinate) {
                return excludeValues.getOrDefault(requireNonNull(coordinate, "coordinate"), List.of());
            }

            @Override
            public boolean isGroupIdPrefixMatch() {
                return groupIdPrefixMatch;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof DefaultArtifactMatcherRequest that
                        && transitive == that.transitive
                        && groupIdPrefixMatch == that.groupIdPrefixMatch
                        && includes.equals(that.includes)
                        && excludes.equals(that.excludes)
                        && Objects.equals(includeScope, that.includeScope)
                        && Objects.equals(excludeScope, that.excludeScope)
                        && includeValues.equals(that.includeValues)
                        && excludeValues.equals(that.excludeValues);
            }

            @Override
            public int hashCode() {
                return Objects.hash(
                        includes,
                        excludes,
                        transitive,
                        includeScope,
                        excludeScope,
                        includeValues,
                        excludeValues,
                        groupIdPrefixMatch);
            }

            @Override
            public String toString() {
                return "ArtifactMatcherRequest[includes=" + includes + ", excludes=" + excludes
                        + ", transitive=" + transitive + ", includeScope=" + includeScope
                        + ", excludeScope=" + excludeScope + ", includeValues=" + includeValues
                        + ", excludeValues=" + excludeValues + ", groupIdPrefixMatch=" + groupIdPrefixMatch
                        + ']';
            }
        }
    }
}
