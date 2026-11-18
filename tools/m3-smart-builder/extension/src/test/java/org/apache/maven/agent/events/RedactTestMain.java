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
        prefixes();
        pairs();
        clipsFirst();
        redactsBeforeClip();
        adversarial().forEach(RedactTestMain::bounded);
        Check.done("RedactTestMain");
    }

    /** Whatever precedes the scheme, a '/' in the password and a long userinfo: the password never survives. */
    private static void prefixes() {
        String url = "https://alice:S3cretPw@127.0.0.1:9/repo";
        java.util.List.of("1", "%22", "-", ".", "+", "(", "git+", "x".repeat(70), "private (").forEach(prefix -> Check
                .equal(prefix + "https://***@127.0.0.1:9/repo", Redact.secrets(prefix + url), "prefix " + prefix));
        Check.equal("https://***@host/x", Redact.secrets("https://alice:pa/ss@host/x"), "'/' in the password");
        Check.equal("https://***@host/x", Redact.secrets("https://alice:p@ss@host/x"), "'@' in the password");
        Check.equal("https://***@h/r", Redact.secrets("https://" + "u".repeat(9000) + ":p@h/r"), "9000 characters");
        Check.equal("central::default::https://***@h1,https://***@h2", Redact.secrets(
                "central::default::https://a:p1@h1,https://b:p2@h2"), "two credentials in one argument");
        Check.equal("see https://registry.example/@scope/pkg", Redact.secrets("see https://registry.example/@scope/pkg"),
                "an '@' in a path is no userinfo");
        Check.equal("a://b/https://***@h", Redact.secrets("a://b/https://u:p@h"), "a credential URL after another");
    }

    /** Credentials outside URLs: name=value pairs and Authorization headers. */
    private static void pairs() {
        Check.equal("jdbc:postgresql://db/x?user=sa&password=***", Redact.secrets(
                "jdbc:postgresql://db/x?user=sa&password=hunter3"), "query password");
        Check.equal("jdbc:h2:tcp://db/x;USER=sa;PASSWORD=***", Redact.secrets("jdbc:h2:tcp://db/x;USER=sa;PASSWORD=hunter3"),
                "upper-case pair");
        Check.equal("-Dsonar.login=*** -Dapi_key=*** token=***, secret=***", Redact.secrets(
                "-Dsonar.login=squ_x1 -Dapi_key=k9 token=t0, secret=s3"), "named pairs");
        Check.equal("Authorization: Bearer ***", Redact.secrets("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig"),
                "bearer header");
        Check.equal("Authorization: Basic ***", Redact.secrets("Authorization: Basic dXNlcjpwYXNzd29yZA=="), "basic");
        Check.equal("sent bearer *** to h", Redact.secrets("sent bearer abcdefgh12345678 to h"), "a bare bearer token");
        Check.equal("password=***", Redact.secrets(Redact.secrets("password=hunter3")), "redaction is idempotent");
        Check.equal("tokens=5 and passwordless=true", Redact.secrets("tokens=5 and passwordless=true"), "no pair");
    }

    /**
     * Redaction runs before clipping and shrinking: a credential across the pre-clip cut, behind blank lines, leading
     * white space or colour codes, is removed or cut away, never kept in part.
     */
    private static void redactsBeforeClip() {
        int head = 1200 * Redact.PRE_CLIP_FACTOR + Redact.PRE_CLIP_SLACK;
        String url = "https://alice:S3cretPw@host/x";
        java.util.stream.IntStream.of(25, 22, 18, 10).forEach(back -> {
            String blank = "Failed:" + "\n".repeat(head - back - 7) + url;
            Check.that(!Redact.message(blank, Redact.Places.of(ROOT), 1200).contains("S3c"), "blank lines " + back);
            Check.that(!Redact.clean(" ".repeat(head - back) + url, Redact.Places.of(ROOT), 1200).contains("S3c"),
                    "leading white space " + back);
            String ansi = "\u001B[0m".repeat((head - back) / 4) + "x".repeat((head - back) % 4) + url;
            Check.that(!Redact.message(ansi, Redact.Places.of(ROOT), 1200).contains("S3c"), "colour codes " + back);
        });
    }

    private static void results() {
        Check.equal("clone https://***@host/x failed", Redact.secrets("clone https://user:pw@host/x failed"), "creds");
        Check.equal("jdbc:mysql://***@db", Redact.secrets("jdbc:mysql://u:p@db"), "nested scheme");
        Check.equal("git+ssh://***@h", Redact.secrets("git+ssh://git@h"), "scheme with +");
        Check.equal("mail a@b and https://h/x@y", Redact.secrets("mail a@b and https://h/x@y"), "no userinfo");
        Check.equal("red", Redact.secrets("\u001B[31mred\u001B[0m"), "colours");
        String forward = ROOT.toString().replace('\\', '/');
        Check.equal("a/B.java:3", Redact.clean(forward + "/a/B.java:3", Redact.Places.of(ROOT), 300), "root path");
        String token = "https://oauth2:" + "t".repeat(3000) + "@gitlab.example/x";
        Check.equal("https://***@gitlab.example/x", Redact.secrets(token), "long token");
    }

    /** A long message is clipped before it is redacted, and a credential near the start is still removed. */
    private static void clipsFirst() {
        String message = "see https://u:p@h/x " + "L".repeat(MB);
        String cleaned = Redact.clean(message, Redact.Places.of(ROOT), 300);
        Check.that(cleaned.startsWith("see https://***@h/x LLL") && cleaned.length() <= 300, "clipped " + cleaned.length());
        String whole = Redact.message("a\n \n\tb  \n" + "c".repeat(MB), Redact.Places.of(ROOT), 300);
        Check.that(whole.startsWith("a\n\tb\nccc") && whole.length() <= 300, "every non-blank line, bounded: " + whole);
    }

    private static Map<String, String> adversarial() {
        return Map.ofEntries(Map.entry("one token", "L".repeat(MB)), Map.entry("scheme runs", "a://".repeat(MB / 4)),
                Map.entry("userinfo without @", "https://" + "u".repeat(MB)), Map.entry("colons", "a:".repeat(MB / 2)),
                Map.entry("at signs", "@".repeat(MB)), Map.entry("escape runs", ("\u001B[" + "1;".repeat(20))
                        .repeat(MB / 42)), Map.entry("words", "abc+def.ghi-jkl ".repeat(MB / 16)),
                Map.entry("schemes and at signs", "://x@".repeat(MB / 5)), Map.entry("slashes", "://a:b/".repeat(MB / 7)),
                Map.entry("pairs", "password=".repeat(MB / 9)), Map.entry("bearers", "Bearer ".repeat(MB / 7)),
                Map.entry("headers", "authorization: ".repeat(MB / 15)));
    }

    /**
     * clean(), what the event spy runs, within {@link #BOUND_MILLIS} (fastest of three tries, so a loaded machine does
     * not fail it); secrets() on the whole unclipped megabyte within {@link #UNCLIPPED_BOUND_MILLIS}, which a linear
     * pattern meets by far and the old quadratic one (hours for a megabyte) never does.
     */
    private static void bounded(String name, String message) {
        long cleanMillis = LongStream.range(0, 3).map(i -> millis(() -> Redact.clean(message, Redact.Places.of(ROOT),
                10_000))).min()
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
