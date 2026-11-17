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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.ArtifactMatcher;
import org.apache.maven.api.services.ArtifactMatcherFactory;
import org.apache.maven.api.services.ArtifactMatcherRequest;
import org.apache.maven.api.services.ArtifactMatcherResult;
import org.apache.maven.api.services.VersionParser;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link ArtifactMatcherFactory}, creating {@link ArtifactSelector} instances.
 * Version ranges in patterns are parsed by the Maven 4 {@link VersionParser}.
 *
 * @since 4.2.0
 */
@Named
@Singleton
public class DefaultArtifactMatcherFactory implements ArtifactMatcherFactory {

    private final VersionParser versionParser;

    @Inject
    public DefaultArtifactMatcherFactory(VersionParser versionParser) {
        this.versionParser = requireNonNull(versionParser, "versionParser");
    }

    @Nonnull
    @Override
    public ArtifactMatcher createArtifactMatcher(@Nonnull ArtifactMatcherRequest request) {
        ArtifactSelector selector = ArtifactSelector.of(request, versionParser);
        return selector.hasNoCriteria() ? ArtifactSelector.INCLUDES_ALL : selector;
    }

    @Nonnull
    @Override
    public <A extends Artifact> ArtifactMatcherResult<A> select(
            @Nonnull ArtifactMatcher matcher,
            @Nonnull Collection<A> artifacts,
            @Nonnull Function<? super A, ? extends List<? extends Artifact>> trails) {
        requireNonNull(artifacts, "artifacts");
        requireNonNull(trails, "trails");
        if (requireNonNull(matcher, "matcher") instanceof ArtifactSelector selector) {
            return selector.select(artifacts, trails);
        }
        Map<Boolean, List<A>> split = artifacts.stream()
                .collect(Collectors.partitioningBy(artifact -> matcher.matches(artifact, trails.apply(artifact))));
        return new DefaultArtifactMatcherResult<>(split.get(true), split.get(false), List.of());
    }

    @Nonnull
    @Override
    public ArtifactMatcher includesAll() {
        return ArtifactSelector.INCLUDES_ALL;
    }
}
