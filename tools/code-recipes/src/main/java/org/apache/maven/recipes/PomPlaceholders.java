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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openrewrite.Cursor;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.xml.tree.Xml;

/**
 * Resolves the {@code ${...}} references of a POM tag value as the tag sees them. The model properties come first:
 * those of the project, its resolved parents and its active profiles, with the {@code <properties>} of the profile
 * that holds the tag (active or not) laid over them. Only a key that none of these defines falls back to
 * {@link ResolvedPom#getValue}, which reads JVM system properties and the {@code project.*} values; that method alone
 * would let a JVM system property such as {@code java.version} win over a POM property of the same name.
 */
final class PomPlaceholders {
    private PomPlaceholders() {}

    static String resolve(String value, Cursor cursor, ResolvedPom pom) {
        Map<String, String> properties = new HashMap<>(pom.getProperties());
        properties.putAll(profileProperties(cursor));
        return ResolvedPom.placeholderHelper.replacePlaceholders(value, key -> lookup(key, properties, pom));
    }

    private static String lookup(String key, Map<String, String> properties, ResolvedPom pom) {
        if (properties.containsKey(key)) {
            return properties.get(key);
        }
        String placeholder = "${" + key + "}";
        String fallback = pom.getValue(placeholder);
        return placeholder.equals(fallback) ? null : fallback;
    }

    /** The {@code <properties>} of the {@code <profiles><profile>} that holds the cursor's tag, if any. */
    private static Map<String, String> profileProperties(Cursor cursor) {
        List<Xml.Tag> tags = cursor.getPathAsStream()
                .filter(Xml.Tag.class::isInstance)
                .map(Xml.Tag.class::cast)
                .toList();
        return IntStream.range(0, tags.size() - 1)
                .filter(i -> "profile".equals(tags.get(i).getName()) && "profiles".equals(tags.get(i + 1).getName()))
                .mapToObj(tags::get)
                .findFirst()
                .flatMap(profile -> profile.getChild("properties"))
                .map(PomPlaceholders::values)
                .orElse(Map.of());
    }

    private static Map<String, String> values(Xml.Tag properties) {
        return properties.getChildren().stream()
                .collect(Collectors.toMap(Xml.Tag::getName, tag -> tag.getValue().orElse(""), (first, last) -> last));
    }
}
