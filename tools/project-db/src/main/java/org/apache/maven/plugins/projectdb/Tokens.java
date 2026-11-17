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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic tokens for retrieval: NFKC, lower case, letter/digit runs (the token rule of the M3JDK21
 * {@code Bm25Index} donor), English stop words removed and a few suffixes stripped, so that "fail", "failed" and
 * "failure" or "test" and "tests" meet.
 */
final class Tokens {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "do", "did",
            "does", "what", "which", "why", "who", "how", "when", "where", "of", "in", "on", "to", "for", "and", "or",
            "it", "this", "that", "by", "with", "from", "at", "as", "last", "me", "my", "we", "our");

    private Tokens() {}

    static List<String> of(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP.contains(token)) tokens.add(stem(token));
        }
        return tokens;
    }

    /** A light suffix stripper; never shortens a token below three characters. */
    static String stem(String token) {
        if (token.endsWith("ies") && token.length() > 5) return token.substring(0, token.length() - 3) + "y";
        for (String suffix : List.of("ures", "ure", "ing", "ed", "es", "s", "e")) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 3) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
