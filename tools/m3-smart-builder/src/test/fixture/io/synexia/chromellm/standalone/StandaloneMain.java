package io.synexia.chromellm.standalone;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Protocol fixture: responds with invented text, never runs real inference. */
public final class StandaloneMain {
    public static void main(String[] args) throws Exception {
        var lines = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!lines.readLine().contains("initialize")) throw new IllegalArgumentException("expected initialize");
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
        if (!lines.readLine().contains("notifications/initialized")) throw new IllegalArgumentException("expected notification");
        if (!lines.readLine().contains("infer_text")) throw new IllegalArgumentException("expected infer_text");
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}");
    }
}
