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
package org.apache.maven.plugins.clustering.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a server command setting into arguments, without a shell. Whitespace separates arguments. Text in double or
 * single quotes is taken as it is, spaces and the other kind of quote included, and joins the unquoted text next to
 * it: {@code "C:/Program Files/LLVM/bin/clangd.exe" --log=error} is two arguments, {@code -data='J:/my workspace'} is
 * one. {@code ""} is an empty argument. A backslash is an ordinary character, so Windows paths need no escaping. An
 * unclosed quote is an error. {@link #entries} splits a list of settings on {@code ;} outside quotes.
 */
final class CommandLine {
    private CommandLine() { }

    static List<String> split(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder arg = new StringBuilder();
        boolean open = false;
        char quote = 0;
        for (char c : command.toCharArray()) {
            if (quote != 0 && c != quote) arg.append(c);
            else if (quote != 0) quote = 0;
            else if (c == '"' || c == '\'') quote = c;
            else if (!Character.isWhitespace(c)) arg.append(c);
            else if (open) args.add(take(arg));
            open = quote != 0 || arg.length() > 0 || open && !Character.isWhitespace(c);
        }
        if (quote != 0) throw new IllegalArgumentException("unclosed " + quote + " in: " + command);
        if (open) args.add(take(arg));
        return List.copyOf(args);
    }

    /** {@code a;b="x;y"} to {@code a} and {@code b="x;y"}: a {@code ;} inside quotes does not split. */
    static List<String> entries(String setting) {
        List<String> entries = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < setting.length(); i++) {
            char c = setting.charAt(i);
            if (quote != 0) quote = c == quote ? 0 : quote;
            else if (c == '"' || c == '\'') quote = c;
            else if (c == ';') {
                entries.add(setting.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(setting.substring(start));
        return entries;
    }

    private static String take(StringBuilder arg) {
        String value = arg.toString();
        arg.setLength(0);
        return value;
    }
}
