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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The extractive answer to a question: the ranked chunks, each with its provenance (file, line, build id). Nothing is
 * generated; the text of every line is the stored chunk text.
 *
 * @param question the question as asked
 * @param build    the build the answer was taken from, empty when the database has no build
 * @param hits     ranked hits, best first
 */
public record Answer(String question, String build, List<Retriever.Hit> hits) {
    /** One line per hit: rank, kind, text and {@code (file:line @build)}. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Q: " + question + (build.isEmpty() ? " (no build ingested)" : " (latest build " + build + ")"));
        if (hits.isEmpty()) lines.add("no evidence in the project database");
        for (int i = 0; i < hits.size(); i++) {
            Chunk chunk = hits.get(i).chunk();
            lines.add((i + 1) + ". [" + chunk.kind() + "] " + chunk.text() + " (" + chunk.provenance() + ")");
        }
        return lines;
    }

    /** The answer as one JSON object with a provenance object per hit. */
    public String json() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("question", question);
        answer.put("build", build);
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) list.add(hit(i + 1, hits.get(i)));
        answer.put("answers", list);
        return Json.write(answer);
    }

    private static Map<String, Object> provenance(Chunk chunk) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("file", chunk.file());
        provenance.put("line", (long) chunk.line());
        provenance.put("build", chunk.build());
        return provenance;
    }

    private static Map<String, Object> hit(int rank, Retriever.Hit hit) {
        Chunk chunk = hit.chunk();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rank", (long) rank);
        out.put("score", String.format(Locale.ROOT, "%.6f", hit.score()));
        out.put("kind", chunk.kind());
        out.put("module", chunk.module());
        out.put("text", chunk.text());
        out.put("facets", new LinkedHashMap<String, Object>(chunk.facets()));
        out.put("provenance", provenance(chunk));
        return out;
    }
}
