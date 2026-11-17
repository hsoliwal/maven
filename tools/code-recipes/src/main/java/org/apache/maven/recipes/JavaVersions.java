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
package org.apache.maven.recipes;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Java major version out of a version text: {@code 17}, {@code 17.0.2}, {@code 21-ea} and the old
 * {@code 1.8} form (8). Anything else, such as an expression or {@code lts}, has no major version.
 */
final class JavaVersions {
    private static final Pattern VERSION = Pattern.compile("(1\\.)?([0-9]{1,4})(\\.[0-9]+)*([-+].*)?");

    private JavaVersions() {}

    static OptionalInt major(String text) {
        Matcher matcher = VERSION.matcher(text.trim());
        return matcher.matches() ? OptionalInt.of(Integer.parseInt(matcher.group(2))) : OptionalInt.empty();
    }

    static boolean below(String text, int minimum) {
        OptionalInt major = major(text);
        return major.isPresent() && major.getAsInt() < minimum;
    }
}
