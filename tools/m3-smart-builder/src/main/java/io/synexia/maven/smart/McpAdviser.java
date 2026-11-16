// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit ChromeLLM MCP stdio provider. Model text remains untrusted. */
public final class McpAdviser {
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

    private static List<String> command(Path moduleJar) {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new ArrayList<>(List.of(javaExe.toString(), "--module-path", moduleJar.toString(),
                "--module", "io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain",
                "--mcp-stdio", "true"));
    }

    private static String propose(List<String> command, int timeoutSeconds, String prompt) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> {
                try (var input = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     var output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                    output.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"m3-smart-maven\",\"version\":\"0.1.0\"}}\n");
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
                    Map<String,Object> item = map(list.get(0));
                    if (!"text".equals(item.get("type"))) throw new IOException("MCP content is not text");
                    Object message = item.get("text");
                    if (!(message instanceof String text) || text.isEmpty() || text.length() > 32768)
                        throw new IOException("invalid MCP candidate");
                    return text;
                }
            });
            try { return future.get(timeoutSeconds, TimeUnit.SECONDS); }
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
            terminate(process);
            pool.shutdownNow();
        }
    }
    private static void terminate(Process process) {
        // The worker closes stdin after a completed transaction, allowing normal shutdown.
        // Do not close it here: close/flush can block behind a worker writing a full pipe.
        // On cancellation, retire descendants and the Java parent before closing streams.
        List<ProcessHandle> descendants = process.descendants().toList();
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            if (!interrupted && process.waitFor(2, TimeUnit.SECONDS)) return;
        } catch (InterruptedException stopped) { interrupted = true; }
        finally {
            for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
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
