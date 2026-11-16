/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.synexia.chromellm.standalone;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Protocol fixture: responds with invented text, never runs real inference. */
public final class StandaloneMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--fixture-child")) {
            Thread.sleep(60_000);
            return;
        }
        java.util.Map<String, String> options = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i += 2) options.put(args[i], args[i + 1]);
        String profile = options.getOrDefault("--chrome-profile", "");
        boolean observeEvidence = profile.endsWith("evidence-observer");
        if (observeEvidence) Files.writeString(Path.of(profile + ".invoked"), "synthetic provider started\n");
        boolean chrome = "chrome".equals(options.get("--backend"));
        if (chrome) {
            if (options.containsKey("--model") || options.containsKey("--jni-lib")
                    || options.containsKey("--llama-bin") || !options.containsKey("--chrome-bin")
                    || !options.containsKey("--chrome-profile"))
                throw new IllegalArgumentException("mixed provider options");
            var lines = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            if (!lines.readLine().contains("initialize")) throw new IllegalArgumentException("expected initialize");
            String scenario = options.get("--chrome-profile").endsWith("bad-hello") ? "init-wrong-id" : "valid";
            strictProtocolFixture(lines, scenario, options);
            return;
        }
        Path model = null;
        String backend = null;
        String nativeFlag = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--model")) model = Path.of(args[i + 1]);
            if (args[i].equals("--backend")) backend = args[i + 1];
            if (args[i].equals("--jni-lib") || args[i].equals("--llama-bin")) nativeFlag = args[i];
        }
        if (model == null) throw new IllegalArgumentException("expected explicit model");
        if (!("cli".equals(backend) && "--llama-bin".equals(nativeFlag))
                && !("jni".equals(backend) && "--jni-lib".equals(nativeFlag)))
            throw new IllegalArgumentException("expected explicit matching native backend");
        Files.writeString(Path.of(model + ".started"), Long.toString(ProcessHandle.current().pid()));
        var lines = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String initialization = lines.readLine();
        Files.writeString(Path.of(model + ".initialize"), initialization, StandardCharsets.UTF_8);
        if (!initialization.contains("initialize")) throw new IllegalArgumentException("expected initialize");
        if (model.getFileName().toString().startsWith("mcp-")) {
            String scenario = model.getFileName().toString().replace("mcp-", "").replace(".gguf", "");
            strictProtocolFixture(lines, scenario, options);
            return;
        }
        String helloId = model.getFileName().toString().startsWith("bad-hello") ? "99" : "1";
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + helloId + ",\"result\":{\"protocolVersion\":\"2025-06-18\"}}");
        if (model.getFileName().toString().startsWith("blocked-native-stdin")) Thread.sleep(60_000);
        if (!lines.readLine().contains("notifications/initialized")) throw new IllegalArgumentException("expected notification");
        String call = lines.readLine();
        if (!call.contains("infer_text")) throw new IllegalArgumentException("expected infer_text");
        if (call.contains("FIXTURE_DESCENDANT")) {
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            Process child = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "--module-path",
                    System.getProperty("jdk.module.path"), "--module",
                    "io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain",
                    "--fixture-child").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(Path.of(model + ".pids"), ProcessHandle.current().pid() + "\n" + child.pid() + "\n");
            if (call.contains("FIXTURE_DESCENDANT_STALL")) Thread.sleep(60_000);
        }
        if (call.contains("FIXTURE_STALL")) Thread.sleep(60_000);
        if (call.contains("FIXTURE_BAD_UTF8")) {
            System.out.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"".getBytes(StandardCharsets.UTF_8));
            System.out.write(new byte[]{(byte) 0xc3, 0x28});
            System.out.write("\"}]}}\n".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            return;
        }
        String text = "fake MCP advisory";
        if (call.contains("FIXTURE_UNICODE")) text = "Review é 🔧";
        if (call.contains("FIXTURE_BOUNDARY")) text = "é".repeat(16_384);
        if (call.contains("FIXTURE_OVERSIZE_UTF8")) text = "é".repeat(16_385);
        if (call.contains("FIXTURE_OVERSIZE_ASCII")) text = "x".repeat(32_769);
        if (call.contains("FIXTURE_BLANK")) text = "   ";
        if (call.contains("FIXTURE_NUL")) text = "invalid\\u0000candidate";
        if (call.contains("FIXTURE_HIGH_SURROGATE")) text = "\\ud800";
        if (call.contains("FIXTURE_LOW_SURROGATE")) text = "\\udc00";
        if (call.contains("FIXTURE_CONFIGURED_TIMEOUT")) {
            if (!call.contains("\"timeoutSeconds\":7")) throw new IllegalArgumentException("wrong timeout");
            text = "configured timeout";
        }
        String id = call.contains("FIXTURE_BAD_ID") ? "9" : "2";
        String type = call.contains("FIXTURE_NOT_TEXT") ? "image" : "text";
        String error = call.contains("FIXTURE_BAD_ERROR") ? "\"false\"" : "false";
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"isError\":" + error
                + ",\"content\":[{\"type\":\"" + type + "\",\"text\":\"" + text + "\"}]}}");
        if (call.contains("FIXTURE_DESCENDANT")) Thread.sleep(60_000);
    }

    /** Preserve the receiving fork's strict reply scenarios in the same fixture owner. */
    private static void strictProtocolFixture(BufferedReader lines, String scenario,
            java.util.Map<String, String> options) throws Exception {
        String initialize = switch (scenario) {
            case "init-wrong-id" -> "{\"jsonrpc\":\"2.0\",\"id\":77,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
            case "init-wrong-rpc" -> "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
            case "init-error" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"rejected\"}}";
            case "init-wrong-version" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
            default -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
        };
        System.out.println(initialize);
        if (options.getOrDefault("--chrome-profile", "").endsWith("blocked-writer")) {
            System.out.flush();
            Files.writeString(Path.of(options.get("--chrome-bin") + ".blocked-writer"),
                    Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(15000);
            return;
        }
        if (!scenario.startsWith("init-")) {
            if (!lines.readLine().contains("notifications/initialized"))
                throw new IllegalArgumentException("expected notification");
            String profile = options.getOrDefault("--chrome-profile", "");
            boolean observeEvidence = profile.endsWith("evidence-observer");
            String request = lines.readLine();
            if (observeEvidence) Files.writeString(Path.of(profile + ".request"), request);
            if (!request.contains("infer_text")) throw new IllegalArgumentException("expected infer_text");
            if (request.contains("fixture-provider-error")) scenario = "call-is-error";
            if (request.contains("fixture timeout")) Thread.sleep(15000);
            if (request.contains("fixture invalid content")) scenario = "call-nontext";
            if (request.contains("fixture wrong reply id")) scenario = "call-wrong-id";
            String timeout = request.replaceAll(".*\\\"timeoutSeconds\\\":([0-9]+).*", "$1");
            String text = "fake MCP advisory" + ("chrome".equals(options.get("--backend"))
                    ? " backend=chrome headless=" + options.get("--chrome-headless")
                    + " download=" + options.get("--chrome-download") + " timeout=" + timeout : "");
            String response = switch (scenario) {
                case "call-wrong-id" -> "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-wrong-rpc" -> "{\"jsonrpc\":\"1.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-error" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32600,\"message\":\"failed\"}}";
                case "call-is-error" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"error\"}]}}";
                case "call-no-success" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-nontext" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"image\",\"text\":\"fake MCP advisory\"}]}}";
                case "call-empty" -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"\"}]}}";
                default -> "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
            };
            System.out.println(response);
        }
    }
}
