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
package org.apache.maven.impl.process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.LaunchedProcess;
import org.apache.maven.api.services.OsService;
import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchRequest.Shell;
import org.apache.maven.api.services.ProcessLaunchResult;
import org.apache.maven.api.services.ProcessLauncher;
import org.apache.maven.api.services.ProcessLauncherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * Default {@link ProcessLauncher} on {@link ProcessBuilder}, with the shell rules picked through {@link OsService}.
 * <p>
 * Through a shell, the executable gets the separators of the target OS, as {@code Shell.setExecutable} of
 * plexus-utils and maven-shared-utils does; without a shell, the command line goes to {@link ProcessBuilder} as
 * given, as surefire's own {@code Commandline.toProcessBuilder} does.
 *
 * @since 4.2.0
 */
@Named
@Singleton
public class DefaultProcessLauncher implements ProcessLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProcessLauncher.class);

    private final OsService osService;

    /**
     * Creates the launcher.
     *
     * @param osService the OS facts that choose the shell rules
     */
    @Inject
    public DefaultProcessLauncher(OsService osService) {
        this.osService = requireNonNull(osService, "osService cannot be null");
    }

    @Nonnull
    @Override
    public ProcessLaunchResult execute(@Nonnull ProcessLaunchRequest request) {
        return launch(request, true).await();
    }

    @Nonnull
    @Override
    public LaunchedProcess start(@Nonnull ProcessLaunchRequest request) {
        return launch(request, false);
    }

    @Nonnull
    @Override
    public List<String> commandLine(@Nonnull ProcessLaunchRequest request) {
        return render(requireNonNull(request, "request cannot be null"), request.getArguments());
    }

    @Nonnull
    @Override
    public String describe(@Nonnull ProcessLaunchRequest request) {
        return String.join(" ", render(requireNonNull(request, "request cannot be null"), maskedArguments(request)));
    }

    @Nonnull
    @Override
    public List<String> tokenize(@Nullable String commandLine) {
        return CommandLineTokenizer.tokenize(commandLine);
    }

    private DefaultLaunchedProcess launch(ProcessLaunchRequest request, boolean execute) {
        List<String> commandLine = commandLine(request);
        if (commandLine.isEmpty()) {
            throw new ProcessLauncherException("Nothing to execute: no executable, tool or argument.");
        }
        LOGGER.debug("Executing {}", describe(request));
        return DefaultLaunchedProcess.start(
                processBuilder(request, commandLine, execute), request, commandLine, execute);
    }

    /**
     * Configures a process builder for the request.
     *
     * @param request the request
     * @param commandLine the rendered command line
     * @param discardUnconsumed whether output without a consumer is discarded
     * @return the process builder
     */
    ProcessBuilder processBuilder(ProcessLaunchRequest request, List<String> commandLine, boolean discardUnconsumed) {
        ProcessBuilder builder = new ProcessBuilder(commandLine).redirectErrorStream(request.isRedirectErrorStream());
        request.getWorkingDirectory()
                .map(DefaultProcessLauncher::checkedDirectory)
                .ifPresent(builder::directory);
        applyEnvironment(builder.environment(), request);
        if (discardUnconsumed && request.getOutputConsumer().isEmpty()) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        if (discardUnconsumed && request.getErrorConsumer().isEmpty() && !request.isRedirectErrorStream()) {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return builder;
    }

    /**
     * Applies the request environment: inherited or cleared, then each variable set, or removed when null.
     *
     * @param environment the process builder environment, a copy of the running JVM's
     * @param request the request
     */
    static void applyEnvironment(Map<String, String> environment, ProcessLaunchRequest request) {
        if (!request.isInheritEnvironment()) {
            environment.clear();
        }
        request.getEnvironment().forEach((name, value) -> {
            if (value == null) {
                environment.remove(name);
            } else {
                environment.put(name, value);
            }
        });
    }

    /** The checks and messages of {@code Commandline.execute()}. */
    private static File checkedDirectory(Path directory) {
        if (!Files.exists(directory)) {
            throw new ProcessLauncherException("Working directory \"" + directory + "\" does not exist!");
        }
        if (!Files.isDirectory(directory)) {
            throw new ProcessLauncherException("Path \"" + directory + "\" does not specify a directory.");
        }
        return directory.toFile();
    }

    private List<String> render(ProcessLaunchRequest request, List<String> arguments) {
        Shell shell = request.getShell();
        Optional<String> directory =
                request.getWorkingDirectory().map(path -> path.toAbsolutePath().toString());
        return ShellCommandLines.render(shell, osService.isWindows(), executable(request, shell), arguments, directory);
    }

    private Optional<String> executable(ProcessLaunchRequest request, Shell shell) {
        Optional<String> executable = request.getExecutable()
                .filter(name -> !name.isEmpty())
                .or(() -> request.getTool().map(tool -> locate(tool, request)));
        return shell == Shell.NONE ? executable : executable.map(this::platformSeparators);
    }

    private String locate(String tool, ProcessLaunchRequest request) {
        return JavaToolLocator.locate(
                tool, request.getToolchain(), osService.isWindows(), System.getProperty("java.home"), System::getenv);
    }

    private String platformSeparators(String executable) {
        char separator = osService.isWindows() ? '\\' : '/';
        return executable.replace('/', separator).replace('\\', separator);
    }

    private static List<String> maskedArguments(ProcessLaunchRequest request) {
        List<String> arguments = new ArrayList<>(request.getArguments());
        request.getMaskedArguments().forEach(index -> arguments.set(index, ProcessLaunchRequest.MASK));
        return arguments;
    }
}
