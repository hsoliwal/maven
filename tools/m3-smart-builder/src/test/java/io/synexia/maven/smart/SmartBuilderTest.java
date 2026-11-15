package io.synexia.maven.smart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        passed++;
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
    private static void expectRuntime(String name,Throwing task) throws Exception {
        try { task.run(); throw new AssertionError("expected RuntimeException: "+name); }
        catch (IllegalArgumentException expected) { /* correct failure */ }
    }
}
