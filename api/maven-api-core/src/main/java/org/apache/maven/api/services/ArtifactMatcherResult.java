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

import org.apache.maven.api.Artifact;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Immutable;
import org.apache.maven.api.annotations.Nonnull;

/**
 * The outcome of {@link ArtifactMatcherFactory#select(ArtifactMatcher, java.util.Collection)}: the selected
 * and the rejected artifacts, and the patterns that decided for none of them.
 *
 * @param <A> the type of the artifacts
 * @since 4.2.0
 */
@Experimental
@Immutable
public interface ArtifactMatcherResult<A extends Artifact> {

    /**
     * {@return the selected artifacts, in input order}.
     */
    @Nonnull
    List<A> getIncluded();

    /**
     * {@return the rejected artifacts, in input order}.
     */
    @Nonnull
    List<A> getExcluded();

    /**
     * Returns the include patterns, then the exclude patterns, that were never the first pattern of their
     * list to match an artifact or an element of its dependency trail. Duplicates of a pattern in the same
     * list count once. These are the patterns that the Maven 3 artifact filters report as "never triggered".
     *
     * @return the unused patterns in request order, never {@code null}
     */
    @Nonnull
    List<String> getUntriggeredPatterns();

    /**
     * {@return whether some pattern was never used}.
     */
    default boolean hasMissedCriteria() {
        return !getUntriggeredPatterns().isEmpty();
    }
}
