/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.recipes;

import java.util.Map;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.AddPropertyVisitor;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Xml;

/**
 * Reuses OpenRewrite's own lossless AddPropertyVisitor, adding only missing
 * effective Maven properties. Guarding the resolved model (not just a POM's
 * local XML tags) prevents an opt-in on a child from overwriting an explicit
 * false or alternate backend inherited from its reactor parent.
 */
public final class M3LifecycleModuleDbDefaults extends Recipe {
    private static final String INCREMENTAL = "m3.lifecycle.incremental";
    private static final String BACKEND = "m3.lifecycle.backend";

    @Override
    public String getDisplayName() {
        return "Add only missing effective native M3DB lifecycle properties";
    }

    @Override
    public String getDescription() {
        return "Safely enable the existing Maven fork's module-local M3DB lifecycle "
                + "only where no local or inherited Maven property already selects a policy. "
                + "Uses the original OpenRewrite Maven AddPropertyVisitor and keeps a parent's opt-out authoritative.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Xml.Document result = super.visitDocument(document, ctx);
                Map<String, String> effective = getResolutionResult().getPom().getProperties();

                // A declaration with an empty value is still an explicit user
                // decision; do not substitute a default or erase it.
                boolean definedIncremental = effective.containsKey(INCREMENTAL)
                        || result.getRoot().getChild("properties")
                                .flatMap(properties -> properties.getChild(INCREMENTAL)).isPresent();
                boolean definedBackend = effective.containsKey(BACKEND)
                        || result.getRoot().getChild("properties")
                                .flatMap(properties -> properties.getChild(BACKEND)).isPresent();

                if (!definedIncremental) {
                    result = (Xml.Document) new AddPropertyVisitor(INCREMENTAL, "true", true)
                            .visitNonNull(result, ctx);
                }
                if (!definedBackend) {
                    result = (Xml.Document) new AddPropertyVisitor(BACKEND, "embedded", true)
                            .visitNonNull(result, ctx);
                }
                return result;
            }
        };
    }
}
