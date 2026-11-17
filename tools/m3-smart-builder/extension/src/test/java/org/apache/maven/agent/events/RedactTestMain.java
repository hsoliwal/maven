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

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.LongStream;

/**
 * Redaction keeps its results and stays linear: credentials, colours and the root path are removed, and adversarial
 * 1 MB messages (one long token, scheme-like runs, userinfo without '@') are cleaned within a time bound. The bound is
 * generous for slow machines; the quadratic regex this replaces needed 43 s for 40 000 characters.
 */
public final class RedactTestMain {
    /** Time bound of one clean() call on a 1 MB message. */
    static final long BOUND_MILLIS = 250;
    /** Time bound of one secrets() call on a whole 1 MB message, without the clipping clean() does first. */
    static final long UNCLIPPED_BOUND_MILLIS = 10_000;
    private static final int MB = 1 << 20;
    private static final Path ROOT = Path.of("r").toAbsolutePath();

    private RedactTestMain() {}

    public static void main(String[] args) {
        results();
        clipsFirst();
        adversarial().forEach(RedactTestMain::bounded);
        Check.done("RedactTestMain");
    }

    private static void results() {
        Check.equal("clone https://***@host/x failed", Redact.secrets("clone https://user:pw@host/x failed"), "creds");
        Check.equal("jdbc:mysql://***@db", Redact.secrets("jdbc:mysql://u:p@db"), "nested scheme");
        Check.equal("git+ssh://***@h", Redact.secrets("git+ssh://git@h"), "scheme with +");
        Check.equal("mail a@b and https://h/x@y", Redact.secrets("mail a@b and https://h/x@y"), "no userinfo");
        Check.equal("red", Redact.secrets("\u001B[31mred\u001B[0m"), "colours");
        String forward = ROOT.toString().replace('\\', '/');
        Check.equal("a/B.java:3", Redact.clean(forward + "/a/B.java:3", ROOT, 300), "root path");
        String token = "https://oauth2:" + "t".repeat(3000) + "@gitlab.example/x";
        Check.equal("https://***@gitlab.example/x", Redact.secrets(token), "long token");
    }

    /** A long message is clipped before it is redacted, and a credential near the start is still removed. */
    private static void clipsFirst() {
        String message = "see https://u:p@h/x " + "L".repeat(MB);
        String cleaned = Redact.clean(message, ROOT, 300);
        Check.that(cleaned.startsWith("see https://***@h/x LLL") && cleaned.length() <= 300, "clipped " + cleaned.length());
        Check.that(Redact.firstLine("a\n" + "b".repeat(MB), ROOT, 300).equals("a"), "first line");
    }

    private static Map<String, String> adversarial() {
        return Map.of("one token", "L".repeat(MB), "scheme runs", "a://".repeat(MB / 4),
                "userinfo without @", "https://" + "u".repeat(MB), "colons", "a:".repeat(MB / 2),
                "at signs", "@".repeat(MB), "escape runs", ("\u001B[" + "1;".repeat(20)).repeat(MB / 42),
                "words", "abc+def.ghi-jkl ".repeat(MB / 16));
    }

    /**
     * clean(), what the event spy runs, within {@link #BOUND_MILLIS} (fastest of three tries, so a loaded machine does
     * not fail it); secrets() on the whole unclipped megabyte within {@link #UNCLIPPED_BOUND_MILLIS}, which a linear
     * pattern meets by far and the old quadratic one (hours for a megabyte) never does.
     */
    private static void bounded(String name, String message) {
        long cleanMillis = LongStream.range(0, 3).map(i -> millis(() -> Redact.clean(message, ROOT, 10_000))).min()
                .orElseThrow();
        long secretsMillis = millis(() -> Redact.secrets(message));
        Check.that(cleanMillis < BOUND_MILLIS, name + ": clean " + cleanMillis + " ms (bound " + BOUND_MILLIS + " ms)");
        Check.that(secretsMillis < UNCLIPPED_BOUND_MILLIS, name + ": secrets " + secretsMillis + " ms");
        System.out.println("RedactTestMain " + name + " (" + message.length() + " chars): clean " + cleanMillis
                + " ms, secrets unclipped " + secretsMillis + " ms");
    }

    private static long millis(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
