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

import java.util.function.Function;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.Type;

import static java.util.Objects.requireNonNull;

/**
 * The coordinates of an artifact that {@link ArtifactSelector} compares, as text.
 * The classifier is {@code null} when the artifact has none, as with Maven 3 {@code Artifact.hasClassifier()}.
 *
 * @param groupId the group id
 * @param artifactId the artifact id
 * @param type the type of a dependency, or the extension of another artifact
 * @param classifier the classifier, or {@code null}
 * @param baseVersion the base version, or {@code null}
 * @param scope the scope of a dependency, or {@code null}
 */
record ArtifactView(
        String groupId, String artifactId, String type, String classifier, String baseVersion, String scope) {

    /**
     * The coordinates a pattern token can be compared with.
     */
    enum Field {
        GROUP_ID,
        ARTIFACT_ID,
        TYPE,
        CLASSIFIER,
        BASE_VERSION
    }

    static ArtifactView of(Artifact artifact) {
        requireNonNull(artifact, "artifact");
        Dependency dependency = artifact instanceof Dependency d ? d : null;
        String classifier = artifact.classifier();
        return new ArtifactView(
                artifact.groupId(),
                artifact.artifactId(),
                dependency != null ? text(dependency.type(), Type::id) : artifact.extension(),
                classifier != null && !classifier.isEmpty() ? classifier : null,
                text(artifact.baseVersion(), Object::toString),
                dependency != null ? text(dependency.scope(), DependencyScope::id) : null);
    }

    String get(Field field) {
        return switch (field) {
            case GROUP_ID -> groupId;
            case ARTIFACT_ID -> artifactId;
            case TYPE -> type;
            case CLASSIFIER -> classifier;
            case BASE_VERSION -> baseVersion;
        };
    }

    private static <T> String text(T value, Function<T, String> toText) {
        return value != null ? toText.apply(value) : null;
    }
}
