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
 * Lists the GitHub Actions workflows that set up Java but in which nothing can run a Java major version: every
 * {@code actions/setup-java} value differs from it and no matrix leg gives it. These are the scalar and reusable
 * workflow shapes that {@link AddSetupJavaMatrixVersion} does not change, left for review by hand. It is a separate
 * search recipe because rewrite-maven-plugin's {@code rewrite:run} writes search markers into the files.
 */
public final class FindWorkflowWithoutSetupJavaVersion extends Recipe {
    @Option(
            displayName = "Java major version",
            description = "Workflows in which no `actions/setup-java` step can run this Java major version are "
                    + "marked. Defaults to 21.",
            example = "21",
            required = false)
    private final Integer javaMajorVersion;

    @JsonCreator
    public FindWorkflowWithoutSetupJavaVersion(@JsonProperty("javaMajorVersion") Integer javaMajorVersion) {
        this.javaMajorVersion = javaMajorVersion;
    }

    public Integer getJavaMajorVersion() {
        return javaMajorVersion;
    }

    @Override
    public String getDisplayName() {
        return "Find workflows in which no `actions/setup-java` step runs a Java version";
    }

    @Override
    public String getDescription() {
        return "Marks the `.github/workflows/*.yml` (and `*.yaml`) files that set up Java, through "
                + "`actions/setup-java` steps or reusable workflow inputs (`jobs.<job>.uses`) whose name holds "
                + "`java` or `jdk` and whose value names a version, and in which none of them can run the Java major "
                + "version: every value differs from it and no matrix list or `include` object gives it. Search "
                + "only: nothing is changed.";
    }

    @Override
    public Validated<Object> validate() {
        return super.validate().and(VersionOption.validate("javaMajorVersion", javaMajorVersion));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> workflows = Preconditions.or(
                new FindSourceFiles(SetupJavaMinimumVersion.WORKFLOWS).getVisitor(),
                new FindSourceFiles(SetupJavaMinimumVersion.WORKFLOWS_YAML).getVisitor());
        int version = VersionOption.orDefault(javaMajorVersion);
        return Preconditions.check(workflows, new MissingJavaLegVisitor(version));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FindWorkflowWithoutSetupJavaVersion that
                && Objects.equals(javaMajorVersion, that.javaMajorVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), javaMajorVersion);
    }
}
