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
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;

/** Lists the POM Java levels above a maximum, the builds that a JDK of that version cannot compile. */
public final class FindJavaLevelAboveMaximum extends Recipe {
    @Option(
            displayName = "Maximum Java major version",
            description = "Java levels above this major version are marked. Defaults to 21.",
            example = "21",
            required = false)
    private final Integer maximumJavaMajorVersion;

    @JsonCreator
    public FindJavaLevelAboveMaximum(@JsonProperty("maximumJavaMajorVersion") Integer maximumJavaMajorVersion) {
        this.maximumJavaMajorVersion = maximumJavaMajorVersion;
    }

    public Integer getMaximumJavaMajorVersion() {
        return maximumJavaMajorVersion;
    }

    @Override
    public String getDisplayName() {
        return "Find POM Java levels above a maximum";
    }

    @Override
    public String getDescription() {
        return "Marks `maven.compiler.release`, `source` and `target` properties (and their `test` forms), the "
                + "same `maven-compiler-plugin` configuration parameters and the `--release`, `--source` and "
                + "`--target` levels of its `compilerArgs` whose Java level is above the maximum Java major version, "
                + "after resolving property references against the POM model (inside a profile, with that "
                + "profile's properties first). Search only: nothing is changed.";
    }

    @Override
    public Validated<Object> validate() {
        return super.validate().and(VersionOption.validate("maximumJavaMajorVersion", maximumJavaMajorVersion));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new CompilerLevelVisitor(VersionOption.orDefault(maximumJavaMajorVersion));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FindJavaLevelAboveMaximum that
                && Objects.equals(maximumJavaMajorVersion, that.maximumJavaMajorVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), maximumJavaMajorVersion);
    }
}
