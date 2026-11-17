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

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;

/**
 * Marks the artifact filters of Maven Common Artifact Filters that Maven 4 plugins can replace with the core
 * {@code ArtifactMatcherFactory}, with the request that selects the same artifacts.
 */
public final class UseArtifactMatcherFactory extends Recipe {
    /** Source files that use a type of the Maven 4 API: only Maven 4 plugins can call core services. */
    static final String MAVEN_4_API = "org.apache.maven.api..*";

    @Override
    public String getDisplayName() {
        return "Use ArtifactMatcherFactory in Maven 4 plugins";
    }

    @Override
    public String getDescription() {
        return "In source files that use the Maven 4 API, marks each `new PatternIncludesArtifactFilter`, "
                + "`PatternExcludesArtifactFilter`, and `ScopeFilter`, `GroupIdFilter`, `ArtifactIdFilter`, "
                + "`TypeFilter` or `ClassifierFilter` of the `collection` package (Maven Common Artifact Filters), "
                + "and the `GroupIdFilter` of the dependency plugin, with the `ArtifactMatcherRequest` that selects "
                + "the same artifacts through the core `ArtifactMatcherFactory`. Search only: the filters take "
                + "Maven 3 artifacts and the matcher takes Maven 4 ones, so the call sites are changed by hand.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(MAVEN_4_API, false), new ArtifactFilterMarkers());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UseArtifactMatcherFactory;
    }

    @Override
    public int hashCode() {
        return UseArtifactMatcherFactory.class.hashCode();
    }
}
