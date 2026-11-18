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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Windows command lines. A Windows process receives one string and splits it itself; programs built with the MSVC
 * or MinGW runtime (and the Java launcher) split it by the rules of {@code CommandLineToArgvW}. Java's
 * {@code ProcessBuilder} joins arguments without escaping inner quotes, so an argument with a quote is split or
 * merged with its neighbours. Each argument is therefore quoted here by those rules; an argument that already starts
 * and ends with a quote is passed through by {@code ProcessBuilder} unchanged. Batch files run inside cmd.exe, whose
 * own syntax ({@code & | < > ^ % !} and quotes) no quoting can neutralise for every batch file, so such arguments
 * are refused for {@code .cmd} and {@code .bat} tools.
 */
public final class WindowsArgs {
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s\"*?]");
    private static final Pattern BATCH_SYNTAX = Pattern.compile("[\"%!^&|<>\\r\\n]");

    private WindowsArgs() {}

    /** The list to hand to {@code ProcessBuilder} so the program receives exactly {@code command}. */
    public static List<String> encode(List<String> command) {
        checkBatch(command);
        return Stream.concat(Stream.of(command.get(0)), command.stream().skip(1).map(WindowsArgs::quote)).toList();
    }

    /**
     * One argument by the MSVC runtime rules: unchanged when it needs no quotes; else backslashes before a quote are
     * doubled, the quote escaped, trailing backslashes doubled, and the whole wrapped in quotes.
     */
    public static String quote(String argument) {
        if (!argument.isEmpty() && !NEEDS_QUOTES.matcher(argument).find()) {
            return argument;
        }
        String inner = argument.replaceAll("(\\\\*)\"", "$1$1\\\\\"").replaceAll("(\\\\+)$", "$1$1");
        return "\"" + inner + "\"";
    }

    /**
     * True for a tool that Windows runs through cmd.exe. Windows drops trailing dots and spaces from a file name and
     * reads {@code name:stream} as a stream of {@code name}, so {@code fake.cmd.}, {@code fake.cmd .} and
     * {@code fake.cmd::$DATA} still start cmd.exe; the name is therefore judged without those, and also by the real
     * name of the file it opens (which resolves an 8.3 short name or a link).
     */
    public static boolean batch(String tool) {
        return batchName(tool) || realName(tool).map(WindowsArgs::batchName).orElse(false);
    }

    private static boolean batchName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int stream = lower.indexOf(':', 2);
        String file = (stream >= 0 ? lower.substring(0, stream) : lower).replaceAll("[. ]+$", "");
        return file.endsWith(".cmd") || file.endsWith(".bat");
    }

    /** The name of the file {@code tool} opens, when it exists. */
    static Optional<String> realName(String tool) {
        try {
            return Optional.of(Path.of(tool).toRealPath().getFileName().toString());
        } catch (IOException | RuntimeException unresolvable) {
            return Optional.empty();
        }
    }

    /** Refuses cmd.exe syntax in the arguments of a batch file. */
    public static void checkBatch(List<String> command) {
        if (!batch(command.get(0))) {
            return;
        }
        command.stream().skip(1).filter(a -> BATCH_SYNTAX.matcher(a).find()).findFirst().ifPresent(a -> {
            throw new IllegalArgumentException("refused argument for the batch file " + command.get(0) + ": '" + a
                    + "' holds cmd.exe syntax (\" % ! ^ & | < > or a line break), which cmd.exe would interpret; "
                    + "configure an .exe for this tool instead");
        });
    }
}
