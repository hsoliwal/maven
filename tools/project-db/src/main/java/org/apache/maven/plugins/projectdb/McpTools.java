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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP tool adapter of the project database, for {@code tools/maven-mcp-server} or any MCP host: the tool
 * definitions ({@code META-INF/project-db/mcp-tools.json}) and one call entry point that returns the tool result as
 * JSON text. A host that owns the transport plugs in with {@link #definitions()} and {@link #call}; without one,
 * {@link McpServer} serves the same tools over stdio.
 */
public final class McpTools {
    static final String RESOURCE = "META-INF/project-db/mcp-tools.json";

    private McpTools() {}

    /** The tool definitions document, exactly as shipped. */
    public static String definitions() throws IOException {
        try (InputStream in = McpTools.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The {@code tools} array, in MCP {@code tools/list} shape. */
    static List<?> tools() throws IOException {
        return (List<?>) ((Map<?, ?>) Json.parse(definitions())).get("tools");
    }

    /** Runs one tool against the project at {@code root} with its database at {@code store}; returns JSON text. */
    public static String call(String tool, Map<String, Object> arguments, Path root, Path store) throws IOException {
        return switch (tool) {
            case "project_db_query" -> query(arguments, store);
            case "project_db_ingest" -> ingest(arguments, root, store);
            default -> throw new IllegalArgumentException("unknown project database tool: " + tool);
        };
    }

    private static String query(Map<String, Object> arguments, Path store) throws IOException {
        Object question = arguments.get("question");
        if (!(question instanceof String text) || text.isBlank()) throw new IllegalArgumentException("question required");
        Object k = arguments.getOrDefault("k", 5L);
        int limit = k instanceof Number number ? Math.max(1, Math.min(50, number.intValue())) : 5;
        try (ProjectDb db = ProjectDb.open(store)) {
            return db.query(text, limit).json();
        }
    }

    private static String ingest(Map<String, Object> arguments, Path root, Path store) throws IOException {
        Object log = arguments.get("buildLog");
        Path buildLog = log instanceof String path && !path.isBlank() ? root.resolve(path) : null;
        try (ProjectDb db = ProjectDb.open(store)) {
            ProjectDb.Ingest ingest = db.ingest(root, Modules.discover(root), buildLog);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("build", ingest.build().id());
            result.put("status", ingest.build().status());
            result.put("sources", (long) ingest.build().sources());
            result.put("parsed", (long) ingest.build().parsed());
            result.put("reused", (long) ingest.build().reused());
            result.put("chunks", (long) ingest.chunks());
            return Json.write(result);
        }
    }
}
