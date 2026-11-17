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
package org.apache.maven.agent.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The charset Maven wrote its {@code -l} log in. Maven 4 writes it with {@code Files.newBufferedWriter}, always UTF-8.
 * Maven 3.9 points standard output at the log through a {@code PrintStream} without a charset, which encodes with the
 * JVM default charset: UTF-8 on Java 18 and newer, the platform charset (such as Cp1252 on Windows) on Java 17. The
 * extension runs in that JVM, so the candidates are UTF-8, the JVM default charset and {@code native.encoding}; the
 * first that decodes the log strictly wins, and the JVM default is used when none does.
 */
final class LogCharset {
    private LogCharset() {}

    /** UTF-8, then the JVM default charset, then {@code native.encoding} when it names a supported charset. */
    static List<Charset> candidates() {
        Optional<Charset> nativeEncoding = Optional.ofNullable(System.getProperty("native.encoding"))
                .flatMap(LogCharset::charset);
        return Stream.concat(Stream.of(StandardCharsets.UTF_8, Charset.defaultCharset()), nativeEncoding.stream())
                .distinct().collect(Collectors.toList());
    }

    /** The first of {@code candidates} that decodes the first {@code maxChars} of {@code file} strictly. */
    static Charset of(Path file, List<Charset> candidates, long maxChars) throws IOException {
        try {
            return candidates.stream().filter(candidate -> decodes(file, candidate, maxChars)).findFirst()
                    .orElse(Charset.defaultCharset());
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    /** Whether {@code charset} decodes the file without a malformed or unmappable sequence; I/O errors propagate. */
    private static boolean decodes(Path file, Charset charset, long maxChars) {
        CharsetDecoder strict = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(Files.newInputStream(file), strict))) {
            long[] chars = {0};
            lines.lines().takeWhile(line -> (chars[0] += line.length() + 1) <= maxChars).forEach(line -> {});
            return true;
        } catch (UncheckedIOException failed) {
            if (failed.getCause() instanceof CharacterCodingException) {
                return false;
            }
            throw failed;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Optional<Charset> charset(String name) {
        try {
            return Optional.of(Charset.forName(name));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
            return Optional.empty();
        }
    }
}
