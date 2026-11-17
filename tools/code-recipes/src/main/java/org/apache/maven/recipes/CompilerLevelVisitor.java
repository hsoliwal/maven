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

import java.util.Optional;
import java.util.OptionalInt;

import org.openrewrite.ExecutionContext;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Xml;

/**
 * Marks POM Java level tags (properties, compiler plugin parameters, {@code <compilerArgs>} levels) whose level,
 * after resolving {@code ${...}} property references as the tag sees them, is above a maximum. It adds search
 * markers only; the POM text is not changed.
 */
final class CompilerLevelVisitor extends MavenIsoVisitor<ExecutionContext> {
    private final int maximum;

    CompilerLevelVisitor(int maximum) {
        this.maximum = maximum;
    }

    @Override
    public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
        Xml.Tag visited = super.visitTag(tag, ctx);
        OptionalInt level = levelText(visited).map(this::resolve).map(JavaVersions::major).orElse(OptionalInt.empty());
        return level.isPresent() && level.getAsInt() > maximum
                ? SearchResult.found(visited, "Java " + level.getAsInt() + " is above " + maximum)
                : visited;
    }

    private Optional<String> levelText(Xml.Tag tag) {
        if (CompilerLevelTags.isLevelTag(getCursor())) {
            return tag.getValue();
        }
        return CompilerLevelTags.isCompilerArg(getCursor()) ? CompilerArgs.level(getCursor()) : Optional.empty();
    }

    private String resolve(String value) {
        return PomPlaceholders.resolve(value.trim(), getCursor(), getResolutionResult().getPom());
    }
}
