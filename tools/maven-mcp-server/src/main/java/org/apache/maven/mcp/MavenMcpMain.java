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
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Starts the MCP server on stdio for one Maven project and stops when the client closes stdin. Stdout carries only
 * protocol messages: the original stream becomes a {@link LineOutput} for the transport and {@link System#out} is
 * pointed at stderr, so a stray print can never corrupt the protocol. Stdin goes through {@link ProtocolInput}, which
 * answers unreadable lines itself and keeps the session up. Logging goes through {@link System.Logger} to stderr.
 */
public final class MavenMcpMain {

    static final String SERVER_NAME = "maven";

    private static final System.Logger LOG = System.getLogger(MavenMcpMain.class.getName());

    private MavenMcpMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerSettings settings = settings(args);
        LineOutput protocolOut = new LineOutput(System.out);
        System.setOut(System.err);
        McpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        ProtocolInput input = ProtocolInput.start(System.in, protocolOut, mapper);
        Runtime.getRuntime().addShutdownHook(new Thread(MavenLauncher::stopAll, "stop-maven"));
        McpSyncServer server = start(settings, mapper, input.sdkInput(), protocolOut);
        LOG.log(Level.INFO, "Maven MCP server ready for " + settings.root() + ", runs in " + settings.runsDir());
        input.await();
        server.closeGracefully();
        MavenLauncher.stopAll();
        System.exit(0);
    }

    static McpSyncServer start(ServerSettings settings, McpJsonMapper mapper, InputStream in, OutputStream out)
            throws IOException {
        ServerContext context = new ServerContext(settings, new MavenLauncher(settings, System.getenv()),
                new RunStore(settings.runsDir(), settings.keepRuns()));
        return McpServer.sync(new StdioServerTransportProvider(mapper, in, out, InboundLines.MAX_LINE_CHARS))
                .serverInfo(SERVER_NAME, version())
                .instructions(instructions(settings))
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(ToolCatalog.specifications(context, mapper))
                .build();
    }

    private static ServerSettings settings(String[] args) throws IOException {
        try {
            return ServerSettings.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            LOG.log(Level.ERROR, e.getMessage() + System.lineSeparator() + ServerSettings.USAGE);
            System.exit(2);
            throw e;
        }
    }

    private static String instructions(ServerSettings settings) {
        return "Maven tools for the project at " + settings.root() + ". list_modules shows the reactor without"
                + " running Maven. build and test run Maven and answer with compact JSON and file paths; after a"
                + " failed run call explain_failure, and page detail with build_events. describe_goal explains a"
                + " plugin goal's parameters; effective_pom and dependency_tree show the resolved model.";
    }

    private static String version() {
        return Optional.ofNullable(MavenMcpMain.class.getPackage().getImplementationVersion()).orElse("dev");
    }
}
