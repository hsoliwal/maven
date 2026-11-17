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

// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Explicit ChromeLLM MCP stdio provider. Model text remains untrusted. */
public final class McpAdviser {
    private static final int MAX_PROMPT_BYTES = 65_536;
    private static final int MAX_CANDIDATE_BYTES = 32_768;

    public record Config(Path moduleJar, String backend, Path binary, Path model, int timeoutSeconds) {
        public Config {
            if (moduleJar == null || !Files.isRegularFile(moduleJar)
                    || binary == null || !Files.isRegularFile(binary)
                    || model == null || !Files.isRegularFile(model))
                throw new IllegalArgumentException("explicit existing module, backend and model paths required");
            if (!"cli".equals(backend) && !"jni".equals(backend)) throw new IllegalArgumentException("backend must be cli or jni");
            if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("MCP timeout outside 1..600 seconds");
        }
    }
    /** Additive Chrome provider configuration; the native Config contract is unchanged. */
    public record ChromeConfig(Path moduleJar, Path executable, Path profile, boolean headless,
                               boolean allowDownload, int timeoutSeconds) {
        public ChromeConfig {
            if (moduleJar == null || !Files.isRegularFile(moduleJar)
                    || executable == null || !Files.isRegularFile(executable) || profile == null)
                throw new IllegalArgumentException("explicit existing module/browser and dedicated profile required");
            if (timeoutSeconds < 1 || timeoutSeconds > 600)
                throw new IllegalArgumentException("MCP timeout outside 1..600 seconds");
        }
    }
    private McpAdviser() { }

    /** Java 21 child entry for the optional Maven completion pass; stdout is candidate text only. */
    public static void main(String[] args) {
        ExecutorService worker = null;
        Future<?> result = null;
        boolean failed = false;
        try {
            Config config = arguments(args);
            worker = daemonWorker("maven-native-advisory-entry");
            result = worker.submit(() -> {
                byte[] input = System.in.readNBytes(MAX_PROMPT_BYTES + 1);
                if (input.length > MAX_PROMPT_BYTES) throw new IOException("invalid advisory prompt");
                String prompt = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input)).toString();
                byte[] candidate = utf8(propose(config, prompt), MAX_CANDIDATE_BYTES);
                System.out.write(candidate);
                System.out.flush();
                if (System.out.checkError()) throw new IOException("advisory output unavailable");
                return null;
            });
            result.get(config.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed = true;
        } catch (Exception failure) {
            failed = true;
        } finally {
            if (result != null) result.cancel(true);
            if (worker != null) {
                worker.shutdownNow();
                boolean interrupted = Thread.interrupted();
                try { worker.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException again) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
        if (failed) {
            System.err.println("Native Maven advisory unavailable.");
            System.exit(1);
        }
    }

    private static Config arguments(String[] args) {
        if (args == null || args.length != 10) throw new IllegalArgumentException("invalid advisory arguments");
        Set<String> allowed = Set.of("--module-jar", "--backend", "--binary", "--model", "--timeout-seconds");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || args[i + 1] == null || args[i + 1].isBlank()
                    || values.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("invalid advisory arguments");
            }
        }
        String rawTimeout = values.get("--timeout-seconds");
        if (rawTimeout == null || !rawTimeout.matches("[0-9]{1,3}"))
            throw new IllegalArgumentException("invalid advisory timeout");
        int timeout = Integer.parseInt(rawTimeout);
        if (timeout < 1 || timeout > 120) throw new IllegalArgumentException("invalid advisory timeout");
        return new Config(Path.of(values.get("--module-jar")), values.get("--backend"),
                Path.of(values.get("--binary")), Path.of(values.get("--model")), timeout);
    }

    public static String propose(Config config, String prompt) throws Exception {
        List<String> command = command(config.moduleJar());
        command.addAll(List.of("--backend", config.backend(),
                config.backend().equals("jni") ? "--jni-lib" : "--llama-bin", config.binary().toString(),
                "--model", config.model().toString()));
        return propose(command, config.timeoutSeconds(), prompt);
    }

    public static String propose(ChromeConfig config, String prompt) throws Exception {
        List<String> command = command(config.moduleJar());
        command.addAll(List.of("--backend", "chrome", "--chrome-bin", config.executable().toString(),
                "--chrome-profile", config.profile().toAbsolutePath().normalize().toString(),
                "--chrome-headless", Boolean.toString(config.headless()),
                "--chrome-download", Boolean.toString(config.allowDownload())));
        return propose(command, config.timeoutSeconds(), prompt);
    }

    /** Additive bridge provider: ChromeLLM's loopback chat-completions backend; a null token file sends none. */
    public static String proposeBridge(Path moduleJar, String url, Path tokenFile, int timeoutSeconds, String prompt) throws Exception {
        if (moduleJar == null || !Files.isRegularFile(moduleJar) || url == null || url.isBlank() || timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("explicit module, bridge URL and 1..600 s timeout required");
        List<String> command = command(moduleJar);
        command.addAll(List.of("--backend", "bridge", "--bridge-url", url));
        if (tokenFile != null) command.addAll(List.of("--bridge-token-file", tokenFile.toString()));
        return propose(command, timeoutSeconds, prompt);
    }

    private static List<String> command(Path moduleJar) {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        // MCP stdio is UTF-8. A JVM writes a piped System.out in the native encoding unless told otherwise,
        // so a reply with a character outside ASCII was refused as malformed on Windows.
        return new ArrayList<>(List.of(javaExe.toString(), "-Dstdout.encoding=UTF-8", "--module-path", moduleJar.toString(),
                "--module", "io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain",
                "--mcp-stdio", "true"));
    }

    private static String propose(List<String> command, int timeoutSeconds, String prompt) throws Exception {
        utf8(prompt, MAX_PROMPT_BYTES);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("MCP advisory interrupted");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var pool = daemonWorker("maven-native-advisory-mcp");
        Future<String> future = null;
        try {
            future = pool.submit(() -> {
                try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(),
                             StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT)));
                     var output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    output.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"m3-smart-maven\",\"version\":\"0.1.0\"}}}\n");
                    output.flush();
                    Map<String,Object> hello = checkedResult(frame(input), "1");
                    if (!"2025-06-18".equals(hello.get("protocolVersion")))
                        throw new IOException("unexpected MCP protocol version");
                    output.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
                    output.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":"
                            + TinyJson.quote(prompt) + ",\"maxTokens\":512,\"timeoutSeconds\":" + timeoutSeconds + "}}}\n");
                    output.flush();
                    Map<String,Object> result = checkedResult(frame(input), "2");
                    if (!Boolean.FALSE.equals(result.get("isError")))
                        throw new IOException("ChromeLLM inference failed or has no success receipt");
                    Object content = result.get("content");
                    if (!(content instanceof List<?> list) || list.size() != 1) throw new IOException("unexpected MCP content");
                    Map<String, Object> item = map(list.get(0));
                    Object message = item.get("text");
                    if (!"text".equals(item.get("type")) || !(message instanceof String text))
                        throw new IOException("invalid MCP candidate");
                    utf8(text, MAX_CANDIDATE_BYTES);
                    return text;
                }
            });
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                return future.get(remaining, TimeUnit.NANOSECONDS);
            }
            catch (java.util.concurrent.TimeoutException e) { future.cancel(true); throw new IOException("MCP advisory deadline reached", e); }
            catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            catch (java.util.concurrent.ExecutionException e) {
                Throwable cause=e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new IOException("MCP advisory failure",cause);
            }
        } finally {
            boolean completed = future != null && future.isDone() && !future.isCancelled();
            if (future != null) future.cancel(true);
            pool.shutdownNow();
            retire(process, completed);
        }
    }

    private static ExecutorService daemonWorker(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static byte[] utf8(String text, int limit) throws IOException {
        if (text == null || text.isBlank() || text.length() > limit || text.indexOf('\0') >= 0)
            throw new IOException("invalid advisory text");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        if (encoded.remaining() > limit) throw new IOException("invalid advisory text");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static void retire(Process process, boolean completed) {
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> descendants = List.of();
        try {
            try { descendants = process.descendants().toList(); }
            catch (RuntimeException unavailable) { /* Enumeration may race process exit. */ }
            // A completed worker has closed stdin. Preserve the browser provider's normal shutdown.
            // Cancellation skips this grace period, and never closes a pipe owned by a blocked writer.
            if (completed && !interrupted) {
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException again) { interrupted = true; }
                catch (RuntimeException unavailable) { /* Retire the owned process below. */ }
            }
        } finally {
            for (int i = descendants.size() - 1; i >= 0; i--) {
                try { descendants.get(i).destroyForcibly(); }
                catch (RuntimeException unavailable) { /* Still retire the owned process below. */ }
            }
            // Process.destroyForcibly may synchronously close stdin while the writer owns its lock.
            // Direct process-handle retirement cannot be skipped by failed descendant observation.
            try { process.toHandle().destroyForcibly(); }
            catch (RuntimeException unavailable) { /* Cleanup must not replace the advisory result. */ }
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException again) { interrupted = true; }
            catch (RuntimeException unavailable) { /* Bounded, best-effort retirement. */ }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    /** Validate protocol, correlated response ID and success shape before reading candidate data. */
    private static Map<String,Object> checkedResult(Map<String,Object> reply, String expectedId)
            throws IOException {
        if (!"2.0".equals(reply.get("jsonrpc")) || !expectedId.equals(reply.get("id"))) {
            throw new IOException("unexpected MCP JSON-RPC version or reply ID");
        }
        if (reply.containsKey("error")) throw new IOException("MCP JSON-RPC error");
        return map(reply.get("result"));
    }

    private static Map<String,Object> frame(BufferedReader r) throws IOException {
        // BufferedReader.readLine is bounded below by checking buffered characters as read.
        StringBuilder line = new StringBuilder();
        int c;
        while ((c=r.read()) != -1 && c != '\n') {
            if (line.length() >= 1_048_576) throw new IOException("oversize MCP reply");
            line.append((char)c);
        }
        if (line.isEmpty()) throw new IOException("missing MCP response");
        try { return map(TinyJson.parse(line.toString())); }
        catch (IllegalArgumentException e) { throw new IOException("invalid MCP response",e); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object o) throws IOException {
        if (!(o instanceof Map<?,?> m)) throw new IOException("expected JSON object");
        return (Map<String,Object>) m;
    }
}
