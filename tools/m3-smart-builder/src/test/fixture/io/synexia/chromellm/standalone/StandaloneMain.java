package io.synexia.chromellm.standalone;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Protocol fixture with deterministic negative replies; never runs inference. */
public final class StandaloneMain {
    public static void main(String[] args) throws Exception {
        String scenario = "valid";
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--model".equals(args[i])) {
                scenario = Path.of(args[i + 1]).getFileName().toString().replace("mcp-", "")
                        .replace(".gguf", "");
                break;
            }
        }
        var lines = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!lines.readLine().contains("initialize")) throw new IllegalArgumentException("expected initialize");
        String initialize = switch (scenario) {
            case "init-wrong-id" -> "{\"jsonrpc\":\"2.0\",\"id\":77,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
            case "init-wrong-rpc" -> "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
            case "init-error" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"rejected\"}}";
            case "init-wrong-version" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
            default -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
        };
        System.out.println(initialize);
        if (!scenario.startsWith("init-")) {
            if (!lines.readLine().contains("notifications/initialized"))
                throw new IllegalArgumentException("expected notification");
            if (!lines.readLine().contains("infer_text"))
                throw new IllegalArgumentException("expected infer_text");
            String response = switch (scenario) {
                case "call-wrong-id" -> "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-wrong-rpc" -> "{\"jsonrpc\":\"1.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-error" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32600,\"message\":\"failed\"}}";
                case "call-is-error" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"error\"}]}}";
                case "call-no-success" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-nontext" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"image\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-empty" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"\"}]}}";
                default -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
            };
            System.out.println(response);
        }
    }
}
