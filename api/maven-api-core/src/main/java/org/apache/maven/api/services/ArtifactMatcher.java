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

import java.util.List;
import java.util.function.Predicate;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.ThreadSafe;

/**
 * Tells whether an artifact is selected by the criteria of an {@link ArtifactMatcherRequest}.
 * Instances are created by {@link ArtifactMatcherFactory}; they hold no state between calls.
 * <p>
 * The coordinates compared are the group id, the artifact id, the type, the classifier, the base version
 * and the scope. For a {@link Dependency}, the type is {@link Dependency#type()} and the scope is
 * {@link Dependency#scope()}. For another {@link Artifact}, the type is its {@link Artifact#extension()}
 * and there is no scope. An empty classifier means no classifier.
 * <p>
 * As a {@link Predicate}, a matcher composes with {@code and}, {@code or} and {@code negate}.
 *
 * @since 4.2.0
 * @see ArtifactMatcherFactory
 */
@Experimental
@ThreadSafe
public interface ArtifactMatcher extends Predicate<Artifact> {

    /**
     * Tells whether the artifact is selected, looking at its dependency trail when the request is
     * {@linkplain ArtifactMatcherRequest#isTransitive() transitive}.
     * <p>
     * The trail lists the artifacts from the root project down to and including the artifact itself, as
     * the dependency trail of Maven 3 artifacts does. When no pattern matches the artifact and the trail
     * holds more than one element, the first trail element that a pattern matches decides.
     *
     * @param artifact the artifact to test
     * @param trail the dependency trail of the artifact, or an empty list
     * @return whether the artifact is selected
     * @throws NullPointerException if an argument is null
     */
    boolean matches(@Nonnull Artifact artifact, @Nonnull List<? extends Artifact> trail);

    /**
     * Tells whether the artifact is selected, without a dependency trail.
     *
     * @param artifact the artifact to test
     * @return whether the artifact is selected
     * @throws NullPointerException if artifact is null
     */
    default boolean matches(@Nonnull Artifact artifact) {
        return matches(artifact, List.of());
    }

    /**
     * Same as {@link #matches(Artifact)}.
     *
     * @param artifact the artifact to test
     * @return whether the artifact is selected
     */
    @Override
    default boolean test(@Nonnull Artifact artifact) {
        return matches(artifact);
    }
}
