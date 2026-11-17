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
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Service;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;

/**
 * Service for creating {@link ArtifactMatcher} objects that select artifacts by coordinate patterns,
 * scope and coordinate values. It is the coordinate twin of {@link PathMatcherFactory}: plugins get
 * include/exclude selection of artifacts without depending on implementation classes.
 * <p>
 * The pattern syntax is the one of the {@code PatternIncludesArtifactFilter} and
 * {@code PatternExcludesArtifactFilter} classes of Maven Common Artifact Filters, used by the
 * assembly, dependency, shade and war plugins:
 * <ul>
 *   <li>{@code groupId:artifactId:type:classifier:version}, or fewer tokens:
 *       {@code G}, {@code G:A}, {@code G:A:T}, {@code G:A:T:V} (the fourth token matches the version or
 *       the classifier), and the partial forms {@code *:A}, {@code *:A:*}, {@code *:*:T} described in
 *       {@link ArtifactMatcherRequest#getIncludes()};</li>
 *   <li>{@code *} (any sequence) and {@code ?} (any character) wildcards inside a token, and an empty
 *       token standing for {@code *};</li>
 *   <li>a version range such as {@code [1.0,2.0)} as the version token of a five-token pattern;</li>
 *   <li>a leading {@code !} that negates the pattern: the first pattern that matches decides.</li>
 * </ul>
 * Scope selection follows the {@code ScopeFilter} of the same library, and the coordinate value lists
 * follow its {@code GroupIdFilter}, {@code ArtifactIdFilter}, {@code TypeFilter} and {@code ClassifierFilter}.
 *
 * @since 4.2.0
 * @see ArtifactMatcher
 * @see ArtifactMatcherRequest
 */
@Experimental
public interface ArtifactMatcherFactory extends Service {

    /**
     * Creates a matcher for all the criteria of the given request.
     * A request without any criterion gives {@link #includesAll()}.
     *
     * @param request the patterns, scopes and coordinate values to select by
     * @return a matcher that tells whether an artifact is selected
     * @throws NullPointerException if request is null
     * @throws IllegalArgumentException if a pattern, a version range or a scope is invalid, or if the
     *         request excludes the {@code test} scope (which would exclude every artifact)
     */
    @Nonnull
    ArtifactMatcher createArtifactMatcher(@Nonnull ArtifactMatcherRequest request);

    /**
     * Creates a matcher for include and exclude patterns. An artifact is selected when it matches
     * the includes (or when there are none) and does not match the excludes.
     *
     * @param includes the patterns of artifacts to include, or null/empty for including all artifacts
     * @param excludes the patterns of artifacts to exclude, or null/empty for no exclusion
     * @return a matcher for the patterns
     * @throws IllegalArgumentException if a pattern or a version range is invalid
     */
    @Nonnull
    default ArtifactMatcher createArtifactMatcher(Collection<String> includes, Collection<String> excludes) {
        return createArtifactMatcher(ArtifactMatcherRequest.builder()
                .includes(includes)
                .excludes(excludes)
                .build());
    }

    /**
     * Creates a matcher that only includes the artifacts matching the include patterns.
     *
     * @param includes the patterns of artifacts to include, or null/empty for including all artifacts
     * @return a matcher for the patterns
     * @throws IllegalArgumentException if a pattern or a version range is invalid
     */
    @Nonnull
    default ArtifactMatcher createIncludeOnlyMatcher(Collection<String> includes) {
        return createArtifactMatcher(includes, null);
    }

    /**
     * Creates a matcher that includes all artifacts except those matching the exclude patterns.
     *
     * @param excludes the patterns of artifacts to exclude, or null/empty for no exclusion
     * @return a matcher for the patterns
     * @throws IllegalArgumentException if a pattern or a version range is invalid
     */
    @Nonnull
    default ArtifactMatcher createExcludeOnlyMatcher(Collection<String> excludes) {
        return createArtifactMatcher(null, excludes);
    }

    /**
     * Splits artifacts into the selected and the rejected ones, and reports the patterns that
     * decided for no artifact. Artifacts keep their order in both lists.
     * <p>
     * The criteria are checked in this order, and the next one only when the previous ones accept the
     * artifact: scope, coordinate values, include patterns, exclude patterns. A pattern counts as used
     * when it was the first pattern of its list to match the artifact (or an element of its trail).
     * For a matcher that this factory did not create, no pattern is reported.
     *
     * @param <A> the type of the artifacts
     * @param matcher a matcher, normally created by this factory
     * @param artifacts the artifacts to split
     * @param trails gives the dependency trail of each artifact (see {@link ArtifactMatcher#matches(Artifact, List)})
     * @return the selected and rejected artifacts and the unused patterns
     * @throws NullPointerException if an argument is null
     */
    @Nonnull
    <A extends Artifact> ArtifactMatcherResult<A> select(
            @Nonnull ArtifactMatcher matcher,
            @Nonnull Collection<A> artifacts,
            @Nonnull Function<? super A, ? extends List<? extends Artifact>> trails);

    /**
     * Splits artifacts into the selected and the rejected ones without dependency trails.
     * This is equivalent to calling {@link #select(ArtifactMatcher, Collection, Function)}
     * with an empty trail for every artifact.
     *
     * @param <A> the type of the artifacts
     * @param matcher a matcher, normally created by this factory
     * @param artifacts the artifacts to split
     * @return the selected and rejected artifacts and the unused patterns
     * @throws NullPointerException if an argument is null
     */
    @Nonnull
    default <A extends Artifact> ArtifactMatcherResult<A> select(
            @Nonnull ArtifactMatcher matcher, @Nonnull Collection<A> artifacts) {
        return select(matcher, artifacts, artifact -> List.of());
    }

    /**
     * Returns the matcher that unconditionally returns {@code true} for all artifacts.
     * It is the matcher returned by the other methods of this interface when the request has no criterion.
     *
     * @return matcher that unconditionally returns {@code true} for all artifacts
     */
    @Nonnull
    ArtifactMatcher includesAll();

    /**
     * {@return whether the given matcher includes all artifacts}.
     * This method may conservatively return {@code false} in case of doubt.
     *
     * @param matcher the matcher to test
     */
    default boolean isIncludesAll(@Nonnull ArtifactMatcher matcher) {
        return Objects.requireNonNull(matcher) == includesAll();
    }
}
