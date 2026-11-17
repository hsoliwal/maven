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
 * Adds a Java major version to the GitHub Actions matrix axis lists that {@code actions/setup-java} steps read through
 * {@code java-version: ${{ matrix.<axis> }}}, when a list lacks it, so that the workflow gets a job on that version.
 * No Apache-2.0 recipe does this (see FOSS_REUSE_DECISION.tsv); {@link FindWorkflowWithoutSetupJavaVersion} lists
 * the workflows it leaves without the version.
 */
public final class AddSetupJavaMatrixVersion extends Recipe {
    @Option(
            displayName = "Java major version",
            description = "The Java major version added to the matrix axis lists that lack it. Defaults to 21.",
            example = "21",
            required = false)
    private final Integer javaMajorVersion;

    @JsonCreator
    public AddSetupJavaMatrixVersion(@JsonProperty("javaMajorVersion") Integer javaMajorVersion) {
        this.javaMajorVersion = javaMajorVersion;
    }

    public Integer getJavaMajorVersion() {
        return javaMajorVersion;
    }

    @Override
    public String getDisplayName() {
        return "Add a Java version to the `actions/setup-java` matrix";
    }

    @Override
    public String getDescription() {
        return "In `.github/workflows/*.yml` (and `*.yaml`), adds the Java major version to each "
                + "`strategy.matrix` axis list that an `actions/setup-java` step reads through "
                + "`java-version: ${{ matrix.<axis> }}` and that lacks it: in ascending order, in the quoting style "
                + "of the entry before it, with every comment kept on its line. `include` and `exclude`, axes given "
                + "as expressions or aliases, anchored lists, lists with an entry that is not a version, and other "
                + "actions are left alone; `FindWorkflowWithoutSetupJavaVersion` lists the workflows left without "
                + "the version.";
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
        return Preconditions.check(workflows, new MatrixVersionInsertVisitor(version));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AddSetupJavaMatrixVersion that
                && Objects.equals(javaMajorVersion, that.javaMajorVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), javaMajorVersion);
    }
}
