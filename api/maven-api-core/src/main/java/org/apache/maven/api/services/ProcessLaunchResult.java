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
package org.apache.maven.api.services;

import java.time.Duration;
import java.util.List;

import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Immutable;
import org.apache.maven.api.annotations.Nonnull;

/**
 * Result of a process launched by {@link ProcessLauncher}.
 * <p>
 * Unlike {@code CommandLineUtils.executeCommandLine}, which throws {@code CommandLineTimeOutException},
 * an expired timeout is reported by {@link #isTimedOut()}: the process has then been destroyed and
 * {@link #getExitCode()} is the code it exited with after that.
 *
 * @since 4.2.0
 */
@Experimental
@Immutable
public interface ProcessLaunchResult extends Result<ProcessLaunchRequest> {

    /**
     * Gets the exit code of the process.
     *
     * @return the exit code
     */
    int getExitCode();

    /**
     * Tells whether the request's timeout expired before the process exited.
     *
     * @return true if the process was destroyed because of the timeout
     */
    boolean isTimedOut();

    /**
     * Gets the command line handed to {@link ProcessBuilder}.
     *
     * @return an unmodifiable command line
     */
    @Nonnull
    List<String> getCommandLine();

    /**
     * Gets the time from start to exit.
     *
     * @return the duration
     */
    @Nonnull
    Duration getDuration();

    /**
     * Tells whether the process exited with code 0 before any timeout.
     *
     * @return true on success
     */
    default boolean isSuccess() {
        return !isTimedOut() && getExitCode() == 0;
    }
}
