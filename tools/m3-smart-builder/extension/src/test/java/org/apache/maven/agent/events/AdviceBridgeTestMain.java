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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The build hook's post-build report is read with a JSON string reader, not a backtracking pattern: a 32 KB candidate
 * full of escapes reads on a thread with a 256 KB stack (the old pattern overflowed a 1 MB stack at 10 000
 * characters), a longer one is cut to {@link AdviceBridge#MAX_TEXT_CHARS}, escapes are decoded, a key inside a string
 * value is no field, and a broken report is a failed advice.
 */
public final class AdviceBridgeTestMain {
    private static final long SMALL_STACK = 256 * 1024;

    private AdviceBridgeTestMain() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("advice-bridge");
        String text = "fix it\n".repeat(32 * 1024 / 7);
        FailureDigest.Advice advice = onSmallStack(() -> AdviceBridge.report(report(dir, "available", text)));
        Check.equal(java.util.List.of("available", text), java.util.List.of(advice.status(), advice.text()),
                "32 KB of escapes on a small stack");
        String big = onSmallStack(() -> AdviceBridge.report(report(dir, "available", "\"x\"\n".repeat(1 << 18)))).text();
        Check.that(big.length() <= AdviceBridge.MAX_TEXT_CHARS && big.startsWith("\"x\"\n"), "cut: " + big.length());
        Check.equal("a\"b\\cé\t", AdviceBridge.report(report(dir, "available", "a\"b\\cé\t")).text(), "escapes");
        Path nested = write(dir, "{\"note\":\"\\\"llmCandidate\\\":\\\"forged\\\"\",\"adviceStatus\":\"available\","
                + "\"llmCandidate\":\"real\"}");
        Check.equal("real", AdviceBridge.report(nested).text(), "a key inside a string value is no field");
        Check.equal("failed", AdviceBridge.report(write(dir, "{\"adviceStatus\":\"available\",\"llmCandidate\":\"open"))
                .status(), "an unterminated string is a failed report");
        Check.equal("none", AdviceBridge.report(report(dir, "disabled", "")).status(), "disabled gives none");
        Check.done("AdviceBridgeTestMain");
    }

    private static Path report(Path dir, String status, String candidate) throws java.io.IOException {
        return write(dir, "{\"phase\":\"post-build\",\"adviceStatus\":\"" + status + "\",\"llmCandidate\":"
                + JsonText.quote(candidate) + "}");
    }

    private static Path write(Path dir, String json) throws java.io.IOException {
        Path file = Files.createTempFile(dir, "post-build", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** Runs {@code work} on a thread with a {@value #SMALL_STACK}-byte stack; rethrows what it threw. */
    private static <T> T onSmallStack(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.call());
            } catch (Throwable t) { // StackOverflowError included: the check reports it
                thrown.set(t);
            }
        }, "small-stack", SMALL_STACK);
        thread.start();
        thread.join();
        Check.that(thrown.get() == null, "no error on a small stack: " + thrown.get());
        return result.get();
    }
}
