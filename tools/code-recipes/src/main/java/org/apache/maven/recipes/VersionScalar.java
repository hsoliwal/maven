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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Reads and raises the Java version text of a YAML scalar. A plain or quoted scalar holds the text as its value. A
 * literal ({@code |}) or folded ({@code >}) block scalar also holds, in rewrite-yaml, the rest of its indicator line
 * (chomping and indentation indicators, a comment), the line break and the indentation; so a block scalar is raised
 * only when its text is a single token, and only that token is replaced. A block of several lines is never raised.
 */
final class VersionScalar {
    private static final Set<Yaml.Scalar.Style> BLOCK_STYLES =
            EnumSet.of(Yaml.Scalar.Style.LITERAL, Yaml.Scalar.Style.FOLDED);
    private static final Pattern SINGLE_TOKEN_BLOCK = Pattern.compile("([^\\n]*\\n\\s*)(\\S+)(\\s*)");

    private VersionScalar() {}

    /** The text of the scalar, trimmed, without the indicator line of a block scalar. */
    static String content(Yaml.Scalar scalar) {
        String value = scalar.getValue();
        return (isBlock(scalar) ? value.substring(value.indexOf('\n') + 1) : value).trim();
    }

    /** The non-blank lines of the scalar's text, trimmed. */
    static List<String> lines(Yaml.Scalar scalar) {
        return content(scalar).lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    /** The scalar with its version raised to the minimum when it is below it; otherwise the same scalar. */
    static Yaml.Scalar raise(Yaml.Scalar scalar, int minimum) {
        if (!JavaVersions.below(content(scalar), minimum)) {
            return scalar;
        }
        String version = String.valueOf(minimum);
        return isBlock(scalar) ? raiseBlock(scalar, version) : scalar.withValue(version);
    }

    private static Yaml.Scalar raiseBlock(Yaml.Scalar scalar, String version) {
        Matcher block = SINGLE_TOKEN_BLOCK.matcher(scalar.getValue());
        return block.matches() ? scalar.withValue(block.group(1) + version + block.group(3)) : scalar;
    }

    private static boolean isBlock(Yaml.Scalar scalar) {
        return BLOCK_STYLES.contains(scalar.getStyle());
    }
}
