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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code -l} log is read in the charset Maven wrote it in: UTF-8 when it decodes strictly (Maven on Java 18 and
 * newer, Maven 4), else the JVM default charset that a {@code PrintStream} without a charset uses (Maven 3.9 on
 * Java 17 writes Cp1252 on Windows), else {@code native.encoding}. A Cp1252 log with a non-ASCII root reads back
 * with the root intact, so its paths become root-relative.
 */
public final class LogCharsetTestMain {
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final String LINE = "[ERROR] C:/r/fx \u00e9 dir/a/B.java:[5,16] cannot find symbol\n";

    private LogCharsetTestMain() {}

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("log-charset");
        Path utf8 = Files.write(dir.resolve("utf8.log"), LINE.getBytes(StandardCharsets.UTF_8));
        Path cp1252 = Files.write(dir.resolve("cp1252.log"), LINE.getBytes(CP1252));
        Path ascii = Files.write(dir.resolve("ascii.log"), "[ERROR] a/B.java:[1,1] x\n".getBytes(StandardCharsets.US_ASCII));
        List<Charset> candidates = List.of(StandardCharsets.UTF_8, CP1252);
        Check.equal(StandardCharsets.UTF_8, LogCharset.of(utf8, candidates, 1 << 20), "UTF-8 log");
        Check.equal(CP1252, LogCharset.of(cp1252, candidates, 1 << 20), "Cp1252 log");
        Check.equal(StandardCharsets.UTF_8, LogCharset.of(ascii, candidates, 1 << 20), "ASCII log");
        fallbacks(cp1252, candidates);
        Check.done("LogCharsetTestMain");
    }

    private static void fallbacks(Path cp1252, List<Charset> candidates) throws IOException {
        Check.equal(Charset.defaultCharset(), LogCharset.of(cp1252, List.of(StandardCharsets.UTF_8), 1 << 20),
                "nothing decodes strictly: the JVM default");
        List<Charset> defaults = LogCharset.candidates();
        Check.that(defaults.get(0).equals(StandardCharsets.UTF_8) && defaults.contains(Charset.defaultCharset()),
                "UTF-8 first, then the JVM default: " + defaults);
        Check.that(new String(Files.readAllBytes(cp1252), LogCharset.of(cp1252, candidates, 1 << 20)).equals(LINE),
                "the root reads back intact");
    }
}
