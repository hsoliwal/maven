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

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The scope criterion of an {@link org.apache.maven.api.services.ArtifactMatcherRequest}, tested on the
 * scope id of an artifact ({@code null} when it has none).
 * <p>
 * Adapted from {@code ScopeFilter} (package {@code collection}) of Apache Maven Common Artifact Filters
 * (Apache-2.0, commit a11a231759800e38a5c46822f1cc04b867bc5670) and the Maven 3 {@code ScopeArtifactFilter}
 * it uses: an include scope wins over an exclude scope; {@code compile}, {@code runtime} and {@code test}
 * are cumulative and let unknown scopes through; {@code provided} and {@code system} select that scope only;
 * excluding {@code test} is rejected.
 */
final class ArtifactScopeRule {
    private static final String TEST = "test";

    private static final Set<String> KNOWN = Set.of("compile", "runtime", TEST, "provided", "system");

    private static final Map<String, Set<String>> CUMULATIVE = Map.of(
            "compile", Set.of("compile", "provided", "system"), "runtime", Set.of("compile", "runtime"), TEST, KNOWN);

    private ArtifactScopeRule() {}

    /**
     * @throws IllegalArgumentException if a scope is not one of the five Maven 3 scopes, or the exclude scope is test
     */
    static Predicate<String> of(String includeScope, String excludeScope) {
        if (includeScope != null) {
            return include(known(includeScope, "includeScope"));
        }
        if (excludeScope == null) {
            return scope -> true;
        }
        if (TEST.equals(known(excludeScope, "excludeScope"))) {
            throw new IllegalArgumentException("Cannot exclude the test scope, this would exclude everything");
        }
        return include(excludeScope).negate();
    }

    private static Predicate<String> include(String scope) {
        Set<String> scopes = CUMULATIVE.get(scope);
        if (scopes == null) {
            return scope::equals;
        }
        return candidate -> candidate == null || !KNOWN.contains(candidate) || scopes.contains(candidate);
    }

    private static String known(String scope, String name) {
        if (!KNOWN.contains(scope)) {
            throw new IllegalArgumentException("Invalid scope in " + name + ": " + scope);
        }
        return scope;
    }
}
