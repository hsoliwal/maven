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

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.NotThreadSafe;
import org.apache.maven.api.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Request for {@link ProcessLauncher}: what to run, how to wrap it in a shell, its environment and
 * working directory, where its streams go, and how long it may run.
 * <p>
 * The program is the {@linkplain #getExecutable() executable}, or else the
 * {@linkplain #getTool() Java tool} found through the {@linkplain #getToolchain() toolchain}, the
 * running JVM's {@code java.home}, {@code JDK_HOME} or {@code JAVA_HOME}, in the order of
 * maven-shared-utils {@code AbstractJavaTool}. Without either, the first argument is the program.
 * <p>
 * A request holds the input stream and consumers it is given; they are used by one launch.
 *
 * @since 4.2.0
 */
@Experimental
public interface ProcessLaunchRequest extends Request<ProtoSession> {

    /** The text shown by {@link ProcessLauncher#describe(ProcessLaunchRequest)} for a masked argument. */
    String MASK = "*****";

    /** The default {@linkplain #getDestroyGracePeriod() grace period} between destroy and forced destroy. */
    Duration DEFAULT_DESTROY_GRACE_PERIOD = Duration.ofSeconds(5);

    /**
     * How the command line is handed to the operating system.
     */
    enum Shell {
        /** The program and its arguments go to {@link ProcessBuilder} directly, without a shell. */
        NONE,
        /** {@link #CMD} when {@link OsService#isWindows()}, otherwise {@link #SH}, as {@code Commandline} does. */
        DEFAULT,
        /** {@code cmd.exe /X /C "..."} with the quoting of maven-shared-utils {@code CmdShell}. */
        CMD,
        /** {@code powershell.exe} ({@code pwsh} off Windows) {@code -NoProfile -NonInteractive -Command & '...'}. */
        POWERSHELL,
        /** {@code /bin/sh -c '...'} with the quoting of maven-shared-utils {@code BourneShell}. */
        SH,
        /** {@code /bin/bash -c '...'} with the quoting of {@link #SH}. */
        BASH
    }

    /**
     * Gets the program to run, as a path or a name looked up on {@code PATH}.
     *
     * @return the executable, or empty to use the {@linkplain #getTool() tool} or the first argument
     */
    @Nonnull
    Optional<String> getExecutable();

    /**
     * Gets the platform independent name of a Java tool, such as {@code java} or {@code javadoc},
     * used when no {@linkplain #getExecutable() executable} is set.
     *
     * @return the tool name, or empty
     */
    @Nonnull
    Optional<String> getTool();

    /**
     * Gets the toolchain asked first for the {@linkplain #getTool() tool}.
     *
     * @return the toolchain, or empty
     */
    @Nonnull
    Optional<Toolchain> getToolchain();

    /**
     * Gets the arguments, in order.
     *
     * @return an unmodifiable list of arguments
     */
    @Nonnull
    List<String> getArguments();

    /**
     * Gets the indexes in {@link #getArguments()} of the arguments that are shown as {@link #MASK}.
     *
     * @return an unmodifiable set of argument indexes
     */
    @Nonnull
    Set<Integer> getMaskedArguments();

    /**
     * Gets the shell that wraps the command line.
     *
     * @return the shell, {@link Shell#NONE} by default
     */
    @Nonnull
    Shell getShell();

    /**
     * Gets the working directory of the process.
     *
     * @return the working directory, or empty for the current directory
     */
    @Nonnull
    Optional<Path> getWorkingDirectory();

    /**
     * Tells whether the process starts from the environment of the running JVM.
     * When false, the process gets only {@link #getEnvironment()}.
     *
     * @return true (the default) to inherit, false to clear the environment
     */
    boolean isInheritEnvironment();

    /**
     * Gets the environment variables set (or, with a {@code null} value, removed) on top of the inherited
     * or cleared environment, in insertion order.
     *
     * @return an unmodifiable map whose values may be null
     */
    @Nonnull
    Map<String, String> getEnvironment();

    /**
     * Gets the stream copied to the standard input of the process, which is closed afterwards.
     *
     * @return the input, or empty
     */
    @Nonnull
    Optional<InputStream> getInput();

    /**
     * Gets the consumer of the lines the process writes to its standard output.
     *
     * @return the consumer, or empty
     */
    @Nonnull
    Optional<Consumer<String>> getOutputConsumer();

    /**
     * Gets the consumer of the lines the process writes to its standard error.
     *
     * @return the consumer, or empty
     */
    @Nonnull
    Optional<Consumer<String>> getErrorConsumer();

    /**
     * Tells whether standard error is merged into standard output.
     *
     * @return true to merge, false (the default) to keep the streams apart
     */
    boolean isRedirectErrorStream();

    /**
     * Gets the charset used to decode output and error lines.
     *
     * @return the charset, or empty for {@link Charset#defaultCharset()}
     */
    @Nonnull
    Optional<Charset> getCharset();

    /**
     * Gets how long the process may run before it is destroyed.
     *
     * @return the timeout, or empty for no timeout
     */
    @Nonnull
    Optional<Duration> getTimeout();

    /**
     * Gets how long a destroyed process may take to exit before it is destroyed forcibly.
     *
     * @return the grace period, {@link #DEFAULT_DESTROY_GRACE_PERIOD} by default
     */
    @Nonnull
    Duration getDestroyGracePeriod();

    /**
     * Tells whether the process is destroyed when the JVM shuts down before it exits.
     *
     * @return true (the default) to destroy it on shutdown
     */
    boolean isDestroyOnShutdown();

    /**
     * Creates a request for a program and its arguments, run without a shell.
     *
     * @param session the session
     * @param executable the program
     * @param arguments the arguments
     * @return the new request
     */
    @Nonnull
    static ProcessLaunchRequest build(
            @Nonnull ProtoSession session, @Nonnull String executable, @Nonnull String... arguments) {
        return builder()
                .session(requireNonNull(session, "session cannot be null"))
                .executable(requireNonNull(executable, "executable cannot be null"))
                .arguments(arguments)
                .build();
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    @Nonnull
    static ProcessLaunchRequestBuilder builder() {
        return new ProcessLaunchRequestBuilder();
    }

    /**
     * Builder for {@link ProcessLaunchRequest}.
     */
    @NotThreadSafe
    class ProcessLaunchRequestBuilder {
        private ProtoSession session;
        private RequestTrace trace;
        private String executable;
        private String tool;
        private Toolchain toolchain;
        private final List<String> arguments = new ArrayList<>();
        private final Set<Integer> maskedArguments = new LinkedHashSet<>();
        private Shell shell = Shell.NONE;
        private Path workingDirectory;
        private boolean inheritEnvironment = true;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private InputStream input;
        private Consumer<String> outputConsumer;
        private Consumer<String> errorConsumer;
        private boolean redirectErrorStream;
        private Charset charset;
        private Duration timeout;
        private Duration destroyGracePeriod = DEFAULT_DESTROY_GRACE_PERIOD;
        private boolean destroyOnShutdown = true;

        ProcessLaunchRequestBuilder() {}

        /**
         * Sets the session.
         *
         * @param session the session
         * @return this builder
         */
        public ProcessLaunchRequestBuilder session(ProtoSession session) {
            this.session = session;
            return this;
        }

        /**
         * Sets the request trace.
         *
         * @param trace the trace, may be null
         * @return this builder
         */
        public ProcessLaunchRequestBuilder trace(RequestTrace trace) {
            this.trace = trace;
            return this;
        }

        /**
         * Sets the program to run.
         *
         * @param executable the program, as a path or a name looked up on {@code PATH}; null to unset
         * @return this builder
         */
        public ProcessLaunchRequestBuilder executable(String executable) {
            this.executable = executable;
            return this;
        }

        /**
         * Sets the program to run.
         *
         * @param executable the program path; null to unset
         * @return this builder
         */
        public ProcessLaunchRequestBuilder executable(Path executable) {
            return executable(executable == null ? null : executable.toString());
        }

        /**
         * Sets the Java tool to run when no executable is set.
         *
         * @param tool the platform independent tool name, such as {@code javadoc}
         * @return this builder
         */
        public ProcessLaunchRequestBuilder tool(String tool) {
            this.tool = tool;
            return this;
        }

        /**
         * Sets the toolchain asked first for the tool.
         *
         * @param toolchain the toolchain, may be null
         * @return this builder
         */
        public ProcessLaunchRequestBuilder toolchain(Toolchain toolchain) {
            this.toolchain = toolchain;
            return this;
        }

        /**
         * Adds an argument.
         *
         * @param argument the argument
         * @return this builder
         */
        public ProcessLaunchRequestBuilder argument(String argument) {
            arguments.add(requireNonNull(argument, "argument cannot be null"));
            return this;
        }

        /**
         * Adds the absolute form of a path as an argument.
         *
         * @param argument the path
         * @return this builder
         */
        public ProcessLaunchRequestBuilder argument(Path argument) {
            return argument(argument.toAbsolutePath().toString());
        }

        /**
         * Adds an argument shown as {@link #MASK} by {@link ProcessLauncher#describe(ProcessLaunchRequest)}.
         *
         * @param argument the argument, such as a passphrase
         * @return this builder
         */
        public ProcessLaunchRequestBuilder maskedArgument(String argument) {
            argument(argument);
            maskedArguments.add(arguments.size() - 1);
            return this;
        }

        /**
         * Adds arguments.
         *
         * @param arguments the arguments
         * @return this builder
         */
        public ProcessLaunchRequestBuilder arguments(String... arguments) {
            return arguments(Arrays.asList(arguments));
        }

        /**
         * Adds arguments.
         *
         * @param arguments the arguments
         * @return this builder
         */
        public ProcessLaunchRequestBuilder arguments(Collection<String> arguments) {
            arguments.forEach(this::argument);
            return this;
        }

        /**
         * Sets the shell that wraps the command line.
         *
         * @param shell the shell
         * @return this builder
         */
        public ProcessLaunchRequestBuilder shell(Shell shell) {
            this.shell = requireNonNull(shell, "shell cannot be null");
            return this;
        }

        /**
         * Sets the working directory.
         *
         * @param workingDirectory the directory, may be null for the current directory
         * @return this builder
         */
        public ProcessLaunchRequestBuilder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets whether the process starts from the environment of the running JVM.
         *
         * @param inheritEnvironment false to start from an empty environment
         * @return this builder
         */
        public ProcessLaunchRequestBuilder inheritEnvironment(boolean inheritEnvironment) {
            this.inheritEnvironment = inheritEnvironment;
            return this;
        }

        /**
         * Sets an environment variable, or removes it when the value is null.
         *
         * @param name the variable name
         * @param value the value, or null to remove the variable
         * @return this builder
         */
        public ProcessLaunchRequestBuilder environment(String name, String value) {
            environment.put(requireNonNull(name, "name cannot be null"), value);
            return this;
        }

        /**
         * Sets environment variables; a null value removes the variable.
         *
         * @param environment the variables
         * @return this builder
         */
        public ProcessLaunchRequestBuilder environment(Map<String, String> environment) {
            environment.forEach(this::environment);
            return this;
        }

        /**
         * Sets the stream copied to the standard input of the process.
         *
         * @param input the input, may be null
         * @return this builder
         */
        public ProcessLaunchRequestBuilder input(InputStream input) {
            this.input = input;
            return this;
        }

        /**
         * Sets the consumer of standard output lines.
         *
         * @param outputConsumer the consumer, may be null
         * @return this builder
         */
        public ProcessLaunchRequestBuilder outputConsumer(Consumer<String> outputConsumer) {
            this.outputConsumer = outputConsumer;
            return this;
        }

        /**
         * Sets the consumer of standard error lines.
         *
         * @param errorConsumer the consumer, may be null
         * @return this builder
         */
        public ProcessLaunchRequestBuilder errorConsumer(Consumer<String> errorConsumer) {
            this.errorConsumer = errorConsumer;
            return this;
        }

        /**
         * Sets whether standard error is merged into standard output.
         *
         * @param redirectErrorStream true to merge
         * @return this builder
         */
        public ProcessLaunchRequestBuilder redirectErrorStream(boolean redirectErrorStream) {
            this.redirectErrorStream = redirectErrorStream;
            return this;
        }

        /**
         * Sets the charset used to decode output and error lines.
         *
         * @param charset the charset, may be null for {@link Charset#defaultCharset()}
         * @return this builder
         */
        public ProcessLaunchRequestBuilder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        /**
         * Sets how long the process may run; null, zero or negative for no timeout.
         *
         * @param timeout the timeout
         * @return this builder
         */
        public ProcessLaunchRequestBuilder timeout(Duration timeout) {
            this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? null : timeout;
            return this;
        }

        /**
         * Sets how long a destroyed process may take to exit before it is destroyed forcibly.
         *
         * @param destroyGracePeriod the grace period, not negative
         * @return this builder
         */
        public ProcessLaunchRequestBuilder destroyGracePeriod(Duration destroyGracePeriod) {
            if (requireNonNull(destroyGracePeriod, "destroyGracePeriod cannot be null")
                    .isNegative()) {
                throw new IllegalArgumentException("destroyGracePeriod cannot be negative");
            }
            this.destroyGracePeriod = destroyGracePeriod;
            return this;
        }

        /**
         * Sets whether the process is destroyed when the JVM shuts down before it exits.
         *
         * @param destroyOnShutdown false to let the process outlive the JVM
         * @return this builder
         */
        public ProcessLaunchRequestBuilder destroyOnShutdown(boolean destroyOnShutdown) {
            this.destroyOnShutdown = destroyOnShutdown;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the immutable request
         * @throws NullPointerException if no session is set
         */
        public ProcessLaunchRequest build() {
            return new DefaultProcessLaunchRequest(
                    session,
                    trace,
                    new Command(executable, tool, toolchain, List.copyOf(arguments), Set.copyOf(maskedArguments)),
                    new Context(shell, workingDirectory, inheritEnvironment, copy(environment)),
                    new Streams(input, outputConsumer, errorConsumer, redirectErrorStream, charset),
                    new Lifetime(timeout, destroyGracePeriod, destroyOnShutdown));
        }

        private static Map<String, String> copy(Map<String, String> environment) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }

        private record Command(
                String executable, String tool, Toolchain toolchain, List<String> arguments, Set<Integer> masked) {}

        private record Context(Shell shell, Path workingDirectory, boolean inherit, Map<String, String> environment) {}

        private record Streams(
                InputStream input, Consumer<String> out, Consumer<String> err, boolean redirect, Charset charset) {}

        private record Lifetime(Duration timeout, Duration grace, boolean destroyOnShutdown) {}

        private static final class DefaultProcessLaunchRequest extends BaseRequest<ProtoSession>
                implements ProcessLaunchRequest {
            private final Command command;
            private final Context context;
            private final Streams streams;
            private final Lifetime lifetime;

            DefaultProcessLaunchRequest(
                    @Nonnull ProtoSession session,
                    @Nullable RequestTrace trace,
                    Command command,
                    Context context,
                    Streams streams,
                    Lifetime lifetime) {
                super(session, trace);
                this.command = command;
                this.context = context;
                this.streams = streams;
                this.lifetime = lifetime;
            }

            @Override
            public Optional<String> getExecutable() {
                return Optional.ofNullable(command.executable());
            }

            @Override
            public Optional<String> getTool() {
                return Optional.ofNullable(command.tool());
            }

            @Override
            public Optional<Toolchain> getToolchain() {
                return Optional.ofNullable(command.toolchain());
            }

            @Override
            public List<String> getArguments() {
                return command.arguments();
            }

            @Override
            public Set<Integer> getMaskedArguments() {
                return command.masked();
            }

            @Override
            public Shell getShell() {
                return context.shell();
            }

            @Override
            public Optional<Path> getWorkingDirectory() {
                return Optional.ofNullable(context.workingDirectory());
            }

            @Override
            public boolean isInheritEnvironment() {
                return context.inherit();
            }

            @Override
            public Map<String, String> getEnvironment() {
                return context.environment();
            }

            @Override
            public Optional<InputStream> getInput() {
                return Optional.ofNullable(streams.input());
            }

            @Override
            public Optional<Consumer<String>> getOutputConsumer() {
                return Optional.ofNullable(streams.out());
            }

            @Override
            public Optional<Consumer<String>> getErrorConsumer() {
                return Optional.ofNullable(streams.err());
            }

            @Override
            public boolean isRedirectErrorStream() {
                return streams.redirect();
            }

            @Override
            public Optional<Charset> getCharset() {
                return Optional.ofNullable(streams.charset());
            }

            @Override
            public Optional<Duration> getTimeout() {
                return Optional.ofNullable(lifetime.timeout());
            }

            @Override
            public Duration getDestroyGracePeriod() {
                return lifetime.grace();
            }

            @Override
            public boolean isDestroyOnShutdown() {
                return lifetime.destroyOnShutdown();
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof DefaultProcessLaunchRequest that
                        && command.equals(that.command)
                        && context.equals(that.context)
                        && streams.equals(that.streams)
                        && lifetime.equals(that.lifetime);
            }

            @Override
            public int hashCode() {
                return List.of(command, context, streams, lifetime).hashCode();
            }

            @Override
            public String toString() {
                return "ProcessLaunchRequest[executable=" + command.executable() + ", tool=" + command.tool()
                        + ", arguments=" + maskedArgumentList() + ", shell=" + context.shell()
                        + ", workingDirectory=" + context.workingDirectory() + ", inheritEnvironment="
                        + context.inherit() + ", environmentNames="
                        + context.environment().keySet()
                        + ", timeout=" + lifetime.timeout() + ']';
            }

            private List<String> maskedArgumentList() {
                List<String> shown = new ArrayList<>(command.arguments());
                command.masked().forEach(index -> shown.set(index, MASK));
                return shown;
            }
        }
    }
}
