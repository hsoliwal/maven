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
package org.apache.maven.plugins.clustering;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The names the JNI specification gives a native method ("Resolving Native Method Names"). The short name is
 * {@code Java_}, the escaped binary class name in internal form ({@code p/Outer$Inner}), {@code _} and the escaped
 * method name. The long name adds {@code __} and the escaped argument descriptor. Escaping keeps the ASCII letters and
 * digits, writes {@code /} as {@code _}, {@code _} as {@code _1}, {@code ;} as {@code _2}, {@code [} as {@code _3},
 * and every other UTF-16 code unit as {@code _0} and four lower-case hex digits: {@code $} becomes {@code _00024}, and
 * the two halves of a surrogate pair are escaped one by one.
 */
final class JniNames {
    /** The argument descriptor of a reference type that cannot be resolved without a class path. */
    static final String UNKNOWN = "L?;";
    /** One escaped class descriptor ({@code L}, escaped name, {@code _2}), standing for an {@link #UNKNOWN} type. */
    private static final String ANY_CLASS = "L(?:[A-Za-z0-9]|_(?!2))+_2";
    private static final String PRIMITIVE_OR_CLASS = "ZBCSIJFDL";
    private static final int HEX_DIGITS = 0x10000;

    private JniNames() { }

    static String escape(String name) {
        return name.chars().mapToObj(c -> escape((char) c)).collect(Collectors.joining());
    }

    private static String escape(char c) {
        if (c < 0x80 && Character.isLetterOrDigit(c)) return String.valueOf(c);
        return switch (c) {
            case '/' -> "_";
            case '_' -> "_1";
            case ';' -> "_2";
            case '[' -> "_3";
            default -> "_0" + Integer.toHexString(HEX_DIGITS | c).substring(1);
        };
    }

    /** {@code Java_<escaped class>_<escaped method>}; the class is a binary name in internal form. */
    static String shortName(String binaryClass, String method) {
        return "Java_" + escape(binaryClass) + "_" + escape(method);
    }

    /** The short name, {@code __} and the escaped argument descriptor (the descriptor without its parentheses). */
    static String longName(String shortName, String arguments) {
        return shortName + "__" + escape(arguments);
    }

    /** The long names a function may have when some arguments are {@link #UNKNOWN}: one class name stands for each. */
    static Pattern longPattern(String shortName, String arguments) {
        String[] known = escape(arguments).split(Pattern.quote(escape(UNKNOWN)), -1);
        return Pattern.compile(Arrays.stream(known).map(Pattern::quote)
                .collect(Collectors.joining(ANY_CLASS, Pattern.quote(shortName + "__"), "")));
    }

    /**
     * Where a function name's long-name separator {@code __} starts, or -1 for a short name. The separator is followed
     * by the end or by an escaped descriptor (a primitive letter, {@code L}, or {@code _3} for an array); an escaped
     * class name has {@code __} only before {@code 1} or {@code 0} (a package that starts with {@code _} or a non-ASCII
     * character).
     */
    static int overloadSeparator(String function) {
        return IntStream.range("Java_".length(), Math.max("Java_".length(), function.length() - 1))
                .filter(i -> function.startsWith("__", i) && descriptorAt(function, i + 2)).findFirst().orElse(-1);
    }

    private static boolean descriptorAt(String function, int at) {
        return at == function.length() || PRIMITIVE_OR_CLASS.indexOf(function.charAt(at)) >= 0
                || function.startsWith("_3", at);
    }
}
