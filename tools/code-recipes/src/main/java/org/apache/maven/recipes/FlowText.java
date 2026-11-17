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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Reads values out of a nested flow mapping, such as {@code with: {java-version: 17}}, which rewrite-yaml keeps as
 * the text of a plain scalar. A value is a single item ({@code 17}, {@code '17'}) or the items of a flow list
 * ({@code ['17', '21']}), each trimmed and unquoted. It is a text scan, not a YAML parser: it is used to report
 * values, never to change them.
 */
final class FlowText {
    private FlowText() {}

    static boolean isFlowMapping(Yaml.Scalar scalar) {
        return scalar.getStyle() == Yaml.Scalar.Style.PLAIN && scalar.getValue().trim().startsWith("{");
    }

    /** The items of every value of the key in the flow mapping text. */
    static List<String> values(String text, String key) {
        Pattern value = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(key) + "\\s*:\\s*(\\[[^\\]]*]|[^,}]*)");
        return value.matcher(text).results().flatMap(match -> items(match.group(1))).toList();
    }

    private static Stream<String> items(String value) {
        String trimmed = value.trim();
        String list = trimmed.startsWith("[") ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
        return Arrays.stream(list.split(",")).map(FlowText::unquote).filter(item -> !item.isEmpty());
    }

    private static String unquote(String item) {
        return item.trim().replaceAll("^['\"]|['\"]$", "").trim();
    }
}
