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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * CPU-only, deterministic retrieval over chunks: BM25 over the chunk tokens (k1 1.2, b 0.75, the formula of the
 * M3JDK21 {@code Bm25Index} donor), cosine of signed feature-hashing vectors over unigrams and bigrams, exact facet
 * filters ({@code key:value} in the question) and a fixed intent boost for the kinds a question asks about. Ties are
 * broken by file, line and text, so the same store and question always give the same answer.
 */
final class Retriever {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    /** Normalized BM25 a chunk of another kind needs when the question asks about a kind. */
    private static final double STRONG = 0.5;
    /** Most hits one file may contribute to an answer. */
    private static final int PER_FILE = 2;

    /** One ranked chunk and the parts of its score. */
    record Hit(Chunk chunk, double score, double bm25, double cosine, boolean intent) {}

    private final List<Chunk> chunks;
    private final List<Map<String, Integer>> frequencies = new ArrayList<>();
    private final Map<String, Integer> documents = new HashMap<>();
    private final List<double[]> vectors = new ArrayList<>();
    private final double averageLength;

    Retriever(List<Chunk> chunks) {
        this.chunks = List.copyOf(chunks);
        long total = 0;
        for (Chunk chunk : this.chunks) {
            List<String> tokens = Tokens.of(chunk.kind() + " " + chunk.text());
            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens) counts.merge(token, 1, Integer::sum);
            counts.keySet().forEach(term -> documents.merge(term, 1, Integer::sum));
            frequencies.add(counts);
            vectors.add(HashVectors.of(tokens));
            total += tokens.size();
        }
        averageLength = this.chunks.isEmpty() ? 0 : (double) total / this.chunks.size();
    }

    /**
     * The k best chunks for a question; chunks with neither a term match nor the asked-for kind are never hits,
     * except that a question of exact facets alone returns the chunks those facets accept.
     */
    List<Hit> search(String question, int k) {
        Query query = Query.parse(question);
        double[] lexical = lexical(query);
        double[] vector = HashVectors.of(query.tokens());
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Hit hit = hit(query, i, lexical[i], HashVectors.cosine(vector, vectors.get(i)));
            if (hit != null) hits.add(hit);
        }
        hits.sort(ORDER);
        return diverse(hits, k);
    }

    /** The best k hits with at most {@link #PER_FILE} from one file, so one long log cannot fill the answer. */
    private static List<Hit> diverse(List<Hit> ranked, int k) {
        Map<String, Integer> perFile = new HashMap<>();
        List<Hit> chosen = new ArrayList<>();
        for (Hit hit : ranked) {
            if (chosen.size() == k) break;
            if (perFile.merge(hit.chunk().file(), 1, Integer::sum) <= PER_FILE) chosen.add(hit);
        }
        return List.copyOf(chosen);
    }

    /** BM25 of every chunk divided by the best one, so that it adds to the cosine on the same scale. */
    private double[] lexical(Query query) {
        double[] scores = new double[chunks.size()];
        double max = 0;
        for (int i = 0; i < chunks.size(); i++) {
            scores[i] = bm25(query.terms(), i);
            max = Math.max(max, scores[i]);
        }
        for (int i = 0; i < scores.length && max > 0; i++) scores[i] /= max;
        return scores;
    }

    private Hit hit(Query query, int index, double lexical, double cosine) {
        Chunk chunk = chunks.get(index);
        if (!query.accepts(chunk)) return null;
        boolean intent = query.asksFor(chunk);
        if (query.facetOnly()) return new Hit(chunk, 0, 0, 0, false);
        // A question about a kind (failure, change, ...) admits other kinds only on a strong term match.
        if (!intent && (lexical == 0 || !query.intents().isEmpty() && lexical < STRONG)) return null;
        return new Hit(chunk, lexical + cosine + (intent ? 1.0 : 0.0), lexical, cosine, intent);
    }

    /** Query terms are a set, so repeating a word cannot inflate its weight. */
    private double bm25(TreeSet<String> terms, int index) {
        Map<String, Integer> counts = frequencies.get(index);
        int length = counts.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0;
        for (String term : terms) {
            Integer tf = counts.get(term);
            if (tf == null) continue;
            int n = documents.get(term);
            double idf = StrictMath.log(1.0 + (chunks.size() - n + 0.5) / (n + 0.5));
            double norm = K1 * (1 - B + B * length / averageLength);
            score += idf * tf * (K1 + 1) / (tf + norm);
        }
        return score;
    }

    private static final Comparator<Hit> ORDER = Comparator.comparingDouble(Hit::score).reversed()
            .thenComparing(hit -> hit.chunk().file()).thenComparingInt(hit -> hit.chunk().line())
            .thenComparing(hit -> hit.chunk().text());
}
