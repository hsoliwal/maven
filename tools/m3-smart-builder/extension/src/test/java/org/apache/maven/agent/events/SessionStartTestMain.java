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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;

/**
 * A new build makes the previous build's agent files go away before it runs, so a build that is killed (and never
 * reaches its session end) cannot leave an older failure digest, summary or event stream looking current; without
 * the properties nothing is touched. Staging itself is checked by {@code StagingTestMain}.
 */
public final class SessionStartTestMain {
    private static final List<String> OUTPUTS = List.of("target/agent/failure.json", "target/agent/failure.md",
            "target/agent/summary.txt", "target/agent/build-events.jsonl");

    private SessionStartTestMain() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("session-start");
        stale(root);
        start(root, new Properties());
        Check.that(OUTPUTS.stream().allMatch(file -> Files.exists(root.resolve(file))), "no property: untouched");
        start(root, agent());
        Check.that(OUTPUTS.stream().noneMatch(file -> Files.exists(root.resolve(file))), "agent mode: removed");
        eventsOnly(root);
        Check.done("SessionStartTestMain");
    }

    /** With only maven.events.file the stream is replaced, and the agent files (not written in that mode) stay. */
    private static void eventsOnly(Path root) throws IOException {
        stale(root);
        Files.writeString(root.resolve("events.jsonl"), "{}\n");
        Properties user = new Properties();
        user.setProperty("maven.events.file", "events.jsonl");
        start(root, user);
        Check.that(!Files.exists(root.resolve("events.jsonl")), "events-only: stream removed");
        Check.that(Files.exists(root.resolve("target/agent/failure.json")), "events-only: agent files kept");
    }

    private static Properties agent() {
        Properties user = new Properties();
        user.setProperty("maven.agent", "true");
        return user;
    }

    private static void start(Path root, Properties user) {
        BuildEventSpy spy = new BuildEventSpy();
        spy.onEvent(new DefaultMavenExecutionRequest().setPom(root.resolve("pom.xml").toFile())
                .setUserProperties(user).setSystemProperties(new Properties()));
        spy.close();
    }

    private static void stale(Path root) throws IOException {
        Files.createDirectories(root.resolve("target/agent"));
        OUTPUTS.forEach(file -> {
            try {
                Files.writeString(root.resolve(file), "previous build\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
