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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A minimal MCP client over the server's stdin and stdout, written against the JSON-RPC wire format rather than
 * the SDK, so the test checks what any client sees. Every stdout line must be a JSON-RPC 2.0 message; other lines
 * are counted as protocol pollution.
 */
final class McpStdioClient implements AutoCloseable {

    private final Process process;
    private final OutputStream stdin;
    private final Map<Integer, BlockingQueue<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger pollution = new AtomicInteger();
    private final AtomicInteger messages = new AtomicInteger();

    McpStdioClient(List<String> command, java.io.File stderr) throws IOException {
        process = new ProcessBuilder(command).redirectError(stderr).start();
        stdin = process.getOutputStream();
        Thread reader = new Thread(this::readLoop, "mcp-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends a request and waits for its response, at most {@code seconds}. */
    JsonNode request(String method, JsonNode params, int seconds) throws IOException, InterruptedException {
        int id = send(method, params);
        return await(id, seconds);
    }

    /** Sends a request without waiting; pair with {@link #await}. */
    int send(String method, JsonNode params) throws IOException {
        int id = ids.incrementAndGet();
        pending.put(id, new LinkedBlockingQueue<>());
        ObjectNode message = CompactJson.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", params);
        write(message);
        return id;
    }

    JsonNode await(int id, int seconds) throws InterruptedException {
        JsonNode response = pending.get(id).poll(seconds, TimeUnit.SECONDS);
        if (response == null) {
            throw new IllegalStateException("no response to request " + id + " within " + seconds + " s");
        }
        return response;
    }

    void notify(String method) throws IOException {
        write(CompactJson.object().put("jsonrpc", "2.0").put("method", method));
    }

    /** Calls a tool and returns the parsed JSON of its text content, with "isError" added. */
    JsonNode tool(String name, JsonNode arguments, int seconds) throws IOException, InterruptedException {
        ObjectNode params = CompactJson.object().put("name", name);
        params.set("arguments", arguments);
        return toolResult(request("tools/call", params, seconds));
    }

    static JsonNode toolResult(JsonNode response) throws IOException {
        JsonNode result = response.path("result");
        ObjectNode body = (ObjectNode) CompactJson.parse(result.path("content").get(0).path("text").asText());
        return body.put("isError", result.path("isError").asBoolean());
    }

    int pollution() {
        return pollution.get();
    }

    int messages() {
        return messages.get();
    }

    /** Closes stdin and waits for the server to exit; returns its exit code, or -1 when it had to be killed. */
    int shutdown(int seconds) throws IOException, InterruptedException {
        stdin.close();
        if (process.waitFor(seconds, TimeUnit.SECONDS)) {
            return process.exitValue();
        }
        process.destroyForcibly();
        return -1;
    }

    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private synchronized void write(JsonNode message) throws IOException {
        stdin.write((CompactJson.text(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            in.lines().forEach(this::dispatch);
        } catch (IOException e) {
            pollution.incrementAndGet();
        }
    }

    private void dispatch(String line) {
        try {
            JsonNode message = CompactJson.parse(line);
            if (!"2.0".equals(message.path("jsonrpc").asText())) {
                pollution.incrementAndGet();
                return;
            }
            messages.incrementAndGet();
            if (message.has("id") && pending.containsKey(message.path("id").asInt())) {
                pending.get(message.path("id").asInt()).add(message);
            }
        } catch (IOException e) {
            pollution.incrementAndGet();
        }
    }
}
