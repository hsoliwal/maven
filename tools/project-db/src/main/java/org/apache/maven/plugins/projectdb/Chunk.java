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
package org.apache.maven.plugins.projectdb;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One retrievable unit of build data and its provenance: the project-relative file, the 1-based line and the id of
 * the build whose data produced it. Facets are exact key/value attributes used for filtering.
 *
 * @param kind   what the chunk describes: test, build-error, dependency, duplicate, atom, change, flaky, ...
 * @param module the module directory relative to the project root ({@code .} for the root)
 * @param file   the project-relative file the chunk comes from, {@code /} separated
 * @param line   the 1-based line in {@code file}
 * @param text   the searchable text
 * @param facets exact attributes, sorted by key
 * @param build  the build id, or empty before the ingest assigns one
 */
public record Chunk(String kind, String module, String file, int line, String text, Map<String, String> facets,
        String build) {
    public Chunk {
        facets = Collections.unmodifiableMap(new TreeMap<>(facets));
    }

    /** A chunk not yet assigned to a build. */
    static Chunk of(String kind, String module, String file, int line, String text, Map<String, String> facets) {
        return new Chunk(kind, module, file, Math.max(1, line), text, facets, "");
    }

    Chunk inBuild(String id) {
        return new Chunk(kind, module, file, line, text, facets, id);
    }

    /** The exact value of a facet; kind and module are facets too. */
    String facet(String key) {
        if ("kind".equals(key)) return kind;
        if ("module".equals(key)) return module;
        return facets.getOrDefault(key, "");
    }

    /** {@code file:line @build}, the provenance printed with every answer. */
    String provenance() {
        return file + ":" + line + " @" + build;
    }
}
