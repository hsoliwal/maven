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
import java.util.TreeMap;

/**
 * Signed feature hashing of unigrams and bigrams into a fixed-size, L2-normalized vector. The feature hash is FNV-1a
 * 64 over UTF-16 code units with the offset and prime of the M3JDK21 {@code M3LaneFnv1a} donor: bit 0 gives the sign,
 * the remaining bits the dimension. No model, no training; the same text always gives the same vector.
 */
final class HashVectors {
    static final int DIMENSIONS = 1024;
    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private HashVectors() {}

    static double[] of(List<String> tokens) {
        Map<String, Integer> features = new TreeMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            features.merge(tokens.get(i), 1, Integer::sum);
            if (i > 0) features.merge(tokens.get(i - 1) + " " + tokens.get(i), 1, Integer::sum);
        }
        double[] vector = new double[DIMENSIONS];
        for (Map.Entry<String, Integer> feature : features.entrySet()) {
            long hash = fnv1a(feature.getKey());
            int dimension = (int) Long.remainderUnsigned(hash >>> 1, DIMENSIONS);
            vector[dimension] += ((hash & 1) == 0 ? 1 : -1) * (1 + StrictMath.log(feature.getValue()));
        }
        return normalized(vector);
    }

    /** Cosine of two normalized vectors; zero when either is empty. */
    static double cosine(double[] left, double[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) dot += left[i] * right[i];
        return dot;
    }

    static long fnv1a(CharSequence text) {
        long hash = OFFSET;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= PRIME;
        }
        return hash;
    }

    private static double[] normalized(double[] vector) {
        double norm = 0;
        for (double value : vector) norm += value * value;
        norm = StrictMath.sqrt(norm);
        for (int i = 0; i < vector.length && norm > 0; i++) vector[i] /= norm;
        return vector;
    }
}
