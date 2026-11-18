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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Follows a step's log file while the step runs and hands each complete line to a sink (Maven's logger), so native
 * output goes through Maven's logging ({@code -q}, {@code -l}, one line at a time under {@code -T}). Keeps the last
 * lines for the failure message. Lines are UTF-8, else the platform's native encoding.
 */
public final class LogTail implements AutoCloseable {
    private static final int READ_LIMIT = 1 << 20;
    private static final int LINE_LIMIT = 64 << 10;
    private static final Charset NATIVE = nativeCharset();

    private final Path file;
    private final Consumer<String> sink;
    private final int keep;
    private final ArrayDeque<String> last = new ArrayDeque<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final ScheduledExecutorService timer;
    private long position;

    private LogTail(Path file, Consumer<String> sink, int keep) {
        this.file = file;
        this.sink = sink;
        this.keep = keep;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "native-log-tail");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts following {@code file} (which may not exist yet), polling every 50 ms. */
    public static LogTail follow(Path file, Consumer<String> sink, int keep) {
        LogTail tail = new LogTail(file, sink, keep);
        tail.timer.scheduleWithFixedDelay(tail::drain, 0, 50, TimeUnit.MILLISECONDS);
        return tail;
    }

    /** The last lines seen, oldest first. */
    public synchronized List<String> last() {
        return List.copyOf(last);
    }

    /** Stops polling, then reads what is left, including a last line without a line end. */
    @Override
    public void close() {
        stopTimer();
        Stream.generate(this::drain).takeWhile(Boolean::booleanValue).count();
        synchronized (this) {
            if (partial.size() > 0) {
                emit(partial.toByteArray());
                partial.reset();
            }
        }
    }

    private void stopTimer() {
        timer.shutdownNow();
        try {
            timer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads new bytes; true when there were any. */
    synchronized boolean drain() {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long available = channel.size() - position;
            if (available <= 0) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, READ_LIMIT));
            int read = channel.read(buffer, position);
            position += Math.max(read, 0);
            feed(buffer.array(), read);
            return read > 0;
        } catch (IOException notYet) {
            return false;
        }
    }

    private void feed(byte[] bytes, int count) {
        IntStream.range(0, Math.max(count, 0)).forEach(i -> {
            if (bytes[i] == '\n' || partial.size() >= LINE_LIMIT) {
                emit(partial.toByteArray());
                partial.reset();
            }
            if (bytes[i] != '\n') {
                partial.write(bytes[i]);
            }
        });
    }

    private void emit(byte[] raw) {
        int length = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
        String line = decode(java.util.Arrays.copyOf(raw, length));
        sink.accept(line);
        last.addLast(line);
        if (last.size() > keep) {
            last.removeFirst();
        }
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, NATIVE);
        }
    }

    private static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        } catch (IllegalArgumentException unknown) {
            return Charset.defaultCharset();
        }
    }
}
