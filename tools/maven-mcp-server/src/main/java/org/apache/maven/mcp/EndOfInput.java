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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/** The server's stdin, which opens a latch when the client closes it, so the server can stop. */
final class EndOfInput extends FilterInputStream {

    private final CountDownLatch closed = new CountDownLatch(1);

    EndOfInput(InputStream in) {
        super(in);
    }

    /** Blocks until stdin reaches its end or fails. */
    void await() throws InterruptedException {
        closed.await();
    }

    @Override
    public int read() throws IOException {
        return observe(() -> super.read());
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return observe(() -> super.read(buffer, offset, length));
    }

    @FunctionalInterface
    private interface Read {
        int run() throws IOException;
    }

    private int observe(Read read) throws IOException {
        try {
            int result = read.run();
            if (result < 0) {
                closed.countDown();
            }
            return result;
        } catch (IOException e) {
            closed.countDown();
            throw e;
        }
    }
}
