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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

/**
 * The protocol output, written one whole line at a time. The SDK writes a message, then its line end, then flushes;
 * {@link ProtocolInput} answers unreadable input lines from another thread. Bytes wait here until their line end and
 * every line goes out under one lock, so two messages never interleave on stdout.
 */
final class LineOutput extends OutputStream {

    private final OutputStream out;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    LineOutput(OutputStream out) {
        this.out = out;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    /** Sends every whole line in {@code bytes} with what was pending before it; keeps the rest pending. */
    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        int end = offset + length;
        int newline = IntStream.range(offset, end).map(i -> end - 1 - (i - offset)).filter(i -> bytes[i] == '\n')
                .findFirst().orElse(-1);
        if (newline < 0) {
            pending.write(bytes, offset, length);
            return;
        }
        pending.write(bytes, offset, newline + 1 - offset);
        pending.writeTo(out);
        pending.reset();
        pending.write(bytes, newline + 1, end - newline - 1);
        out.flush();
    }

    /** Writes {@code line} and a line end as one message. */
    synchronized void writeLine(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Partial lines stay pending until their line end; flushing sends only whole lines. */
    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }
}
