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

/**
 * The one check for commands that Windows starts through cmd.exe. A {@code .cmd}/{@code .bat} launcher is run as
 * {@code cmd.exe /c}, which expands {@code %VAR%} and {@code !VAR!}, drops {@code ^}, splits commands on {@code &},
 * {@code |}, {@code <}, {@code >} and line breaks, and ends quoting at {@code "}, even for arguments Java passed as
 * one argv element. Escaping cannot be made safe for every batch file, so such a command is refused when any element
 * (the launcher path included) holds one of those characters or any other control character. Every other executable
 * receives argv as data and is never refused. Used for the argv the engine starts ({@link ToolArgs#launch}) and again
 * by the tool processes for the commands they start themselves (a language server, git). JDK only.
 */
final class CmdLauncher {
    /** cmd.exe expands or splits on these even inside quotes. */
    static final String METACHARACTERS = "%!^&|<>\"";

    private CmdLauncher() { }

    /** The command, unchanged, unless a batch launcher would receive a cmd.exe metacharacter. */
    static List<String> shellSafe(List<String> command) throws IOException {
        if (batch(command.get(0)) && command.stream().anyMatch(CmdLauncher::unsafe)) {
            throw new IOException("a .cmd/.bat launcher cannot take cmd.exe metacharacters (" + METACHARACTERS
                    + " or line breaks) safely: " + command.get(0));
        }
        return List.copyOf(command);
    }

    /** True for a .cmd or .bat file in any case; Windows ignores trailing dots and spaces in a file name. */
    static boolean batch(String launcher) {
        String name = launcher.toLowerCase(Locale.ROOT).replaceAll("[. ]+$", "");
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    private static boolean unsafe(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f || METACHARACTERS.indexOf(c) >= 0);
    }
}
