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
package org.apache.maven.plugins.polyglot.adapters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A stdio JSON-RPC language server for collector fault injection; no LSP library. Modes: {@code ok} (one warning per
 * opened file), {@code twostage} (an empty publish, the real ERROR 1.5 s later, as servers with a second checking
 * stage do), {@code progress} (work-done progress begins, an empty publish, the ERROR and the progress end 2.5 s
 * later), {@code late} (an empty publish, the ERROR 3.5 s later), {@code surrogate} (a message holding a lone UTF-16
 * surrogate as a JSON escape), {@code silent} (never answers) and {@code crash} (exits 3 at once).
 */
public final class FakeLanguageServer {
    private static final OutputStream OUT = System.out;
    private static final Pattern CONTENT_LENGTH = Pattern.compile("(?i)content-length:\\s*(\\d+)");

    private FakeLanguageServer() { }

    public static void main(String[] args) throws IOException {
        if ("crash".equals(args[0])) {
            System.exit(3);
        }
        Stream.generate(() -> read(System.in)).takeWhile(Optional::isPresent).map(Optional::get)
                .forEach(message -> answer(args[0], message));
    }

    private static void answer(String mode, String message) {
        String method = field(message, "method");
        String id = field(message, "id");
        if ("silent".equals(mode) || method == null) {
            return;
        }
        switch (method) {
            case "initialize" -> send("{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}");
            case "shutdown" -> send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":null}");
            case "exit" -> System.exit(0);
            case "textDocument/didOpen" -> opened(mode, field(message, "uri"));
            default -> { }
        }
    }

    private static void opened(String mode, String uri) {
        switch (mode) {
            case "twostage" -> later(1500, () -> send(publish(uri, error())), () -> send(publish(uri, "")));
            case "late" -> later(3500, () -> send(publish(uri, error())), () -> send(publish(uri, "")));
            case "surrogate" -> send(publish(uri, "{\"range\":" + RANGE
                    + ",\"severity\":2,\"code\":\"s1\",\"message\":\"bad " + '\\' + "ud800 text\"}"));
            case "progress" -> later(2500, () -> {
                send(publish(uri, error()));
                send(progress("end"));
            }, () -> send(progress("begin")), () -> send(publish(uri, "")));
            default -> send(publish(uri, "{\"range\":" + RANGE + ",\"severity\":2,\"code\":\"w1\",\"message\":\"ok\"}"));
        }
    }

    private static final String RANGE = "{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":1}}";

    private static String error() {
        return "{\"range\":" + RANGE + ",\"severity\":1,\"code\":\"late-error\",\"message\":\"stage 2\"}";
    }

    /** Runs {@code now} at once and {@code then} after {@code millis} on another thread. */
    private static void later(long millis, Runnable then, Runnable... now) {
        Stream.of(now).forEach(Runnable::run);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(millis);
                then.run();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
    }

    private static String publish(String uri, String diagnostics) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\"" + uri
                + "\",\"version\":1,\"diagnostics\":[" + diagnostics + "]}}";
    }

    private static String progress(String kind) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"$/progress\",\"params\":{\"token\":\"check\",\"value\":{\"kind\":\""
                + kind + "\"" + ("begin".equals(kind) ? ",\"title\":\"checking\"" : "") + "}}}";
    }

    private static synchronized void send(String json) {
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            OUT.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            OUT.write(body);
            OUT.flush();
        } catch (IOException closed) {
            System.exit(0);
        }
    }

    /** One message: header lines up to the blank line, then Content-Length bytes; empty at the end of input. */
    private static Optional<String> read(InputStream in) {
        try {
            Optional<Integer> length = header(in);
            return length.map(n -> readBody(in, n));
        } catch (IOException closed) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> header(InputStream in) throws IOException {
        StringBuilder seen = new StringBuilder();
        IntStream.generate(() -> readByte(in)).takeWhile(c -> c >= 0 && !ended(seen.append((char) c))).forEach(c -> { });
        Matcher length = CONTENT_LENGTH.matcher(seen);
        return !ended(seen) || !length.find() ? Optional.empty() : Optional.of(Integer.parseInt(length.group(1)));
    }

    private static boolean ended(StringBuilder header) {
        return header.length() >= 4 && "\r\n\r\n".contentEquals(header.subSequence(header.length() - 4, header.length()));
    }

    private static int readByte(InputStream in) {
        try {
            return in.read();
        } catch (IOException closed) {
            return -1;
        }
    }

    private static String readBody(InputStream in, int length) {
        try {
            return new String(in.readNBytes(length), StandardCharsets.UTF_8);
        } catch (IOException closed) {
            return "";
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|(\\d+))").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : null;
    }
}
