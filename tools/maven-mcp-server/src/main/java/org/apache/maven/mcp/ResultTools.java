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
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/** The tools that read a finished run: build_events (a page of the event stream) and explain_failure (the digest). */
final class ResultTools {

    private final ServerContext context;

    ResultTools(ServerContext context) {
        this.context = context;
    }

    /** build_events(run, types, project, offset, limit). */
    JsonNode buildEvents(ToolArgs args) throws IOException {
        Path dir = runDir(args);
        Set<String> types = args.strings("types").stream().map(ArgumentGuard::eventType).collect(Collectors.toSet());
        String project = args.string("project").map(ArgumentGuard::project).orElse("");
        int offset = args.number("offset", 0, 0, 10_000_000);
        int limit = args.number("limit", 50, 1, 500);
        return RunResults.events(dir, types, project, offset, limit);
    }

    /** explain_failure(run). */
    JsonNode explainFailure(ToolArgs args) throws IOException {
        return RunResults.failure(runDir(args));
    }

    private Path runDir(ToolArgs args) {
        String run = ArgumentGuard.run(args.string("run").orElse("latest"));
        return context.store().resolve(run).orElseThrow(() -> new IllegalArgumentException("no run " + run + " in "
                + context.store().runsDir() + ("latest".equals(run) ? "; call build or test first" : "")));
    }
}
