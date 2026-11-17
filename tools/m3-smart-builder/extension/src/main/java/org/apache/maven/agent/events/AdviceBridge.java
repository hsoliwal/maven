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
package org.apache.maven.agent.events;

import io.synexia.maven.smart.extension.AdviceLifecycleParticipant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Advice for a failed build from the existing advice owner, the build hook's post-build phase, run once for the
 * first failed project when the build asked for advice ({@code chromellm.advice=true}). The hook needs Java 21 and
 * its plugin in the local repository; otherwise the digest records {@code skipped}. The text is a candidate, never
 * trusted, and the hook never changes the build result.
 */
final class AdviceBridge {
    /** Extra seconds over the hook's own provider timeout. */
    static final int GRACE_SECONDS = 30;
    /** Characters of advice text kept; the hook's own adviser caps a candidate at 32 KB too. */
    static final int MAX_TEXT_CHARS = 32 * 1024;
    /** Size of a report that is read at all. */
    static final long MAX_REPORT_BYTES = 4L * 1024 * 1024;

    private AdviceBridge() {}

    /** The advice for a failure of the project in {@code projectDir}. */
    static FailureDigest.Advice advise(Properties user, Properties system, Path localRepository, Path projectDir,
            Optional<Path> log) {
        if (!"true".equals(user.getProperty(AdviceLifecycleParticipant.PROPERTY,
                system.getProperty(AdviceLifecycleParticipant.PROPERTY)))) {
            return FailureDigest.Advice.of("not-requested");
        }
        Path jar = AdviceLifecycleParticipant.pluginJar(localRepository);
        if (Runtime.version().feature() < 21 || !Files.isRegularFile(jar)) {
            return new FailureDigest.Advice("skipped", Runtime.version().feature() < 21
                    ? "build hook needs Maven on Java 21 or newer" : "build hook plugin not in the local repository", "");
        }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toString(), AdviceLifecycleParticipant.HOOK_MAIN));
        command.addAll(AdviceLifecycleParticipant.failureHookArguments(user, system, projectDir, log));
        return run(command, projectDir, AdviceLifecycleParticipant.hookTimeoutSeconds(user, system) + GRACE_SECONDS);
    }

    private static FailureDigest.Advice run(List<String> command, Path projectDir, int seconds) {
        try {
            Process process = new ProcessBuilder(command).directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new FailureDigest.Advice("failed", "build hook timed out", "");
            }
            return report(projectDir.resolve("target").resolve("m3-smart").resolve("post-build.json"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new FailureDigest.Advice("failed", "interrupted", "");
        } catch (IOException failed) {
            return new FailureDigest.Advice("failed", failed.getClass().getSimpleName(), "");
        }
    }

    /**
     * Status and candidate of the hook's report: disabled gives none, available with text gives available. Read with
     * {@link JsonText#stringField}, linear and without recursion; a broken or oversized report is a failed advice.
     */
    static FailureDigest.Advice report(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_REPORT_BYTES) {
            return new FailureDigest.Advice("failed", "build hook wrote no report of at most " + MAX_REPORT_BYTES
                    + " bytes", "");
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            String hook = JsonText.stringField(json, "adviceStatus", 40).orElse("");
            String text = JsonText.stringField(json, "llmCandidate", MAX_TEXT_CHARS).orElse("");
            String result = "disabled".equals(hook) ? "none" : "available".equals(hook) && !text.isBlank()
                    ? "available" : "available".equals(hook) ? "none" : "failed";
            return new FailureDigest.Advice(result, "build hook post-build (" + hook + ")", text);
        } catch (IllegalArgumentException | StackOverflowError broken) {
            return new FailureDigest.Advice("failed", "build hook report is not valid JSON", "");
        }
    }
}
