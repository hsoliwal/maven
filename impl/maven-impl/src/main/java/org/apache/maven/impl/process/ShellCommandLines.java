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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.services.ProcessLaunchRequest.Shell;

/**
 * Renders the command line handed to {@link ProcessBuilder} for each {@link Shell}.
 * <p>
 * The {@code cmd.exe} and Bourne shell rules are adapted from maven-shared-utils 3.5.0
 * ({@code org.apache.maven.shared.utils.cli.shell.Shell}, {@code CmdShell}, {@code BourneShell}, Apache-2.0):
 * the same shell arguments, the same per-item quoting and the same {@code cd 'dir' && } preamble, so a
 * {@code Commandline} and a request with the same shell give the same command line.
 */
final class ShellCommandLines {

    /** Characters that make {@code CmdShell} quote an item. */
    private static final String CMD_SPECIAL_CHARS = " \t&<>()@^|";

    /** Backslashes followed by a double quote, escaped for the Windows command line parser. */
    private static final Pattern WINDOWS_QUOTE = Pattern.compile("(\\\\*)\"");

    private ShellCommandLines() {}

    /**
     * Renders a command line.
     *
     * @param shell the shell
     * @param windows whether the target OS is Windows
     * @param executable the program, empty to start with the first argument
     * @param arguments the arguments
     * @param workingDirectory the absolute working directory, for the Bourne shell preamble
     * @return the command line
     */
    static List<String> render(
            Shell shell,
            boolean windows,
            Optional<String> executable,
            List<String> arguments,
            Optional<String> workingDirectory) {
        return switch (shell) {
            case NONE -> direct(executable, arguments);
            case DEFAULT -> render(windows ? Shell.CMD : Shell.SH, windows, executable, arguments, workingDirectory);
            case CMD -> cmd(executable, arguments);
            case POWERSHELL -> powerShell(windows, executable, arguments);
            case SH -> bourne("/bin/sh", executable, arguments, workingDirectory);
            case BASH -> bourne("/bin/bash", executable, arguments, workingDirectory);
        };
    }

    static List<String> direct(Optional<String> executable, List<String> arguments) {
        List<String> line = new ArrayList<>(arguments.size() + 1);
        executable.ifPresent(line::add);
        line.addAll(arguments);
        return List.copyOf(line);
    }

    static List<String> cmd(Optional<String> executable, List<String> arguments) {
        String inner = joinQuoted(ShellCommandLines::cmdQuote, "", executable, arguments);
        return List.of("cmd.exe", "/X", "/C", '"' + inner + '"');
    }

    static List<String> bourne(
            String shell, Optional<String> executable, List<String> arguments, Optional<String> workingDirectory) {
        String preamble =
                workingDirectory.map(dir -> "cd " + shQuote(dir) + " && ").orElse("");
        return List.of(shell, "-c", joinQuoted(ShellCommandLines::shQuote, preamble, executable, arguments));
    }

    static List<String> powerShell(boolean windows, Optional<String> executable, List<String> arguments) {
        UnaryOperator<String> quote = windows ? ShellCommandLines::psQuoteForWindows : ShellCommandLines::psQuote;
        String command = "& " + joinQuoted(quote, "", executable, arguments);
        return List.of(windows ? "powershell.exe" : "pwsh", "-NoProfile", "-NonInteractive", "-Command", command);
    }

    /**
     * Joins the quoted executable and arguments as {@code Shell.getRawCommandLine} does: the preamble only
     * precedes an executable, and a space separates an item from a non-empty prefix.
     */
    static String joinQuoted(
            UnaryOperator<String> quote, String preamble, Optional<String> executable, List<String> arguments) {
        StringBuilder line = new StringBuilder();
        executable.ifPresent(exe -> line.append(preamble).append(quote.apply(exe)));
        arguments.forEach(argument -> {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(quote.apply(argument));
        });
        return line.toString();
    }

    /** The quoting of maven-shared-utils {@code CmdShell.quoteOneItem}. */
    static String cmdQuote(String item) {
        if (item.isEmpty() || (item.length() > 1 && item.startsWith("\"") && item.endsWith("\""))) {
            return item;
        }
        boolean special = CMD_SPECIAL_CHARS.chars().anyMatch(c -> item.indexOf(c) >= 0);
        return special ? '"' + item + '"' : item;
    }

    /** The quoting of maven-shared-utils {@code BourneShell.quoteOneItem}. */
    static String shQuote(String item) {
        return "'" + item.replace("'", "'\"'\"'") + "'";
    }

    /** A PowerShell single-quoted string: a single quote is doubled, nothing else is special. */
    static String psQuote(String item) {
        return "'" + item.replace("'", "''") + "'";
    }

    /**
     * A PowerShell single-quoted string whose double quotes survive the parsing of the {@code powershell.exe}
     * command line: each double quote gets a backslash and the backslashes before it are doubled.
     */
    static String psQuoteForWindows(String item) {
        return psQuote(WINDOWS_QUOTE
                .matcher(item)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + match.group(1) + "\\\"")));
    }
}
