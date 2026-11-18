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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stdio MCP server (newline-delimited JSON-RPC 2.0) exposing {@link McpTools}: {@code initialize},
 * {@code tools/list}, {@code tools/call} and {@code ping}. It is the stand-in until {@code tools/maven-mcp-server}
 * hosts the same definitions.
 */
final class McpServer {
    static final String PROTOCOL = "2025-06-18";

    private final Path root;
    private final Path store;

    McpServer(Path root, Path store) {
        this.root = root;
        this.store = store;
    }

    void serve(BufferedReader in, PrintStream out) throws IOException {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.isBlank()) continue;
            String response = handle(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
        }
    }

    /** The response to one request line, or null for a notification. */
    String handle(String line) {
        Map<?, ?> request;
        try {
            request = (Map<?, ?>) Json.parse(line);
        } catch (IllegalArgumentException | ClassCastException malformed) {
            return error(null, -32700, "parse error");
        }
        Object id = request.get("id");
        if (id == null) return null;
        Object method = request.get("method");
        Object params = request.get("params");
        return respond(id, String.valueOf(method), params instanceof Map<?, ?> map ? map : Map.of());
    }

    private String respond(Object id, String method, Map<?, ?> params) {
        return switch (method) {
            case "initialize" -> result(id, Map.of("protocolVersion", PROTOCOL, "capabilities",
                    Map.of("tools", Map.of()), "serverInfo", Map.of("name", "maven-project-db", "version", "0.1.0")));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> list(id);
            case "tools/call" -> call(id, params);
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private String list(Object id) {
        try {
            return result(id, Map.of("tools", McpTools.tools()));
        } catch (IOException missing) {
            return error(id, -32603, missing.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String call(Object id, Map<?, ?> params) {
        Object arguments = params.get("arguments");
        Map<String, Object> args = arguments instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        try {
            String text = McpTools.call(String.valueOf(params.get("name")), args, root, store);
            return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false));
        } catch (IOException | RuntimeException failure) {
            return result(id, Map.of("content", List.of(Map.of("type", "text", "text", String.valueOf(
                    failure.getMessage()))), "isError", true));
        }
    }

    private static String result(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return Json.write(response);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", (long) code, "message", message));
        return Json.write(response);
    }
}
