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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code ArtifactMatcherRequest} builder calls that select the same artifacts as a Maven Common Artifact Filters
 * constructor call, from the constructor's arguments.
 */
final class ArtifactFilterRequests {
    private static final String SHARED = "org.apache.maven.shared.artifact.filter.";

    static final String INCLUDES = SHARED + "PatternIncludesArtifactFilter";

    static final String EXCLUDES = SHARED + "PatternExcludesArtifactFilter";

    static final String SCOPES = SHARED + "collection.ScopeFilter";

    static final String GROUP_IDS = SHARED + "collection.GroupIdFilter";

    static final String DEPENDENCY_GROUP_IDS = "org.apache.maven.plugins.dependency.utils.filters.GroupIdFilter";

    private static final Map<String, String> COORDINATES = Map.of(
            GROUP_IDS, "GROUP_ID",
            SHARED + "collection.ArtifactIdFilter", "ARTIFACT_ID",
            SHARED + "collection.TypeFilter", "TYPE",
            SHARED + "collection.ClassifierFilter", "CLASSIFIER");

    private static final String NESTED_GROUP_IDS = "ArtifactMatcherFactory: no 1:1 request, this GroupIdFilter matches a "
            + "group id and the group ids nested under it (org.example matches org.example.foo, not org.examples), "
            + "while groupIdPrefixMatch(true) matches any prefix and the default matches exactly";

    private ArtifactFilterRequests() {}

    /**
     * One constructor argument: its source text and, for a literal, its value.
     *
     * @param source the printed argument
     * @param literal whether the argument is a literal
     * @param value the value of a literal ({@code null} for the null literal)
     */
    record Argument(String source, boolean literal, Object value) {
        boolean isNullLiteral() {
            return literal && value == null;
        }
    }

    /** {@return the marker message for a constructor call, or empty when the type is not a mapped filter} */
    static Optional<String> marker(String type, List<Argument> arguments) {
        if (DEPENDENCY_GROUP_IDS.equals(type)) {
            return Optional.of(isTrue(arguments, 2) ? request(values("GROUP_ID", arguments)) : NESTED_GROUP_IDS);
        }
        return Optional.ofNullable(calls(type, arguments)).map(ArtifactFilterRequests::request);
    }

    private static String calls(String type, List<Argument> arguments) {
        return switch (type) {
            case INCLUDES -> patterns("includes", arguments);
            case EXCLUDES -> patterns("excludes", arguments);
            case SCOPES -> arguments.size() == 2
                    ? call("includeScope", arguments.get(0)) + call("excludeScope", arguments.get(1))
                    : null;
            case GROUP_IDS -> values("GROUP_ID", arguments) + groupIdPrefixMatch(arguments);
            default -> COORDINATES.containsKey(type) ? values(COORDINATES.get(type), arguments) : null;
        };
    }

    private static String request(String calls) {
        return "ArtifactMatcherFactory: ArtifactMatcherRequest.builder()" + calls + ".build()";
    }

    private static String patterns(String name, List<Argument> arguments) {
        if (arguments.size() == 1) {
            return call(name, arguments.get(0));
        }
        return arguments.size() == 2 ? call(name, arguments.get(0)) + call("transitive", arguments.get(1)) : null;
    }

    private static String values(String coordinate, List<Argument> arguments) {
        if (arguments.size() < 2) {
            return null;
        }
        return list("includeValues", coordinate, arguments.get(0)) + list("excludeValues", coordinate, arguments.get(1));
    }

    /** The shared GroupIdFilter matches by prefix unless its third argument asks for an exact match. */
    private static String groupIdPrefixMatch(List<Argument> arguments) {
        if (arguments.size() == 3 && !arguments.get(2).literal()) {
            return ".groupIdPrefixMatch(!" + arguments.get(2).source() + ")";
        }
        return isTrue(arguments, 2) ? "" : ".groupIdPrefixMatch(true)";
    }

    private static boolean isTrue(List<Argument> arguments, int index) {
        return arguments.size() > index && Boolean.TRUE.equals(arguments.get(index).value());
    }

    private static String call(String name, Argument argument) {
        return argument.isNullLiteral() ? "" : "." + name + "(" + argument.source() + ")";
    }

    /** A comma-separated list argument as a list; an empty or null one sets no values, as in the filters. */
    private static String list(String name, String coordinate, Argument argument) {
        if (argument.isNullLiteral() || "".equals(argument.value())) {
            return "";
        }
        String values = argument.value() instanceof String text
                ? Arrays.stream(text.split(",")).map(v -> '"' + v + '"').collect(Collectors.joining(", "))
                : argument.source() + ".split(\",\")";
        return "." + name + "(Coordinate." + coordinate + ", List.of(" + values + "))";
    }
}
