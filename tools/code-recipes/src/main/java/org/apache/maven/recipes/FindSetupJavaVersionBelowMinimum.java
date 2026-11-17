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

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.ExecutionContext;
import org.openrewrite.FindSourceFiles;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;

/**
 * Lists the {@code actions/setup-java} Java versions below a minimum that {@link SetupJavaMinimumVersion} does not
 * raise, so that a dry run reports them. It is a separate search recipe because rewrite-maven-plugin's
 * {@code rewrite:run} writes search markers into the files, and in YAML a marker becomes part of the value.
 */
public final class FindSetupJavaVersionBelowMinimum extends Recipe {
    static final String ACTIONS = "**/action.yml";
    static final String ACTIONS_YAML = "**/action.yaml";

    @Option(
            displayName = "Minimum Java major version",
            description = "`java-version` values below this Java major version are marked. Defaults to 21.",
            example = "21",
            required = false)
    private final Integer minimumJavaMajorVersion;

    @JsonCreator
    public FindSetupJavaVersionBelowMinimum(
            @JsonProperty("minimumJavaMajorVersion") Integer minimumJavaMajorVersion) {
        this.minimumJavaMajorVersion = minimumJavaMajorVersion;
    }

    public Integer getMinimumJavaMajorVersion() {
        return minimumJavaMajorVersion;
    }

    @Override
    public String getDisplayName() {
        return "Find `actions/setup-java` `java-version` values left below a minimum";
    }

    @Override
    public String getDescription() {
        return "In `.github/workflows/*.yml` (and `*.yaml`) and composite actions (`action.yml`, `action.yaml`), "
                + "marks the `actions/setup-java` Java versions below the minimum that `SetupJavaMinimumVersion` "
                + "does not raise: `with` inputs and matrices written as nested flow mappings, multi-line "
                + "`java-version` values, composite action steps, and matrix `exclude` values that match no value "
                + "of their axis list. Search only: nothing is changed.";
    }

    @Override
    public Validated<Object> validate() {
        return super.validate().and(VersionOption.validate("minimumJavaMajorVersion", minimumJavaMajorVersion));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> files = Preconditions.or(
                new FindSourceFiles(SetupJavaMinimumVersion.WORKFLOWS).getVisitor(),
                new FindSourceFiles(SetupJavaMinimumVersion.WORKFLOWS_YAML).getVisitor(),
                new FindSourceFiles(ACTIONS).getVisitor(),
                new FindSourceFiles(ACTIONS_YAML).getVisitor());
        int minimum = VersionOption.orDefault(minimumJavaMajorVersion);
        return Preconditions.check(files, new UnraisedJavaVersionVisitor(minimum));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FindSetupJavaVersionBelowMinimum that
                && Objects.equals(minimumJavaMajorVersion, that.minimumJavaMajorVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), minimumJavaMajorVersion);
    }
}
