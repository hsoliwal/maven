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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Reads stdin line by line on its own thread and hands the SDK, through a pipe, only lines it can read as JSON-RPC
 * messages: the SDK's reader stops for good at the first line it cannot read. Every other line is answered here: a
 * line that is not JSON with error -32700 and id null, JSON that is not a message or a line past
 * {@link InboundLines#MAX_LINE_CHARS} with -32600 (and its id when it has one). The end of stdin, a stdin error, or
 * an SDK reader that went away end the input, and {@link #await()} returns so the server stops.
 */
final class ProtocolInput {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;

    private static final System.Logger LOG = System.getLogger(ProtocolInput.class.getName());
    private static final int PIPE_BYTES = 1 << 20;

    private final InboundLines lines;
    private final PipedOutputStream toSdk = new PipedOutputStream();
    private final PipedInputStream sdkInput;
    private final LineOutput out;
    private final McpJsonMapper mapper;
    private final CountDownLatch ended = new CountDownLatch(1);

    private ProtocolInput(InputStream stdin, LineOutput out, McpJsonMapper mapper) throws IOException {
        this.lines = new InboundLines(new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)),
                InboundLines.MAX_LINE_CHARS);
        this.sdkInput = new PipedInputStream(toSdk, PIPE_BYTES);
        this.out = out;
        this.mapper = mapper;
    }

    /** Starts reading {@code stdin} on a daemon thread; give {@link #sdkInput()} to the SDK's transport. */
    static ProtocolInput start(InputStream stdin, LineOutput out, McpJsonMapper mapper) throws IOException {
        ProtocolInput input = new ProtocolInput(stdin, out, mapper);
        Thread reader = new Thread(input::pump, "stdin-lines");
        reader.setDaemon(true);
        reader.start();
        return input;
    }

    InputStream sdkInput() {
        return sdkInput;
    }

    /** Blocks until the input has ended. */
    void await() throws InterruptedException {
        ended.await();
    }

    private void pump() {
        try {
            Stream.generate(lines::next).takeWhile(Optional::isPresent).map(Optional::get).forEach(this::handle);
            LOG.log(Level.INFO, "stdin closed");
        } catch (UncheckedIOException stopped) {
            LOG.log(Level.INFO, "protocol input ended: " + stopped.getCause().getMessage());
        } finally {
            closeQuietly();
            ended.countDown();
        }
    }

    private void handle(InboundLines.Line line) {
        if (line.text().isBlank()) {
            return;
        }
        Optional<ObjectNode> error = line.tooLong() ? Optional.of(error(null, INVALID_REQUEST,
                "Invalid Request: longer than " + InboundLines.MAX_LINE_CHARS + " characters")) : check(line.text());
        try {
            if (error.isPresent()) {
                out.writeLine(CompactJson.text(error.get()));
            } else {
                toSdk.write((line.text() + "\n").getBytes(StandardCharsets.UTF_8));
                toSdk.flush();
            }
        } catch (IOException gone) {
            throw new UncheckedIOException(gone);
        }
    }

    /** Empty when the SDK can read {@code text} as a message, else the error response to send. */
    Optional<ObjectNode> check(String text) {
        JsonNode json;
        try {
            json = CompactJson.parse(text);
        } catch (IOException notJson) {
            return Optional.of(error(null, PARSE_ERROR, "Parse error: the line is not JSON"));
        }
        try {
            McpSchema.deserializeJsonRpcMessage(mapper, text);
            return Optional.empty();
        } catch (IOException | RuntimeException notMessage) {
            JsonNode id = json.path("id");
            return Optional.of(error(id.isTextual() || id.isNumber() ? id : null, INVALID_REQUEST,
                    "Invalid Request: not a JSON-RPC 2.0 message"));
        }
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = CompactJson.object().put("jsonrpc", "2.0");
        response.set("id", id == null ? CompactJson.MAPPER.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private void closeQuietly() {
        try {
            toSdk.close();
        } catch (IOException ignored) {
            LOG.log(Level.DEBUG, "pipe to the SDK already closed");
        }
    }
}
