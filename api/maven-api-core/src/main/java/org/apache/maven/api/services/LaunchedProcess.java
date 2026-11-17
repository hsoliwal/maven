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

import java.util.List;

import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;

/**
 * A process started by {@link ProcessLauncher#start(ProcessLaunchRequest)}.
 * Closing the handle destroys the process if it is still running and removes its shutdown hook.
 *
 * @since 4.2.0
 */
@Experimental
public interface LaunchedProcess extends AutoCloseable {

    /**
     * Gets the underlying process, whose streams without a request consumer or input are open to the caller.
     *
     * @return the process
     */
    @Nonnull
    Process process();

    /**
     * Gets the command line handed to {@link ProcessBuilder}.
     *
     * @return an unmodifiable command line
     */
    @Nonnull
    List<String> commandLine();

    /**
     * Waits until the process exits or the request's timeout, counted from the start, expires; on timeout the
     * process is {@linkplain #destroy() destroyed}. Then waits for the stream pumps to finish.
     *
     * @return the result
     * @throws ProcessLauncherException if a stream pump failed or the current thread is interrupted
     */
    @Nonnull
    ProcessLaunchResult await();

    /**
     * Destroys the process and its descendants, then destroys them forcibly if they are still alive after the
     * request's destroy grace period.
     */
    void destroy();

    /**
     * Destroys the process if it is still alive and removes its shutdown hook.
     */
    @Override
    void close();
}
