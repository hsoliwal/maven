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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;

/**
 * Structured build events for agents: with {@code -Dmaven.agent=true} (or {@code -Dmaven.events.file=...}) the
 * build writes {@code target/agent/build-events.jsonl}, and in agent mode {@code failure.json}, {@code failure.md}
 * and {@code summary.txt}, printing the summary on standard output (also under {@code -q}). Without either property
 * it records nothing. It only dispatches: repository events are dropped at once, lifecycle events go to the
 * {@link BuildRecorder}, the session end or the execution result closes the record. A failure inside the spy turns
 * it off for the rest of the build and never fails the build.
 */
@Named("build-events")
@Singleton
public final class BuildEventSpy extends AbstractEventSpy {
    private static final System.Logger LOG = System.getLogger(BuildEventSpy.class.getName());

    private BuildRecorder recorder;
    private boolean off;

    @Override
    public synchronized void onEvent(Object event) {
        if (event instanceof MavenExecutionRequest) { // a new build, also in a long-lived container (mvnd)
            off = false;
            recorder = null;
        }
        if (off || !ours(event)) {
            return;
        }
        try {
            dispatch(event);
        } catch (IOException | RuntimeException | LinkageError failure) {
            off = true;
            LOG.log(System.Logger.Level.WARNING, "Build events stopped for this build: " + failure);
            discard();
        }
    }

    /** Lifecycle events, the request and the result; repository and other events are dropped. */
    private static boolean ours(Object event) {
        return event instanceof ExecutionEvent || event instanceof MavenExecutionRequest
                || event instanceof MavenExecutionResult;
    }

    private void dispatch(Object event) throws IOException {
        if (event instanceof MavenExecutionRequest) {
            start((MavenExecutionRequest) event);
        } else if (event instanceof ExecutionEvent) {
            ExecutionEvent execution = (ExecutionEvent) event;
            start(execution.getSession().getRequest());
            if (recorder != null && execution.getType() == ExecutionEvent.Type.SessionEnded) {
                recorder.finish(execution.getSession().getResult().getExceptions());
            } else if (recorder != null) {
                recorder.handle(execution);
            }
        } else if (recorder != null) {
            recorder.finish(((MavenExecutionResult) event).getExceptions());
        }
    }

    /** Reads the settings once, from the first request seen; inactive settings turn the spy off. */
    private void start(MavenExecutionRequest request) throws IOException {
        if (recorder != null || off) {
            return;
        }
        AgentSettings settings = AgentSettings.of(request.getUserProperties(), request.getSystemProperties(), root(request));
        if (!settings.events()) {
            off = true;
            return;
        }
        SessionReport.removePrevious(settings);
        EventWriter writer = EventWriter.open(settings.stdout() == AgentSettings.Stdout.EVENTS
                ? line -> System.out.println(line) : line -> {});
        recorder = new BuildRecorder(settings, writer, request);
    }

    /** The directory of the top-level POM, else the base directory, else the working directory. */
    static Path root(MavenExecutionRequest request) {
        return Optional.ofNullable(request.getPom()).map(File::getAbsoluteFile).map(File::getParentFile)
                .or(() -> Optional.ofNullable(request.getBaseDirectory()).map(File::new))
                .map(File::toPath).orElse(Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private void discard() {
        try {
            if (recorder != null && !recorder.finished()) {
                recorder.writer().discard();
            }
        } catch (IOException ignored) {
            LOG.log(System.Logger.Level.DEBUG, "staged events not removed", ignored);
        }
    }

    /** A build that ends without a session end or result (an aborted JVM shutdown path) leaves nothing staged. */
    @Override
    public synchronized void close() {
        discard();
    }
}
