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
package org.apache.maven.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A flat, depth-annotated view of the JSON tree that maven-dependency-plugin's tree goal writes with
 * {@code -DoutputType=json}: one row per node in depth-first order, capped at a node limit.
 */
final class DependencyTree {

    private DependencyTree() {}

    static ObjectNode summarize(Path file, int maxNodes) throws IOException {
        JsonNode root = CompactJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<JsonNode> nodes = new ArrayList<>();
        root.path("children").forEach(child -> flatten(child, 1, nodes));
        ObjectNode out = CompactJson.object()
                .put("file", file.toString())
                .put("root", id(root))
                .put("total", nodes.size());
        return CompactJson.capped(out, "nodes", nodes, maxNodes);
    }

    private static void flatten(JsonNode node, int depth, List<JsonNode> out) {
        ObjectNode row = CompactJson.object()
                .put("d", depth)
                .put("id", id(node))
                .put("scope", node.path("scope").asText());
        if ("true".equals(node.path("optional").asText())) {
            row.put("optional", true);
        }
        out.add(row);
        node.path("children").forEach(child -> flatten(child, depth + 1, out));
    }

    /** "groupId:artifactId[:type][:classifier]:version", type left out when it is jar. */
    private static String id(JsonNode node) {
        String type = node.path("type").asText();
        String classifier = node.path("classifier").asText();
        return node.path("groupId").asText() + ":" + node.path("artifactId").asText()
                + (type.isEmpty() || "jar".equals(type) ? "" : ":" + type)
                + (classifier.isEmpty() ? "" : ":" + classifier)
                + ":" + node.path("version").asText();
    }
}
