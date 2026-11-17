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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The last check of an argument vector for a {@code .cmd} launcher, after {@link ArgumentGuard}. Java hands such a
 * script to cmd.exe, which expands {@code %} and {@code !}, interprets {@code ^ & | < >} and quotes, and splits the
 * script's parameters at white space, {@code =}, {@code ,} and {@code ;}; {@code mvn.cmd} then scans the pieces for
 * {@code -f} and {@code --file}. An argument without white space reaches cmd.exe unquoted, so after splitting no piece
 * but its first may start with "-"; an argument with white space is quoted by Java and stays one piece.
 */
final class CmdArguments {

    private static final Pattern EXPANDED = Pattern.compile("[%!^&|<>\"\\p{Cntrl}]");
    private static final Pattern WHITE = Pattern.compile("[ \t]");
    private static final Pattern DELIMITERS = Pattern.compile("[=,;]");

    private CmdArguments() {}

    /** Returns {@code args} when every argument is inert through cmd.exe; refuses the launch otherwise. */
    static List<String> check(List<String> args) {
        args.forEach(CmdArguments::checkOne);
        return args;
    }

    private static void checkOne(String arg) {
        if (EXPANDED.matcher(arg).find()) {
            throw new IllegalStateException("argument holds a character cmd.exe interprets: " + arg);
        }
        List<String> pieces = WHITE.matcher(arg).find() ? List.of(arg) : Arrays.asList(DELIMITERS.split(arg, -1));
        if (pieces.stream().skip(1).anyMatch(piece -> piece.startsWith("-"))) {
            throw new IllegalStateException("argument would add an option through cmd.exe's splitting: " + arg);
        }
    }
}
