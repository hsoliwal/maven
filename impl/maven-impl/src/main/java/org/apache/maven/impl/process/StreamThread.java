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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A daemon thread that feeds standard input or pumps output lines, as the {@code StreamPumper} and
 * {@code StreamPollFeeder} threads of {@code CommandLineUtils} do. A failure is kept for the caller; a pump keeps
 * draining after its consumer fails, so the process never blocks on a full pipe.
 */
final class StreamThread extends Thread {

    /** Work that may fail with any exception. */
    @FunctionalInterface
    interface IoTask {
        /**
         * Runs the task.
         *
         * @throws Exception on failure
         */
        void run() throws Exception;
    }

    private final String stream;
    private final IoTask task;
    private volatile Exception failure;

    private StreamThread(String stream, IoTask task) {
        super("ProcessLauncher-" + stream);
        this.stream = stream;
        this.task = task;
        setDaemon(true);
        setContextClassLoader(null);
    }

    /**
     * Starts a thread copying {@code input} to {@code output}, then closing {@code output}.
     *
     * @param input the request input
     * @param output the standard input of the process
     * @return the started thread
     */
    static StreamThread feed(InputStream input, OutputStream output) {
        return start("stdin", () -> {
            try (OutputStream target = output) {
                input.transferTo(target);
            }
        });
    }

    /**
     * Starts a thread passing each line of {@code input} to {@code consumer}.
     *
     * @param stream the stream name, {@code stdout} or {@code stderr}
     * @param input the process stream
     * @param consumer the line consumer
     * @param charset the charset of the lines
     * @return the started thread
     */
    static StreamThread pump(String stream, InputStream input, Consumer<String> consumer, Charset charset) {
        return start(stream, () -> {
            Delivery delivery = new Delivery(consumer);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
                reader.lines().forEach(delivery);
            }
            delivery.rethrow();
        });
    }

    private static StreamThread start(String stream, IoTask task) {
        StreamThread thread = new StreamThread(stream, task);
        thread.start();
        return thread;
    }

    @Override
    public void run() {
        try {
            task.run();
        } catch (Exception e) {
            failure = e;
        }
    }

    /**
     * Gets the stream name used in failure messages.
     *
     * @return {@code stdin}, {@code stdout} or {@code stderr}
     */
    String stream() {
        return stream;
    }

    /**
     * Gets the failure of the task.
     *
     * @return the failure, or empty
     */
    Optional<Exception> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Waits for the thread to end.
     *
     * @param timeout the longest wait
     * @return true if the thread has ended
     * @throws InterruptedException if the current thread is interrupted
     */
    boolean awaitEnd(Duration timeout) throws InterruptedException {
        join(Math.max(1, timeout.toMillis()));
        return !isAlive();
    }

    /** Passes lines to a consumer until it fails, then drops them. */
    private static final class Delivery implements Consumer<String> {
        private final Consumer<String> consumer;
        private RuntimeException failure;

        Delivery(Consumer<String> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void accept(String line) {
            if (failure == null) {
                try {
                    consumer.accept(line);
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
        }

        void rethrow() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
