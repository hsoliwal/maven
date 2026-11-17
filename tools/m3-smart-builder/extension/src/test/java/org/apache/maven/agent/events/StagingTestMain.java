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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;

/**
 * Each build stages its event stream in a new private directory (owner-only on POSIX), so no shared or pre-created
 * path can stop or forge another user's events: the old fixed path taken by a file changes nothing, two builds get
 * two directories, publishing or discarding removes the build's directory, and a sweep removes only this user's
 * directories of killed builds older than a day, never a file or a directory it did not make.
 */
public final class StagingTestMain {
    private StagingTestMain() {}

    public static void main(String[] args) throws IOException {
        squatted();
        privateDirectory();
        stale();
        Check.done("StagingTestMain");
    }

    /** {@code maven-build-events} in the temporary directory is a file (a squatter, another user): events still come. */
    private static void squatted() throws IOException {
        Path shared = Files.createTempDirectory("shared-tmp");
        Files.writeString(shared.resolve("maven-build-events"), "squatter\n");
        String saved = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", shared.toString());
        try {
            Path root = Files.createTempDirectory("squat-root");
            Properties user = new Properties();
            user.setProperty("maven.agent", "true");
            user.setProperty("maven.events.stdout", "none");
            BuildEventSpy spy = new BuildEventSpy();
            spy.onEvent(new DefaultMavenExecutionRequest().setPom(root.resolve("pom.xml").toFile())
                    .setUserProperties(user).setSystemProperties(new Properties()));
            spy.onEvent(new DefaultMavenExecutionResult());
            Check.that(Files.isRegularFile(root.resolve("target/agent/build-events.jsonl"))
                    && Files.isRegularFile(root.resolve("target/agent/summary.txt")), "published despite the squat");
        } finally {
            System.setProperty("java.io.tmpdir", saved);
        }
        try (EventWriter writer = EventWriter.open(shared, line -> {})) {
            Check.that(writer.stagedFile().getParent().getParent().equals(shared), "a new directory beside the squat");
        }
    }

    private static void privateDirectory() throws IOException {
        Path base = Files.createTempDirectory("staging-base");
        EventWriter a = EventWriter.open(base, line -> {});
        EventWriter b = EventWriter.open(base, line -> {});
        Path dirA = a.stagedFile().getParent();
        Check.that(!dirA.equals(b.stagedFile().getParent()) && dirA.getParent().equals(base), "one directory per build");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Check.equal(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dirA), "owner only");
        } else {
            System.out.println("StagingTestMain: owner-only POSIX permissions NOT RUN (no POSIX file system here)");
        }
        a.publish(base.resolve("events.jsonl"));
        b.discard();
        Check.that(!Files.exists(dirA) && !Files.exists(b.stagedFile().getParent()) && Files.isRegularFile(base
                .resolve("events.jsonl")), "publishing and discarding remove the build's directory");
    }

    /** Killed builds' directories older than a day go; a fresh one and anything the writer did not make stay. */
    private static void stale() throws IOException {
        Path base = Files.createTempDirectory("staging-stale");
        Path old = killed(base, Duration.ofDays(2));
        Path fresh = killed(base, Duration.ZERO);
        Path file = base.resolve(EventWriter.STAGING_PREFIX + "file");
        Files.writeString(file, "not a directory\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path other = Files.createDirectories(base.resolve("other-dir"));
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        EventWriter.open(base, line -> {}).discard();
        Check.that(!Files.exists(old), "stale directory of a killed build removed");
        Check.that(Files.exists(fresh) && Files.exists(file) && Files.exists(other), "fresh, file and other kept");
    }

    private static Path killed(Path base, Duration age) throws IOException {
        Path dir = Files.createTempDirectory(base, EventWriter.STAGING_PREFIX);
        Files.writeString(dir.resolve(EventWriter.STAGED_NAME), "{}\n");
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(age)));
        return dir;
    }
}
