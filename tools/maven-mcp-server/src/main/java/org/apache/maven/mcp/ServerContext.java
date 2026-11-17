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
package org.apache.maven.mcp;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What the tools share: the fixed settings, the Maven launcher, the run store, and the lock that keeps Maven runs on
 * this project root one at a time.
 */
final class ServerContext {

    /** Work that may throw {@link IOException}. */
    @FunctionalInterface
    interface IoWork<T> {
        T run() throws IOException;
    }

    private final ServerSettings settings;
    private final MavenLauncher launcher;
    private final RunStore store;
    private final ReentrantLock mavenLock = new ReentrantLock();

    ServerContext(ServerSettings settings, MavenLauncher launcher, RunStore store) {
        this.settings = settings;
        this.launcher = launcher;
        this.store = store;
    }

    ServerSettings settings() {
        return settings;
    }

    MavenLauncher launcher() {
        return launcher;
    }

    RunStore store() {
        return store;
    }

    /** Runs {@code work} while no other Maven run is active on this root; refuses at once when one is. */
    <T> T exclusive(IoWork<T> work) throws IOException {
        if (!mavenLock.tryLock()) {
            throw new IllegalStateException("another Maven run is still active for " + settings.root());
        }
        try {
            return work.run();
        } finally {
            mavenLock.unlock();
        }
    }
}
