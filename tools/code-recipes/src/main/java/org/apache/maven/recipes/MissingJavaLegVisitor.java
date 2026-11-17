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

import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Marks a workflow document that has Java sources ({@link WorkflowJavaLegs}) none of which can run the version. The
 * marker is on the document, so it is printed in front of its first key, after any leading comments. It adds search
 * markers only.
 */
final class MissingJavaLegVisitor extends YamlIsoVisitor<ExecutionContext> {
    private final int version;

    MissingJavaLegVisitor(int version) {
        this.version = version;
    }

    static String message(int version) {
        return "no actions/setup-java step or reusable workflow input runs Java " + version;
    }

    @Override
    public Yaml.Document visitDocument(Yaml.Document document, ExecutionContext ctx) {
        List<Boolean> legs = WorkflowJavaLegs.legs(document.getBlock(), version);
        return legs.isEmpty() || legs.contains(Boolean.TRUE)
                ? document
                : SearchResult.found(document, message(version));
    }
}
