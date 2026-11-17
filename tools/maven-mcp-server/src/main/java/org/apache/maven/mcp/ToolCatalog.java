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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * The tool list: names, descriptions and input schemas come from {@code tools.json} next to this class, and each
 * name is bound to one handler. A handler's refusal or failure becomes a tool error with a one-line message; the
 * protocol session stays up.
 */
final class ToolCatalog {

    /** One tool's work. */
    @FunctionalInterface
    interface Handler {
        JsonNode call(ToolArgs args) throws IOException;
    }

    static final String RESOURCE = "tools.json";

    private static final System.Logger LOG = System.getLogger(ToolCatalog.class.getName());

    /** Runs Maven and writes a run directory. */
    private static final ToolAnnotations RUN = new ToolAnnotations(null, false, false, false, true, null);
    /** Runs Maven to compute a view; may download plugins. */
    private static final ToolAnnotations QUERY = new ToolAnnotations(null, true, false, true, true, null);
    /** Reads files only. */
    private static final ToolAnnotations READ = new ToolAnnotations(null, true, false, true, false, null);

    private ToolCatalog() {}

    static List<SyncToolSpecification> specifications(ServerContext context, McpJsonMapper mapper) throws IOException {
        Map<String, Handler> handlers = handlers(context);
        List<SyncToolSpecification> specifications = new ArrayList<>();
        definitions().forEach(definition -> specifications.add(specification(definition, handlers, mapper)));
        Set<String> defined = specifications.stream().map(spec -> spec.tool().name()).collect(Collectors.toSet());
        if (!defined.equals(handlers.keySet())) {
            throw new IllegalStateException(RESOURCE + " defines " + new TreeSet<>(defined) + " but handlers are "
                    + new TreeSet<>(handlers.keySet()));
        }
        return specifications;
    }

    static Map<String, Handler> handlers(ServerContext context) {
        BuildTools build = new BuildTools(context);
        ProjectTools project = new ProjectTools(context);
        ResultTools results = new ResultTools(context);
        return Map.of(
                "build", build::build,
                "test", build::test,
                "effective_pom", project::effectivePom,
                "dependency_tree", project::dependencyTree,
                "describe_goal", project::describeGoal,
                "list_modules", project::listModules,
                "explain_failure", results::explainFailure,
                "build_events", results::buildEvents);
    }

    /** Calls a handler and turns its answer, refusal or failure into a tool result. */
    static CallToolResult call(String name, Handler handler, Map<String, Object> arguments) {
        try {
            return CompactJson.result(handler.call(new ToolArgs(arguments)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return CompactJson.error(e.getMessage());
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.WARNING, name + " failed: " + e.getMessage());
            return CompactJson.error("I/O error: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, name + " failed", e);
            return CompactJson.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonNode definitions() throws IOException {
        try (InputStream in = Optional.ofNullable(ToolCatalog.class.getResourceAsStream(RESOURCE))
                .orElseThrow(() -> new IOException(RESOURCE + " is missing from the class path"))) {
            return CompactJson.MAPPER.readTree(in);
        }
    }

    private static SyncToolSpecification specification(
            JsonNode definition, Map<String, Handler> handlers, McpJsonMapper mapper) {
        String name = definition.path("name").asText();
        Handler handler = Optional.ofNullable(handlers.get(name))
                .orElseThrow(() -> new IllegalStateException("no handler for tool " + name));
        Tool tool = Tool.builder(name, mapper, CompactJson.text(definition.path("inputSchema")))
                .description(definition.path("description").asText())
                .annotations(hints(definition.path("hints").asText()))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> call(name, handler, request.arguments()))
                .build();
    }

    private static ToolAnnotations hints(String hints) {
        return "run".equals(hints) ? RUN : "query".equals(hints) ? QUERY : READ;
    }
}
