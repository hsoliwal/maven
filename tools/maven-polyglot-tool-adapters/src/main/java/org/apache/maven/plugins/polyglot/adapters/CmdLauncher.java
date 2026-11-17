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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one check for the commands the adapters start, by launcher name (JDK only). A command interpreter is never a
 * launcher: cmd.exe re-reads every argument as a command line, PowerShell's default mode, the POSIX shells' -c and
 * the Windows script hosts take code, so no metacharacter list could make their argv data. Such a launcher
 * ({@link #INTERPRETERS}; any directory, any case, with or without .exe/.com, trailing dots and spaces and a
 * {@code :stream} suffix ignored, as Windows ignores them) is refused, whatever its arguments; name the tool's own
 * executable instead. A {@code .cmd}/{@code .bat} launcher is run as {@code cmd.exe /c}, which expands {@code %VAR%}
 * and {@code !VAR!}, drops {@code ^}, splits commands on {@code &}, {@code |}, {@code <}, {@code >} and line breaks,
 * and ends quoting at {@code "}, even for arguments Java passed as one argv element; escaping cannot be made safe for
 * every batch file, so such a command is refused when any element (the launcher path included) holds one of those
 * characters or any other control character. Every other executable receives argv as data and is never refused. A
 * renamed copy of an interpreter is not recognised: the check is by name. Used for the argv the engine starts
 * ({@link ToolArgs#launch}) and again by the tool processes for the commands they start themselves (a language
 * server, git).
 */
final class CmdLauncher {
    /** cmd.exe expands or splits on these even inside quotes. */
    static final String METACHARACTERS = "%!^&|<>\"";
    /** Command interpreters and script hosts, by file name without .exe/.com. */
    static final Set<String> INTERPRETERS = Set.of("cmd", "command", "powershell", "powershell_ise", "pwsh",
            "wscript", "cscript", "mshta", "wsl", "bash", "sh", "dash", "zsh", "ksh", "csh", "tcsh", "fish", "busybox");

    private CmdLauncher() { }

    /** The command, unchanged, unless it starts an interpreter or a batch launcher would receive a metacharacter. */
    static List<String> shellSafe(List<String> command) throws IOException {
        if (interpreter(command.get(0))) {
            throw new IOException("a command interpreter cannot be a launcher (it would read its arguments as code);"
                    + " name the tool's own executable: " + command.get(0));
        }
        if (batch(command.get(0)) && command.stream().anyMatch(CmdLauncher::unsafe)) {
            throw new IOException("a .cmd/.bat launcher cannot take cmd.exe metacharacters (" + METACHARACTERS
                    + " or line breaks) safely: " + command.get(0));
        }
        return List.copyOf(command);
    }

    /** True for a .cmd or .bat file in any case; Windows ignores trailing dots and spaces in a file name. */
    static boolean batch(String launcher) {
        String name = name(launcher);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    /** True for a command interpreter or script host named directly, by path or through PATH. */
    static boolean interpreter(String launcher) {
        return INTERPRETERS.contains(name(launcher).replaceFirst("\\.(exe|com)$", ""));
    }

    /** The file name, lower case, without a drive ({@code C:cmd.exe}), a {@code :stream} suffix, trailing dots/spaces. */
    static String name(String launcher) {
        String file = launcher.substring(Math.max(launcher.lastIndexOf('/'), launcher.lastIndexOf('\\')) + 1)
                .replaceFirst("^[A-Za-z]:", "");
        int stream = file.indexOf(':');
        return (stream < 0 ? file : file.substring(0, stream)).toLowerCase(Locale.ROOT).replaceAll("[. ]+$", "");
    }

    private static boolean unsafe(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f || METACHARACTERS.indexOf(c) >= 0);
    }
}
