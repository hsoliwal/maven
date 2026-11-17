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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The same database outside Maven:
 * <pre>
 * ingest &lt;root&gt; [--store DIR] [--persistent] [--build-log FILE]
 * query  &lt;root&gt; &lt;question&gt; [--store DIR] [--persistent] [--k N] [--json]
 * mcp    &lt;root&gt; [--store DIR] [--persistent]          (stdio MCP server)
 * tools                                              (prints the MCP tool definitions)
 * </pre>
 */
public final class ProjectDbCli {
    private ProjectDbCli() {}

    public static void main(String[] args) throws Exception {
        execute(args, System.out::println);
    }

    static void execute(String[] args, Consumer<String> out) throws Exception {
        if (args.length == 1 && "tools".equals(args[0])) {
            out.accept(McpTools.definitions());
        } else if (args.length >= 2) {
            run(args[0], Options.parse(args), out);
        } else {
            throw usage();
        }
    }

    private static void run(String command, Options options, Consumer<String> out) throws Exception {
        switch (command) {
            case "ingest" -> out.accept(McpTools.call("project_db_ingest", options.ingestArguments(), options.root(),
                    options.store()));
            case "query" -> query(options, out);
            case "mcp" -> new McpServer(options.root(), options.store()).serve(new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
            default -> throw usage();
        }
    }

    private static void query(Options options, Consumer<String> out) throws Exception {
        if (options.question().isEmpty()) throw usage();
        try (ProjectDb db = ProjectDb.open(options.store())) {
            Answer answer = db.query(options.question(), options.k());
            if (options.json()) out.accept(answer.json());
            else answer.lines().forEach(out);
        }
    }

    static IllegalArgumentException usage() {
        return new IllegalArgumentException("usage: tools | ingest <root> [--store DIR] [--persistent] [--build-log FILE]"
                + " | query <root> <question> [--store DIR] [--persistent] [--k N] [--json] | mcp <root> [--store DIR]");
    }

    /** Parsed options; unknown or duplicate options are refused. */
    private record Options(Path root, Path store, String buildLog, String question, int k, boolean json) {
        static Options parse(String[] args) {
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            List<String> rest = new ArrayList<>(List.of(args).subList(2, args.length));
            String question = "query".equals(args[0]) && !rest.isEmpty() && !rest.get(0).startsWith("--") ? rest.remove(0) : "";
            String store = value(rest, "--store");
            String log = value(rest, "--build-log");
            String k = value(rest, "--k");
            boolean persistent = rest.remove("--persistent");
            boolean json = rest.remove("--json");
            if (!rest.isEmpty()) throw usage();
            return new Options(root, StoreLocation.resolve(root, store, persistent), log, question,
                    k == null ? 5 : Integer.parseInt(k), json);
        }

        private static String value(List<String> rest, String option) {
            int at = rest.indexOf(option);
            if (at < 0) return null;
            if (at + 1 >= rest.size() || rest.lastIndexOf(option) != at) throw usage();
            String value = rest.get(at + 1);
            rest.subList(at, at + 2).clear();
            return value;
        }

        Map<String, Object> ingestArguments() {
            return buildLog == null ? Map.of() : Map.of("buildLog", buildLog);
        }
    }
}
