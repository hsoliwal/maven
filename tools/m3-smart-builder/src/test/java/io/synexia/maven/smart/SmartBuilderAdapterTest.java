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

package io.synexia.maven.smart;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Execute opt-in goals against stub Maven injections; no network or real Maven runtime. */
public final class SmartBuilderAdapterTest {
    private SmartBuilderAdapterTest() { }
    private static void set(Object object, String fieldName, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(object, value);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path parent = Path.of(System.getProperty("m3.smart.testRoot", "target")).toAbsolutePath();
        if (!Files.isDirectory(parent)) throw new IllegalArgumentException("existing test root required");
        Path project = Files.createTempDirectory(parent, "m3-adapter-fixture-");
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>io.test</groupId>"
                        + "<artifactId>fixture</artifactId><version>1.0</version></project>\n", StandardCharsets.UTF_8);
        Path build = project.resolve("target");

        PlanMojo plan = new PlanMojo();
        set(plan, "baseDir", project.toFile());
        set(plan, "buildDir", build.toFile());
        set(plan, "profile", "none");
        plan.execute();
        String first = Files.readString(build.resolve("m3-smart/plan.json"));
        plan.execute();
        require(first.equals(Files.readString(build.resolve("m3-smart/plan.json"))), "plan should replay");
        require(first.contains("\"mode\": \"review-only\""), "plan must be advisory");

        Path source = Files.createDirectory(project.resolve("src"));
        Files.writeString(source.resolve("Smoke.java"), "public class Smoke { public native void f(); }\n");
        Path log = project.resolve("build.log");
        Files.writeString(log, "[ERROR] cannot find symbol secret=ADAPTER_TOKEN\n");
        DiagnoseMojo diagnose = new DiagnoseMojo();
        set(diagnose, "baseDir", project.toFile());
        set(diagnose, "buildDir", build.toFile());
        set(diagnose, "sourceRoot", source.toFile());
        set(diagnose, "logFile", log.toFile());
        diagnose.execute();
        String diagnosis = Files.readString(build.resolve("m3-smart/diagnose.json"));
        require(diagnosis.contains("cannot-find-symbol"), "diagnosis classification");
        require(!diagnosis.contains("ADAPTER_TOKEN"), "log content leakage");

        Path cache = Files.createDirectory(project.resolve("cache"));
        byte[] bytes = "approved fixture".getBytes(StandardCharsets.UTF_8);
        String digest = Hashes.sha256(bytes);
        Path manifest = project.resolve("m3-artifacts.tsv");
        Files.writeString(manifest,
                "id\tprofile\tkind\turl\tsha256\tmaxBytes\tlicenseApproved\n"
                        + "smoke\tlocal\tnative-bin\thttps://downloads.example.org/smoke.bin\t"
                        + digest + "\t1000\ttrue\n");
        Files.write(cache.resolve(digest + ".bin"), bytes);
        ProvisionMojo provision = new ProvisionMojo();
        set(provision, "baseDir", project.toFile());
        set(provision, "buildDir", build.toFile());
        set(provision, "manifest", manifest.toFile());
        set(provision, "profile", "local");
        set(provision, "cacheDir", cache.toFile());
        set(provision, "approveProvision", true);
        set(provision, "offline", true);
        provision.execute();
        String receipt = Files.readString(build.resolve("m3-smart/provision.json"));
        require(receipt.contains(digest) && receipt.contains("\"profile\": \"local\""),
                "offline provision receipt");
        require(Files.isRegularFile(cache.resolve(digest + ".bin")), "cache reused unchanged");
        BuildHookMojo hook = new BuildHookMojo();
        set(hook, "baseDir", project.toFile());
        set(hook, "buildDir", build.toFile());
        set(hook, "phase", "post-build");
        set(hook, "buildOutcome", "failure");
        set(hook, "logFile", log.toFile());
        set(hook, "sourceRoot", source.toFile());
        set(hook, "llmBackend", "none");
        set(hook, "llmTimeoutSeconds", 120);
        hook.execute();
        String hookReport = Files.readString(build.resolve("m3-smart/post-build.json"));
        require(hookReport.contains("\"buildOutcome\":\"failure\""), "hook must retain observed Maven outcome");
        require(!hookReport.contains("ADAPTER_TOKEN"), "hook must not serialize raw log");
        set(hook, "requireAtomPattern", true);
        try {
            hook.execute();
            throw new AssertionError("required Java checkpoint manifest was not forwarded");
        } catch (org.apache.maven.plugin.MojoExecutionException expected) {
            require(Files.readString(build.resolve("m3-smart/post-build.json")).contains("\"atomPatternError\":\"manifest-required\""),
                    "Maven required-evidence parameter reaches the controller");
        }
        set(hook, "atomPatternManifest", project.resolve("absent-checkpoints.tsv").toFile());
        try {
            hook.execute();
            throw new AssertionError("selected manifest path was not forwarded");
        } catch (org.apache.maven.plugin.MojoExecutionException expected) {
            require(Files.readString(build.resolve("m3-smart/post-build.json")).contains("\"atomPatternError\":\"unreadable-evidence\""),
                    "Maven manifest-path parameter reaches the controller");
        }
        set(hook, "requireAtomPattern", false);
        set(hook, "atomPatternManifest", null);
        int count = 6;
        String fixture = System.getProperty("m3.smart.fixtureJar");
        if (fixture != null) {
            Path browser = Files.writeString(project.resolve("browser fixture"), "not an executable");
            for (Object mojo : java.util.List.of(plan, diagnose, hook)) {
                set(mojo, "llmBackend", "chrome");
                set(mojo, "chromeLlmJar", Path.of(fixture).toFile());
                set(mojo, "chromeBin", browser.toFile());
                set(mojo, "chromeProfile", project.resolve("chrome profile").toFile());
                set(mojo, "chromeHeadless", true);
                set(mojo, "chromeDownload", false);
                set(mojo, "llmTimeoutSeconds", 7);
            }
            plan.execute();
            diagnose.execute();
            hook.execute();
            for (String name : java.util.List.of("plan.json", "diagnose.json", "post-build.json")) {
                require(Files.readString(build.resolve("m3-smart").resolve(name)).contains("backend=chrome headless=true download=false timeout=7"),
                        "Maven adapter must select the real Chrome protocol arguments: " + name);
                count++;
            }
        }
        System.out.println("M3 SMART ADAPTER TESTS PASS: " + count);
    }
}
