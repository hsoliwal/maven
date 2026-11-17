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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A parsed question: its tokens, its exact facet filters ({@code kind:flaky}, {@code module:core},
 * {@code status:failed}, {@code artifact:junit:junit}, ...) and the kinds of build data it asks about (intents), each
 * derived from fixed word stems, never from a model.
 *
 * @param tokens  question tokens in order (for the hashing vector)
 * @param terms   the distinct tokens (for BM25)
 * @param filters exact facet filters
 * @param intents asked-for kinds: failure, duplicate, change, dependency, flaky
 */
record Query(List<String> tokens, TreeSet<String> terms, Map<String, String> filters, Set<String> intents) {
    private static final Set<String> FACETS = Set.of("kind", "module", "status", "artifact", "via", "scope", "plane",
            "test", "class", "task", "backend", "build", "signal", "element");
    private static final Map<String, List<String>> STEMS = Map.of(
            "failure", List.of("fail", "error", "broke"),
            "duplicate", List.of("duplicat", "clon", "copi", "copy"),
            "change", List.of("chang", "modifi", "differ", "diff"),
            "dependency", List.of("bring", "brought", "depend", "transitiv", "pull"),
            "flaky", List.of("flak", "intermitt", "unstabl"));

    static Query parse(String question) {
        Map<String, String> filters = new TreeMap<>();
        StringBuilder text = new StringBuilder();
        for (String word : question.trim().split("\\s+")) {
            int colon = word.indexOf(':');
            if (colon > 0 && FACETS.contains(word.substring(0, colon))) filters.put(word.substring(0, colon),
                    word.substring(colon + 1));
            else text.append(word).append(' ');
        }
        List<String> tokens = Tokens.of(text.toString());
        return new Query(tokens, new TreeSet<>(tokens), filters, intents(tokens));
    }

    private static Set<String> intents(List<String> tokens) {
        Set<String> intents = new TreeSet<>();
        STEMS.forEach((intent, stems) -> {
            if (tokens.stream().anyMatch(token -> stems.stream().anyMatch(token::startsWith))) intents.add(intent);
        });
        return intents;
    }

    /** Only exact facets were asked ({@code kind:flaky}): every chunk they accept is a hit, in provenance order. */
    boolean facetOnly() {
        return terms.isEmpty() && !filters.isEmpty();
    }

    /** Every exact filter matches. */
    boolean accepts(Chunk chunk) {
        return filters.entrySet().stream().allMatch(filter -> filter.getValue().equals(chunk.facet(filter.getKey())));
    }

    /** The chunk is of a kind the question asks about. */
    boolean asksFor(Chunk chunk) {
        String status = chunk.facet("status");
        boolean failedTest = "test".equals(chunk.kind()) && ("failed".equals(status) || "error".equals(status));
        return intents.contains("failure") && ("build-error".equals(chunk.kind()) || failedTest)
                || intents.contains("duplicate") && "duplicate".equals(chunk.kind())
                || intents.contains("change") && "change".equals(chunk.kind())
                || intents.contains("dependency") && "dependency".equals(chunk.kind())
                || intents.contains("flaky") && "flaky".equals(chunk.kind());
    }
}
