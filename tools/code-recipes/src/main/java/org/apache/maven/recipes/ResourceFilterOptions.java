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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * How the setters of maven-filtering {@code MavenResourcesExecution} map to the {@code ResourceFilterRequest}
 * builder of the Maven core {@code ResourceFilter} service.
 */
final class ResourceFilterOptions {

    /** Setter to builder method, one to one. */
    static final Map<String, String> BUILDER = Map.ofEntries(
            Map.entry("setResources", "roots"),
            Map.entry("setOutputDirectory", "outputDirectory"),
            Map.entry("setResourcesBaseDirectory", "baseDirectory"),
            Map.entry("setMavenSession", "session"),
            Map.entry("setEncoding", "encoding"),
            Map.entry("setPropertiesEncoding", "propertiesEncoding"),
            Map.entry("setAdditionalProperties", "properties"),
            Map.entry("setDelimiters", "delimiters"),
            Map.entry("setEscapeString", "escapeString"),
            Map.entry("setSupportMultiLineFiltering", "supportMultiLineFiltering"),
            Map.entry("setEscapeWindowsPaths", "escapeWindowsPaths"),
            Map.entry("setNonFilteredFileExtensions", "nonFilteredFileExtensions"),
            Map.entry("setAddDefaultExcludes", "useDefaultExcludes"),
            Map.entry("setIncludeEmptyDirs", "includeEmptyDirs"),
            Map.entry("setFlatten", "flatten"),
            Map.entry("setChangeDetection", "changeDetection"),
            Map.entry("setOverwrite", "changeDetection"));

    /** Setters whose values the caller resolves in the request callback. */
    static final List<String> CALLBACK = List.of(
            "setMavenProject", "setFilters", "setFileFilters", "setInjectProjectBuildFilters", "setUseDefaultFilterWrappers");

    private ResourceFilterOptions() {}

    /** The marker text for a call whose execution was configured with the given setters. */
    static String describe(Collection<String> setters) {
        Collection<String> sorted = new TreeSet<>(setters);
        List<String> parts = new ArrayList<>();
        parts.add("ResourceFilter.copy(ResourceFilterRequest)");
        add(parts, "builder: ", sorted.stream()
                .filter(BUILDER::containsKey)
                .map(setter -> setter + " -> " + BUILDER.get(setter))
                .toList());
        add(parts, "callback: ", sorted.stream().filter(CALLBACK::contains).toList());
        add(parts, "no equivalent: ", sorted.stream()
                .filter(setter -> !BUILDER.containsKey(setter) && !CALLBACK.contains(setter))
                .toList());
        return String.join("; ", parts);
    }

    private static void add(List<String> parts, String label, List<String> items) {
        if (!items.isEmpty()) {
            parts.add(label + String.join(", ", items));
        }
    }
}
