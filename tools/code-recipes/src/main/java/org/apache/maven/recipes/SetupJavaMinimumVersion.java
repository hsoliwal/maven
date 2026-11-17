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
 * Raises the {@code actions/setup-java} {@code java-version} values below a minimum in GitHub Actions workflows:
 * scalar values (plain, quoted, single-line blocks) and the matrix axis lists, {@code include} and {@code exclude}
 * objects those values refer to. It follows the approach of OpenRewrite's Apache-2.0
 * {@code SetupJavaUpgradeJavaVersion} (rewrite-github-actions 2.11.1), extended to matrices and non-scalar values;
 * see FOSS_REUSE_DECISION.tsv for why that recipe is not wrapped. The shapes it leaves (flow mappings, multi-line
 * values, composite actions) are listed by {@link FindSetupJavaVersionBelowMinimum}.
 */
public final class SetupJavaMinimumVersion extends Recipe {
    static final String WORKFLOWS = ".github/workflows/*.yml";
    static final String WORKFLOWS_YAML = ".github/workflows/*.yaml";

    @Option(
            displayName = "Minimum Java major version",
            description = "`java-version` values below this Java major version are raised to it. Defaults to 21.",
            example = "21",
            required = false)
    private final Integer minimumJavaMajorVersion;

    @JsonCreator
    public SetupJavaMinimumVersion(@JsonProperty("minimumJavaMajorVersion") Integer minimumJavaMajorVersion) {
        this.minimumJavaMajorVersion = minimumJavaMajorVersion;
    }

    public Integer getMinimumJavaMajorVersion() {
        return minimumJavaMajorVersion;
    }

    @Override
    public String getDisplayName() {
        return "Raise `actions/setup-java` `java-version` to a minimum";
    }

    @Override
    public String getDescription() {
        return "In `.github/workflows/*.yml` (and `*.yaml`), raises each `actions/setup-java` `java-version` below "
                + "the minimum Java major version to the minimum: scalar values such as `17`, `'17'`, `\"17\"` and "
                + "single-line `|` or `>` blocks keep their style, and for a `${{ matrix.<axis> }}` value the "
                + "`strategy.matrix` axis list and the axis values of its `include` objects (and of its `exclude` "
                + "objects, when the value is not merged into another) are raised, with resulting duplicates "
                + "dropped. Values at or above the minimum, expressions, names such as `lts`, aliases and multi-line "
                + "values are kept; `FindSetupJavaVersionBelowMinimum` lists those left below the minimum.";
    }

    @Override
    public Validated<Object> validate() {
        return super.validate().and(VersionOption.validate("minimumJavaMajorVersion", minimumJavaMajorVersion));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> workflows = Preconditions.or(
                new FindSourceFiles(WORKFLOWS).getVisitor(), new FindSourceFiles(WORKFLOWS_YAML).getVisitor());
        int minimum = VersionOption.orDefault(minimumJavaMajorVersion);
        return Preconditions.check(workflows, new SetupJavaVersionVisitor(minimum));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SetupJavaMinimumVersion that
                && Objects.equals(minimumJavaMajorVersion, that.minimumJavaMajorVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), minimumJavaMajorVersion);
    }
}
