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
import org.openrewrite.java.search.UsesMethod;

/**
 * Marks the maven-filtering {@code filterResources} calls of Maven 4 API plugins with their migration to the core
 * {@code ResourceFilter} service.
 */
public final class UseCoreResourceFilter extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use the core ResourceFilter service";
    }

    @Override
    public String getDescription() {
        return "In plugins written against the Maven 4 API (a source that uses an `org.apache.maven.api` type), "
                + "marks each `MavenResourcesFiltering.filterResources(MavenResourcesExecution)` call with the "
                + "`ResourceFilterRequest` builder method that each `MavenResourcesExecution` setter called in the "
                + "same method maps to, the setters whose values go into the request callback, and the setters "
                + "without an equivalent. Maven 3 plugins are not marked: they cannot use Maven 4 services. "
                + "Search only: a `MavenResourcesExecution` carries model `Resource`s and a service handle the call "
                + "site does not have, so no call is rewritten.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesMethod<>(ResourceFilterCallVisitor.FILTER_RESOURCES), new ResourceFilterCallVisitor());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UseCoreResourceFilter;
    }

    @Override
    public int hashCode() {
        return UseCoreResourceFilter.class.hashCode();
    }
}
