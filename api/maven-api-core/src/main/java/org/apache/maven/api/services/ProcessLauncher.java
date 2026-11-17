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

import org.apache.maven.api.Service;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;

/**
 * Launches external processes and forked JVMs on {@link ProcessBuilder}.
 * <p>
 * The service covers what plugins used to take from the {@code cli} packages of plexus-utils and
 * maven-shared-utils ({@code Commandline}, {@code CommandLineUtils}, the {@code Shell} classes and
 * {@code JavaTool}): per-shell argument quoting chosen through {@link OsService}, environment
 * (inherit, override, clear), working directory, standard input and output pumping, timeouts with
 * {@link Process#destroy()} followed by {@link Process#destroyForcibly()}, destroy on JVM shutdown,
 * exit-code results and Java tool lookup through {@link org.apache.maven.api.Toolchain#findTool(String)}.
 * Those libraries are not changed: plugins opt in to this service.
 * <p>
 * Example:
 * <pre>{@code
 * ProcessLauncher launcher = session.getService(ProcessLauncher.class);
 * ProcessLaunchResult result = launcher.execute(ProcessLaunchRequest.builder()
 *         .session(session)
 *         .tool("javadoc")
 *         .toolchain(toolchain)
 *         .arguments("-d", outputDirectory.toString())
 *         .outputConsumer(log::info)
 *         .errorConsumer(log::warn)
 *         .timeout(Duration.ofMinutes(10))
 *         .build());
 * }</pre>
 *
 * @since 4.2.0
 * @see ProcessLaunchRequest
 * @see ProcessLaunchResult
 */
@Experimental
public interface ProcessLauncher extends Service {

    /**
     * Starts the process described by the request and waits until it exits or its timeout expires.
     * Output and error lines go to the request's consumers; output without a consumer is discarded,
     * and standard input without a request input is closed.
     *
     * @param request the launch request
     * @return the result, with the exit code and whether the timeout expired
     * @throws ProcessLauncherException if the process cannot be started or a stream fails
     * @throws NullPointerException if {@code request} is null
     */
    @Nonnull
    ProcessLaunchResult execute(@Nonnull ProcessLaunchRequest request);

    /**
     * Starts the process described by the request and returns without waiting.
     * Output and error streams with a consumer are pumped to it; streams without one, and standard
     * input when the request has no input, stay available from {@link LaunchedProcess#process()}.
     *
     * @param request the launch request
     * @return a handle on the running process
     * @throws ProcessLauncherException if the process cannot be started
     * @throws NullPointerException if {@code request} is null
     */
    @Nonnull
    LaunchedProcess start(@Nonnull ProcessLaunchRequest request);

    /**
     * Returns the exact command line the launcher hands to {@link ProcessBuilder} for the request,
     * after tool lookup and shell wrapping.
     *
     * @param request the launch request
     * @return the command line, one element per process argument
     * @throws NullPointerException if {@code request} is null
     */
    @Nonnull
    List<String> commandLine(@Nonnull ProcessLaunchRequest request);

    /**
     * Returns the command line of the request joined by spaces, with every masked argument replaced
     * by {@code *****}, for logging.
     *
     * @param request the launch request
     * @return a printable command line that does not show masked arguments
     * @throws NullPointerException if {@code request} is null
     */
    @Nonnull
    String describe(@Nonnull ProcessLaunchRequest request);

    /**
     * Splits a command line into arguments: spaces separate arguments, single and double quotes group
     * them, and a backslash keeps the quote that follows it.
     * This is the tokenizer of maven-shared-utils {@code CommandLineUtils.translateCommandline}, which
     * surefire also uses for {@code argLine}.
     *
     * @param commandLine the command line, may be null
     * @return the arguments, empty when {@code commandLine} is null or empty
     * @throws ProcessLauncherException if the command line has unbalanced quotes
     */
    @Nonnull
    List<String> tokenize(@Nullable String commandLine);
}
