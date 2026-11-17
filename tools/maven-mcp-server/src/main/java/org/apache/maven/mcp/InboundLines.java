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
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Lines of the protocol input, each with at most {@code maxChars} characters kept: the rest of a longer line is read
 * and dropped, so one huge line cannot exhaust memory. A trailing CR is removed.
 */
final class InboundLines {

    /** The SDK's own limit for one inbound message. */
    static final int MAX_LINE_CHARS = 16 * 1024 * 1024;

    /** One line and whether it went past the limit. */
    record Line(String text, boolean tooLong) {}

    private final Reader in;
    private final int maxChars;
    private int last;

    InboundLines(Reader in, int maxChars) {
        this.in = in;
        this.maxChars = maxChars;
    }

    /** The next line, or empty at the end of the input; read errors are thrown as {@link UncheckedIOException}. */
    Optional<Line> next() {
        StringBuilder text = new StringBuilder();
        boolean[] tooLong = {false};
        IntStream.generate(this::read).takeWhile(c -> c >= 0 && c != '\n').forEach(c -> {
            if (text.length() < maxChars) {
                text.append((char) c);
            } else {
                tooLong[0] = true;
            }
        });
        if (last < 0 && text.length() == 0) {
            return Optional.empty();
        }
        int end = text.length();
        boolean cr = end > 0 && text.charAt(end - 1) == '\r';
        return Optional.of(new Line(cr ? text.substring(0, end - 1) : text.toString(), tooLong[0]));
    }

    private int read() {
        try {
            last = in.read();
            return last;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
