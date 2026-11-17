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
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * The bounded {@code maven.agent.*} and {@code maven.events.*} properties of one build; user properties win over
 * system properties, values outside their range fall back to the range end, unparsable values to the default.
 *
 * @param agent {@code maven.agent}: write the digest and summary next to the events
 * @param events whether the event stream is written at all ({@code maven.agent} or {@code maven.events.file})
 * @param eventsFile {@code maven.events.file}, default {@code <agentDir>/build-events.jsonl}
 * @param stdout {@code maven.events.stdout}: none, summary (default with {@code maven.agent}) or events
 * @param eventsLog {@code maven.events.log}: the {@code -l} log of this build, read at the end for warnings
 * @param agentDir {@code maven.agent.dir}, default {@code <root>/target/agent}
 * @param allTests {@code maven.agent.tests=all}: a test event for every test, not only the failed ones
 * @param printLimit {@code maven.agent.printLimit}: diagnostics and tests per failure in digest and summary
 * @param maxRepeat {@code maven.agent.maxRepeat}: same-rule diagnostics written before they collapse
 * @param stackLines {@code maven.agent.stackLines}: trimmed stack trace lines per test
 * @param context {@code maven.agent.context}: source lines before and after a diagnostic (at most 10)
 * @param messageChars {@code maven.agent.messageChars}: characters per message
 * @param summaryBytes {@code maven.agent.summaryBytes}: size of the printed summary
 * @param root the build's root directory; every written path is relative to it
 * @param localRepository {@code maven.repo.local}, else {@code <home>/.m2/repository}: written {@code <local-repo>}
 * @param home the {@code user.home} of the build: written {@code ~}
 */
public record AgentSettings(
        boolean agent,
        boolean events,
        Path eventsFile,
        Stdout stdout,
        Optional<Path> eventsLog,
        Path agentDir,
        boolean allTests,
        int printLimit,
        int maxRepeat,
        int stackLines,
        int context,
        int messageChars,
        int summaryBytes,
        Path root,
        Optional<Path> localRepository,
        Optional<Path> home) {

    /** What the build prints on standard output. */
    public enum Stdout {
        NONE,
        SUMMARY,
        EVENTS
    }

    /** Reads the settings; {@code root} is the directory of the top-level POM. */
    public static AgentSettings of(Properties user, Properties system, Path root) {
        Lookup lookup = new Lookup(user, system);
        boolean agent = Boolean.parseBoolean(lookup.get("maven.agent", "false"));
        Optional<String> file = lookup.find("maven.events.file");
        Path agentDir = root.resolve(lookup.get("maven.agent.dir", "target/agent")).normalize();
        Path eventsFile = file.map(name -> root.resolve(name).normalize())
                .orElse(agentDir.resolve("build-events.jsonl"));
        Stdout stdout = stdout(lookup.get("maven.events.stdout", agent ? "summary" : "none"));
        Optional<Path> home = path(lookup.find("user.home"), root);
        return new AgentSettings(agent, agent || file.isPresent(), eventsFile, stdout,
                lookup.find("maven.events.log").map(name -> root.resolve(name).normalize()), agentDir,
                "all".equals(lookup.get("maven.agent.tests", "failed")), lookup.bounded("maven.agent.printLimit", 20, 0, 1000),
                lookup.bounded("maven.agent.maxRepeat", 3, 1, 1000), lookup.bounded("maven.agent.stackLines", 12, 1, 200),
                lookup.bounded("maven.agent.context", 2, 0, 10), lookup.bounded("maven.agent.messageChars", 300, 40, 10000),
                lookup.bounded("maven.agent.summaryBytes", 4000, 200, 1_000_000), root,
                path(lookup.find("maven.repo.local"), root).or(() -> home.map(h -> h.resolve(".m2/repository"))), home);
    }

    /** An absolute, normalized path of {@code value} (relative to {@code root}); empty when it is no path. */
    private static Optional<Path> path(Optional<String> value, Path root) {
        try {
            return value.map(text -> root.resolve(text).toAbsolutePath().normalize());
        } catch (java.nio.file.InvalidPathException notAPath) {
            return Optional.empty();
        }
    }

    /** The places every message is redacted for: root, local repository, home. */
    Redact.Places places() {
        return new Redact.Places(root, localRepository, home);
    }

    /** A failure message (digest, {@code mojo-end} error) keeps up to this many times {@link #messageChars()}. */
    static final int FAILURE_MESSAGE_FACTOR = 4;

    /** Characters of a whole failure message. */
    int failureMessageChars() {
        return messageChars * FAILURE_MESSAGE_FACTOR;
    }

    private static Stdout stdout(String value) {
        try {
            return Stdout.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Stdout.NONE;
        }
    }

    /** {@code path} relative to the root with '/' separators; a path outside the root stays absolute. */
    public String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        String text = absolute.startsWith(base) ? base.relativize(absolute).toString() : absolute.toString();
        text = text.replace('\\', '/');
        return text.isEmpty() ? "." : text;
    }

    private record Lookup(Properties user, Properties system) {
        Optional<String> find(String key) {
            String value = user.getProperty(key, system.getProperty(key));
            return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty());
        }

        String get(String key, String fallback) {
            return find(key).orElse(fallback);
        }

        int bounded(String key, int fallback, int min, int max) {
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(get(key, String.valueOf(fallback)))));
            } catch (NumberFormatException unparsable) {
                return fallback;
            }
        }
    }
}
