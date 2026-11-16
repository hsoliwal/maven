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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** No network, Maven, Chrome, native backend or privileged writes. */
public final class SmartBuilderTest {
    private static int passed;
    private static final byte[] FIXTURE = "approved native bytes".getBytes(StandardCharsets.UTF_8);
    private static Path root;
    private SmartBuilderTest() { }

    public static void main(String[] args) throws Exception {
        root = Path.of(System.getProperty("m3.smart.testRoot", "target")).toAbsolutePath();
        if (!Files.isDirectory(root)) throw new IOException("test root must already exist");
        root = Files.createTempDirectory(root, "m3-smart-fixture-");
        testManifest(); testPomFacts(); testPlanner(); testDownloadedCache(); testOffline(); testCorruption(); testRejectedDownloads(); testJson();
        testCacheSafety();
        testBuildLogFacts(); testSourceInventory(); testDiagnosis(); testSafeBuildOutput();
        if (System.getProperty("m3.smart.fixtureJar") != null) testMcpFixture();
        System.out.println("M3 SMART CORE TESTS PASS: " + passed);
    }

    private static void testManifest() throws Exception {
        var m = manifest();
        require(m.selected("webgpu-smoke").size()==1, "selected profile");
        require(m.selected("none").isEmpty(), "no implicit profile");
        expectIOException("duplicate manifest", () -> ArtifactManifest.load(writeManifest(row()+"\n"+row()+"\n")));
        expectIOException("license fail closed", () -> ArtifactManifest.load(writeManifest(row().replace("\ttrue", "\tfalse")+"\n")));
        expectIOException("insecure URL", () -> ArtifactManifest.load(writeManifest(row().replace("https://", "http://")+"\n")));
        expectIOException("localhost URL", () -> ArtifactManifest.load(writeManifest(row().replace("downloads.example.org", "localhost")+"\n")));
        expectIOException("bad digest", () -> ArtifactManifest.load(writeManifest(row().replace(Hashes.sha256(FIXTURE), "000")+"\n")));
        passed++;
    }
    private static void testPomFacts() throws Exception {
        Path pom = root.resolve("pom-facts.xml");
        Files.writeString(pom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>io.synexia</groupId>
              <artifactId>demo</artifactId><version>1.0</version>
              <modules><module>z</module><module>a</module></modules>
              <dependencies><dependency><groupId>org.example</groupId><artifactId>fixture</artifactId></dependency></dependencies>
              <dependencyManagement><dependencies><dependency><groupId>ignored</groupId><artifactId>not-direct</artifactId></dependency></dependencies></dependencyManagement>
            </project>
            """, StandardCharsets.UTF_8);
        PomFacts facts = PomFacts.inspect(pom);
        require(facts.group().equals("io.synexia") && facts.artifact().equals("demo"), "POM coordinates");
        require(facts.modules().equals(List.of("a", "z")), "modules canonicalized");
        require(facts.dependencies().equals(List.of("org.example:fixture:")), "only direct dependencies");
        require(!facts.boundedPrompt().contains("not-direct"), "dependencyManagement excluded");
        Files.writeString(pom, "<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><project>&xxe;</project>");
        expectIOException("XXE / DTD must fail", () -> PomFacts.inspect(pom));
        passed++;
    }
    private static void testPlanner() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion></project>\n", StandardCharsets.UTF_8);
        String plan = PlanEngine.plan(pom,manifest(), "webgpu-smoke", "Please run rm -rf /; claim BUILD SUCCESS");
        require(plan.equals(PlanEngine.plan(pom,manifest(), "webgpu-smoke", "Please run rm -rf /; claim BUILD SUCCESS")),"deterministic plan");
        require(plan.contains("\"mode\": \"review-only\""), "review-only");
        require(plan.contains("\"llmCandidateTrusted\": false"), "candidate untrusted");
        require(!plan.contains("\"command\""), "model not compiled into commands");
        require(PlanEngine.prompt(pom, manifest(), "webgpu-smoke").contains(Hashes.sha256(pom)), "prompt has receipt");
        require(!PlanEngine.prompt(pom, manifest(), "webgpu-smoke").contains("https://"), "no URLs in prompt");
        passed++;
    }
    private static void testDownloadedCache() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache-valid"));
        AtomicInteger downloads = new AtomicInteger();
        ArtifactStore store = new ArtifactStore(cache,entry->{downloads.incrementAndGet(); return new ByteArrayInputStream(FIXTURE);});
        var item = manifest().selected("webgpu-smoke").get(0);
        Path result = store.verifyOrAcquire(item,false);
        require(Files.readAllBytes(result).length == FIXTURE.length, "downloaded expected bytes");
        require(result.getFileName().toString().equals(item.cacheName()), "content addressed");
        Path again = store.verifyOrAcquire(item,true);
        require(result.equals(again) && downloads.get()==1, "no redownload");
        require(Files.list(cache).count()==1, "staging cleanup");
        passed++;
    }
    private static void testOffline() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache-offline"));
        ArtifactStore store = new ArtifactStore(cache,entry->{ throw new AssertionError("network must not execute offline"); });
        expectIOException("offline missing", ()-> store.verifyOrAcquire(manifest().selected("webgpu-smoke").get(0),true));
        require(Files.list(cache).count()==0,"offline no writes");
        passed++;
    }
    private static void testCorruption() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache-corrupt"));
        var item = manifest().selected("webgpu-smoke").get(0);
        Files.writeString(cache.resolve(item.cacheName()), "tampered", StandardCharsets.UTF_8);
        ArtifactStore store = new ArtifactStore(cache,entry->{ throw new AssertionError("corrupt cache must fail closed"); });
        expectIOException("corrupt cache", ()-> store.verifyOrAcquire(item,false));
        require(Files.readString(cache.resolve(item.cacheName())).equals("tampered"), "no silent repair");
        passed++;
    }
    private static void testRejectedDownloads() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache-reject"));
        var item = manifest().selected("webgpu-smoke").get(0);
        ArtifactStore wrong = new ArtifactStore(cache, entry -> new ByteArrayInputStream("wrong".getBytes(StandardCharsets.UTF_8)));
        expectIOException("digest mismatch", ()->wrong.verifyOrAcquire(item,false));
        var tiny = new ArtifactManifest.Entry(item.id(), item.profile(), item.kind(), item.uri(), item.sha256(), 2);
        ArtifactStore oversized = new ArtifactStore(cache, entry -> new ByteArrayInputStream(FIXTURE));
        expectIOException("size bound", ()->oversized.verifyOrAcquire(tiny,false));
        require(Files.list(cache).count()==0, "no incomplete publication");
        passed++;
    }
    private static void testJson() throws Exception {
        Object value = TinyJson.parse("{\"result\":{\"content\":[{\"text\":\"hello\"}]},\"id\":2}");
        require(value instanceof Map, "nested reply parse");
        expectRuntime("duplicate JSON keys", () -> TinyJson.parse("{\"id\":null,\"id\":2}"));
        expectRuntime("JSON trailing garbage", () -> TinyJson.parse("{} true"));
        require(TinyJson.quote("hello\nworld").equals("\"hello\\nworld\""),"JSON encode");
        require(TinyJson.parse("\"🔧\"").equals("🔧"), "raw supplementary JSON text");
        expectRuntime("raw high surrogate", () -> TinyJson.parse("\"\ud800\""));
        expectRuntime("raw low surrogate", () -> TinyJson.parse("\"\udc00\""));
        passed++;
    }
    private static void testCacheSafety() throws Exception {
        var item = manifest().selected("webgpu-smoke").get(0);
        expectIOException("nonexistent cache", ()->new ArtifactStore(root.resolve("not-created"),e->new ByteArrayInputStream(FIXTURE)));
        Path cache = Files.createDirectory(root.resolve("cache-symlink"));
        try {
            Files.createSymbolicLink(cache.resolve(item.cacheName()), root.resolve("pom.xml"));
            ArtifactStore store = new ArtifactStore(cache,e->new ByteArrayInputStream(FIXTURE));
            expectIOException("symlinked cache entry",()->store.verifyOrAcquire(item,false));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // Some Windows installations do not support symlinks without Developer Mode.
        }
        passed++;
    }
    private static void testBuildLogFacts() throws Exception {
        Path file = root.resolve("m3-maven.log");
        String secret = "credential=DO_NOT_ECHO_THIS_TOKEN";
        Files.writeString(file, "[ERROR] cannot find symbol " + secret + "\n"
                + "[ERROR] Could not find artifact x:y:z\n"
                + "[ERROR] There are test failures.\n"
                + "[ERROR] failed to execute goal\n"
                + "[ERROR] undefined reference to Java_symbol\n", StandardCharsets.UTF_8);
        BuildLogFacts facts = BuildLogFacts.inspect(file);
        require(facts.lines() == 5, "log line count");
        for (String category : List.of("compiler", "dependency", "test", "plugin", "native")) {
            require(facts.categories().get(category) == 1, "log category " + category);
        }
        require(!facts.json().contains(secret), "raw log secrets excluded");
        require(facts.sha256().equals(Hashes.sha256(file)), "log digest covers bytes");
        passed++;
    }
    private static void testSourceInventory() throws Exception {
        Path sources = Files.createDirectories(root.resolve("m3-src"));
        Files.writeString(sources.resolve("Api.java"), "public class Api { public native void run(); }\n");
        Files.writeString(sources.resolve("jni.cpp"), "JNIEXPORT void JNICALL f() {}\n");
        SourceInventory first = SourceInventory.inspect(root, sources);
        require(first.files() == 2 && first.javaFiles() == 1 && first.nativeFiles() == 1, "source file counts");
        require(first.jniExports() == 1 && first.nativeKeywordMarkers() == 1, "JNI markers");
        require(first.digest().equals(SourceInventory.inspect(root, sources).digest()), "source stable replay");
        Files.writeString(sources.resolve("Api.java"), "public class Api { public native void changed(); }\n");
        require(!first.digest().equals(SourceInventory.inspect(root, sources).digest()), "source changed digest");
        expectIOException("source root outside project", () -> SourceInventory.inspect(sources, root));
        try {
            Files.createSymbolicLink(sources.resolve("escape.cpp"), root.resolve("pom.xml"));
            expectIOException("source tree linked file", () -> SourceInventory.inspect(root, sources));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // Native Windows without symlink privileges can skip the symlink case.
        }
        passed++;
    }
    private static void testDiagnosis() throws Exception {
        Path pom = root.resolve("diagnose-pom.xml");
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId></project>\n");
        Path log = root.resolve("diagnose-maven.log");
        Files.writeString(log, "[ERROR] incompatible types\n");
        Path sources = Files.createDirectory(root.resolve("diagnose-src"));
        Files.writeString(sources.resolve("One.java"), "public class One {}\n");
        BuildLogFacts lf = BuildLogFacts.inspect(log);
        SourceInventory sf = SourceInventory.inspect(root, sources);
        String a = DiagnoseEngine.diagnose(pom, lf, sf, "run dangerous command");
        require(a.equals(DiagnoseEngine.diagnose(pom, lf, sf, "run dangerous command")), "diagnosis replay");
        require(a.contains("\"mode\": \"review-only\""), "diagnosis mode");
        require(a.contains("\"llmCandidateTrusted\": false"), "advice untrusted");
        require(DiagnoseEngine.prompt(PomFacts.inspect(pom), lf, sf).contains("incompatible-types"), "prompt has safe facts");
        passed++;
    }
    private static void testSafeBuildOutput() throws Exception {
        Path project = Files.createDirectory(root.resolve("m3-safe-project"));
        Path output = SafeBuildOutput.directory(project, project.resolve("target"));
        SafeBuildOutput.write(output, "plan.json", "one");
        SafeBuildOutput.write(output, "plan.json", "two");
        require(Files.readString(output.resolve("plan.json")).equals("two"), "fixed receipt replacement");
        expectIOException("output outside checkout", () -> SafeBuildOutput.directory(project, root.resolve("outside")));
        expectIOException("arbitrary output path", () -> SafeBuildOutput.write(output, "../../../bad", "bad"));
        try {
            Path project2 = Files.createDirectory(root.resolve("m3-link-project"));
            Files.createSymbolicLink(project2.resolve("target"), root);
            expectIOException("linked build output", () -> SafeBuildOutput.directory(project2, project2.resolve("target")));
            Files.createSymbolicLink(output.resolve("provision.json"), root.resolve("pom.xml"));
            expectIOException("linked receipt file", () -> SafeBuildOutput.write(output, "provision.json", "bad"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // Windows symlink creation may require elevated developer privileges.
        }
        passed++;
    }
    private static void testMcpFixture() throws Exception {
        Path jar = Path.of(System.getProperty("m3.smart.fixtureJar")).toAbsolutePath();
        Path cli = Files.writeString(root.resolve("mock-cli"), "fixture");
        Path model = Files.writeString(root.resolve("mock-model.gguf"), "fixture");
        String result = McpAdviser.propose(new McpAdviser.Config(jar,"cli",cli,model,10),"Review only");
        require(result.equals("fake MCP advisory"),"MCP exchange");
        testMcpInitialization(model);
        for (String scenario : List.of("init-wrong-id", "init-wrong-rpc", "init-error",
                "init-wrong-version", "call-wrong-id", "call-wrong-rpc", "call-error",
                "call-is-error", "call-no-success", "call-nontext", "call-empty")) {
            Path invalid = Files.writeString(root.resolve("mcp-" + scenario + ".gguf"), "fixture");
            String expectedMessage = switch (scenario) {
                case "init-wrong-id", "init-wrong-rpc", "call-wrong-id", "call-wrong-rpc" ->
                        "unexpected MCP JSON-RPC version or reply ID";
                case "init-error", "call-error" -> "MCP JSON-RPC error";
                case "init-wrong-version" -> "unexpected MCP protocol version";
                case "call-is-error", "call-no-success" ->
                        "ChromeLLM inference failed or has no success receipt";
                case "call-nontext" -> "invalid MCP candidate";
                case "call-empty" -> "invalid advisory text";
                default -> throw new AssertionError("unmapped protocol scenario " + scenario);
            };
            expectIOExceptionMessage("MCP rejects " + scenario, expectedMessage, () -> McpAdviser.propose(
                    new McpAdviser.Config(jar, "cli", cli, invalid, 10), "Review only"));
            passed++;
        }
        passed++;
        testMcpText(jar, cli);
        testMcpLifecycle(jar, cli);
        testMcpEntry(jar, cli);
    }

    private static void testMcpInitialization(Path model) throws Exception {
        Path wire = Path.of(model + ".initialize");
        require(Files.isRegularFile(wire), "actual MCP initialization request was captured");
        Object parsed;
        try {
            parsed = TinyJson.parse(Files.readString(wire, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new AssertionError("MCP initialization request must be complete JSON", malformed);
        }
        require(parsed instanceof Map<?, ?>, "MCP initialization JSON object");
        Map<?, ?> request = (Map<?, ?>) parsed;
        require("2.0".equals(request.get("jsonrpc")) && "1".equals(request.get("id"))
                && "initialize".equals(request.get("method")), "MCP initialization envelope");
        require(request.get("params") instanceof Map<?, ?>, "MCP initialization parameters");
        Map<?, ?> params = (Map<?, ?>) request.get("params");
        require("2025-06-18".equals(params.get("protocolVersion"))
                && params.get("capabilities") instanceof Map<?, ?>
                && params.get("clientInfo") instanceof Map<?, ?>, "MCP initialization negotiation");
        Map<?, ?> client = (Map<?, ?>) params.get("clientInfo");
        require("m3-smart-maven".equals(client.get("name")) && "0.1.0".equals(client.get("version")),
                "MCP initialization client identity");
        passed++;
    }

    private static void testMcpText(Path jar, Path cli) throws Exception {
        var config = config(jar, cli, "text", 7);
        require(McpAdviser.propose(config, "FIXTURE_UNICODE").equals("Review é 🔧"), "native supplementary candidate");
        require(McpAdviser.propose(config, "FIXTURE_BOUNDARY").getBytes(StandardCharsets.UTF_8).length == 32_768,
                "exact candidate byte limit");
        require(McpAdviser.propose(config, "FIXTURE_CONFIGURED_TIMEOUT").equals("configured timeout"),
                "configured native timeout");
        require(McpAdviser.propose(config, "é".repeat(32_768)).equals("fake MCP advisory"),
                "exact prompt byte limit");
        passed += 4;
        for (String mode : List.of("OVERSIZE_UTF8", "OVERSIZE_ASCII", "BLANK", "NUL", "HIGH_SURROGATE",
                "LOW_SURROGATE", "BAD_UTF8", "BAD_ID", "BAD_ERROR", "NOT_TEXT")) {
            expectIOException("invalid native candidate " + mode, () -> McpAdviser.propose(config, "FIXTURE_" + mode));
            passed++;
        }
        expectIOException("initialization response identity", () -> McpAdviser.propose(config(jar, cli, "bad-hello", 7), "review"));
        passed++;
        var unused = config(jar, cli, "invalid-prompt-no-start", 7);
        for (String prompt : new String[]{null, " ", "bad\0prompt", "\ud800", "\udc00", "é".repeat(32_769)}) {
            expectIOException("invalid prompt before start", () -> McpAdviser.propose(unused, prompt));
            require(!Files.exists(Path.of(unused.model() + ".started")), "invalid prompt did not start a provider");
            passed++;
        }
        Thread.currentThread().interrupt();
        try {
            McpAdviser.propose(unused, "review");
            throw new AssertionError("pre-interrupted invocation accepted");
        } catch (InterruptedException expected) {
            require(Thread.currentThread().isInterrupted(), "pre-interruption retained");
            require(!Files.exists(Path.of(unused.model() + ".started")), "pre-interruption did not start provider");
        } finally { Thread.interrupted(); }
        passed++;
    }

    private static void testMcpLifecycle(Path jar, Path cli) throws Exception {
        var timed = config(jar, cli, "deadline", 1);
        long before = System.nanoTime();
        expectIOException("total MCP deadline", () -> McpAdviser.propose(timed, "FIXTURE_DESCENDANT_STALL"));
        require(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5), "deadline bounded including cleanup");
        stopped(timed.model());
        passed++;

        var blockedInput = config(jar, cli, "blocked-native-stdin", 1);
        before = System.nanoTime();
        expectIOException("blocked native stdin deadline", () -> McpAdviser.propose(blockedInput, "\"".repeat(65_536)));
        require(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5), "blocked writer cannot hold cleanup");
        awaitFile(Path.of(blockedInput.model() + ".started"));
        require(!alive(Long.parseLong(Files.readString(Path.of(blockedInput.model() + ".started")))),
                "provider with blocked stdin retired");
        passed++;

        var cancelled = config(jar, cli, "cancelled", 30);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                McpAdviser.propose(cancelled, "FIXTURE_DESCENDANT_STALL");
                outcome.set(new AssertionError("interrupted call returned a candidate"));
            } catch (InterruptedException expected) {
                if (!Thread.currentThread().isInterrupted()) outcome.set(new AssertionError("interrupt flag lost"));
            } catch (Throwable unexpected) { outcome.set(unexpected); }
        }, "mcp-interruption-fixture");
        caller.start();
        try {
            awaitFile(Path.of(cancelled.model() + ".pids"));
            caller.interrupt();
            caller.join(5_000);
            require(!caller.isAlive(), "interruption bounded including cleanup");
            require(outcome.get() == null, "interruption outcome: " + outcome.get());
            stopped(cancelled.model());
        } finally { caller.interrupt(); }
        passed++;

        var completed = config(jar, cli, "normal-descendant", 7);
        require(McpAdviser.propose(completed, "FIXTURE_DESCENDANT").equals("fake MCP advisory"),
                "candidate survives normal cleanup");
        stopped(completed.model());
        passed++;
        for (int i = 0; i < 3; i++) {
            require(McpAdviser.propose(config(jar, cli, "already-exiting-" + i, 7), "review").equals("fake MCP advisory"),
                    "already exiting provider preserves candidate");
        }
        passed++;
    }

    private static void testMcpEntry(Path jar, Path cli) throws Exception {
        var good = config(jar, cli, "main-unicode", 7);
        Entry valid = entry(good, "FIXTURE_UNICODE".getBytes(StandardCharsets.UTF_8), null, null);
        require(valid.exit() == 0 && valid.out().equals("Review é 🔧") && valid.err().isEmpty(),
                "entry emits only exact UTF-8 candidate");
        passed++;
        Entry jni = entry(new McpAdviser.Config(jar, "jni", cli, good.model(), 7),
                "FIXTURE_UNICODE".getBytes(StandardCharsets.UTF_8), null, null);
        require(jni.exit() == 0 && jni.out().equals("Review é 🔧") && jni.err().isEmpty(),
                "entry preserves explicit JNI selection through existing transport");
        passed++;
        for (byte[] input : new byte[][]{new byte[0], {0}, {(byte) 0xc3, 0x28}, new byte[65_537]}) {
            var unused = config(jar, cli, "main-invalid-input-" + passed, 7);
            Entry invalid = entry(unused, input, null, null);
            refused(invalid);
            require(!Files.exists(Path.of(unused.model() + ".started")), "entry validates input before provider start");
            passed++;
        }
        for (String mode : List.of("BLANK", "NUL", "OVERSIZE_UTF8", "NOT_TEXT", "BAD_UTF8")) {
            refused(entry(good, ("FIXTURE_" + mode).getBytes(StandardCharsets.UTF_8), null, null));
            passed++;
        }
        for (String[] pair : new String[][]{{"--unknown", "x"}, {"--module-jar", "duplicate"},
                {"--backend", "chrome"}, {"--timeout-seconds", "0"}, {"--timeout-seconds", "121"}}) {
            refused(entry(good, "review".getBytes(StandardCharsets.UTF_8), pair[0], pair[1]));
            passed++;
        }
        var blocked = config(jar, cli, "main-blocked-stdin", 1);
        refused(entry(blocked, null, null, null));
        require(!Files.exists(Path.of(blocked.model() + ".started")), "blocked stdin never starts provider");
        passed++;
    }

    private static McpAdviser.Config config(Path jar, Path cli, String name, int seconds) throws IOException {
        return new McpAdviser.Config(jar, "cli", cli, Files.writeString(root.resolve(name + ".gguf"), "fixture"), seconds);
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        require(Files.exists(path), "fixture process receipt: " + path.getFileName());
    }

    private static void stopped(Path model) throws Exception {
        Path receipt = Path.of(model + ".pids");
        awaitFile(receipt);
        List<Long> pids = Files.readAllLines(receipt).stream().map(Long::parseLong).toList();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pids.stream().anyMatch(SmartBuilderTest::alive) && System.nanoTime() < deadline) Thread.sleep(10);
        require(pids.stream().noneMatch(SmartBuilderTest::alive), "owned provider and descendant retired");
    }

    private static boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }

    private record Entry(int exit, String out, String err) { }

    private static Entry entry(McpAdviser.Config config, byte[] input, String replaceFlag, String value) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-XX:-UsePerfData", "-cp",
                Path.of(McpAdviser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                "io.synexia.maven.smart.McpAdviser",
                "--module-jar", config.moduleJar().toString(), "--backend", config.backend(),
                "--binary", config.binary().toString(), "--model", config.model().toString(),
                "--timeout-seconds", Integer.toString(config.timeoutSeconds())));
        if (replaceFlag != null) {
            int index = command.indexOf(replaceFlag);
            if (replaceFlag.equals("--module-jar")) {
                command.set(command.indexOf("--binary"), replaceFlag); // Duplicate flag in a fixed-size argv.
            } else if (index >= 0) command.set(index + 1, value);
            else { command.set(command.indexOf("--binary"), replaceFlag); command.set(command.indexOf(config.binary().toString()), value); }
        }
        Process child = new ProcessBuilder(command).start();
        try {
            if (input != null) {
                child.getOutputStream().write(input);
                child.getOutputStream().close();
            }
            require(child.waitFor(8, TimeUnit.SECONDS), "entry total deadline");
            return new Entry(child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }
    }

    private static void refused(Entry result) {
        require(result.exit() != 0 && result.out().isEmpty()
                && result.err().equals("Native Maven advisory unavailable." + System.lineSeparator()),
                "entry refusal is generic and candidate-free");
    }
    private static ArtifactManifest manifest() throws Exception { return ArtifactManifest.load(writeManifest(row()+"\n")); }
    private static String row() { return "webgpu-runtime\twebgpu-smoke\tnative-bin\thttps://downloads.example.org/runtime.bin\t"+Hashes.sha256(FIXTURE)+"\t1048576\ttrue"; }
    private static Path writeManifest(String rows) throws IOException {
        Path p = root.resolve("manifest.tsv");
        Files.writeString(p, "id\tprofile\tkind\turl\tsha256\tmaxBytes\tlicenseApproved\n"+rows, StandardCharsets.UTF_8);
        return p;
    }
    private static void require(boolean condition,String what) { if (!condition) throw new AssertionError(what); }
    @FunctionalInterface private interface Throwing {void run() throws Exception;}
    private static void expectIOException(String name,Throwing task) throws Exception {
        try { task.run(); throw new AssertionError("expected IOException: "+name); }
        catch (IOException expected) { /* correct failure */ }
    }
    private static void expectIOExceptionMessage(String name, String expectedMessage, Throwing task) throws Exception {
        try { task.run(); throw new AssertionError("expected IOException: " + name); }
        catch (IOException expected) {
            require(expectedMessage.equals(expected.getMessage()),
                    name + " returned an unexpected failure: " + expected.getMessage());
        }
    }
    private static void expectRuntime(String name,Throwing task) throws Exception {
        try { task.run(); throw new AssertionError("expected RuntimeException: "+name); }
        catch (IllegalArgumentException expected) { /* correct failure */ }
    }
}
