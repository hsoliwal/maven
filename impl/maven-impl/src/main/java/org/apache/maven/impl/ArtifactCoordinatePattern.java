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
package org.apache.maven.impl;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.maven.api.VersionConstraint;
import org.apache.maven.api.services.VersionParser;
import org.apache.maven.api.services.VersionParserException;
import org.apache.maven.impl.ArtifactView.Field;

import static org.apache.maven.impl.ArtifactView.Field.ARTIFACT_ID;
import static org.apache.maven.impl.ArtifactView.Field.BASE_VERSION;
import static org.apache.maven.impl.ArtifactView.Field.CLASSIFIER;
import static org.apache.maven.impl.ArtifactView.Field.GROUP_ID;
import static org.apache.maven.impl.ArtifactView.Field.TYPE;

/**
 * One compiled coordinate pattern of an {@link org.apache.maven.api.services.ArtifactMatcherRequest}.
 * <p>
 * The token rules are adapted from {@code PatternIncludesArtifactFilter.compile} in Apache Maven Common
 * Artifact Filters (Apache-2.0, commit a11a231759800e38a5c46822f1cc04b867bc5670): the same number of tokens
 * selects the same coordinates, a token {@code *} compares nothing, a token with {@code *} or {@code ?} is
 * a {@link CoordinateGlob}, and a version token of a five-token pattern that starts with {@code [} or
 * {@code (} is a version range. Version ranges use the Maven 4 {@link VersionParser}.
 */
final class ArtifactCoordinatePattern {
    private static final String ANY = "*";

    private final String source;

    private final boolean negative;

    private final List<Term> terms;

    private ArtifactCoordinatePattern(String source, boolean negative, List<Term> terms) {
        this.source = source;
        this.negative = negative;
        this.terms = terms;
    }

    /**
     * Compiles a pattern. Leading {@code !} characters negate it.
     *
     * @throws IllegalArgumentException if the pattern has no token or more than five, or a version range is invalid
     */
    static ArtifactCoordinatePattern compile(String pattern, VersionParser parser) {
        String body = pattern.replaceFirst("^!+", "");
        String[] tokens = Arrays.stream(body.split(":"))
                .map(token -> token.isEmpty() ? ANY : token)
                .toArray(String[]::new);
        List<Term> terms =
                terms(body, tokens, parser).stream().flatMap(Optional::stream).toList();
        return new ArtifactCoordinatePattern(pattern, body.length() < pattern.length(), terms);
    }

    private static List<Optional<Term>> terms(String body, String[] tokens, VersionParser parser) {
        return switch (tokens.length) {
            case 1 -> List.of(term(tokens[0], parser, GROUP_ID));
            case 2 -> twoTokens(tokens, parser);
            case 3 -> threeTokens(tokens, parser);
            case 4 -> fourTokens(tokens, parser);
            case 5 -> fiveTokens(tokens, parser);
            default -> throw new IllegalArgumentException("Invalid pattern: " + body);
        };
    }

    boolean isNegative() {
        return negative;
    }

    boolean matches(ArtifactView view) {
        return terms.stream().allMatch(term -> term.matches(view));
    }

    @Override
    public String toString() {
        return source;
    }

    private static List<Optional<Term>> fiveTokens(String[] t, VersionParser p) {
        return List.of(
                term(t[0], p, GROUP_ID),
                term(t[1], p, ARTIFACT_ID),
                term(t[2], p, TYPE),
                term(t[3], p, CLASSIFIER),
                term(t[4], p, BASE_VERSION));
    }

    private static List<Optional<Term>> fourTokens(String[] t, VersionParser p) {
        return List.of(
                term(t[0], p, GROUP_ID),
                term(t[1], p, ARTIFACT_ID),
                term(t[2], p, TYPE),
                term(t[3], p, BASE_VERSION, CLASSIFIER));
    }

    /** {@code *:*:*} all; {@code *:*:x} TC; {@code *:x:*} AT; {@code *:x:y} GA+TC; {@code x:*:*} GA; {@code x:*:y} G+TC. */
    private static List<Optional<Term>> threeTokens(String[] t, VersionParser p) {
        return switch (wildcards(t)) {
            case 0b111 -> List.of();
            case 0b110 -> List.of(term(t[2], p, TYPE, CLASSIFIER));
            case 0b101 -> List.of(term(t[1], p, ARTIFACT_ID, TYPE));
            case 0b100 -> List.of(term(t[1], p, GROUP_ID, ARTIFACT_ID), term(t[2], p, TYPE, CLASSIFIER));
            case 0b011 -> List.of(term(t[0], p, GROUP_ID, ARTIFACT_ID));
            case 0b010 -> List.of(term(t[0], p, GROUP_ID), term(t[2], p, TYPE, CLASSIFIER));
            default -> List.of(term(t[0], p, GROUP_ID), term(t[1], p, ARTIFACT_ID), term(t[2], p, TYPE));
        };
    }

    /** {@code *:*} all; {@code *:x} GATV; {@code x:*} G; {@code x:y} G+A. */
    private static List<Optional<Term>> twoTokens(String[] t, VersionParser p) {
        return switch (wildcards(t)) {
            case 0b11 -> List.of();
            case 0b10 -> List.of(term(t[1], p, GROUP_ID, ARTIFACT_ID, TYPE, BASE_VERSION));
            default -> List.of(term(t[0], p, GROUP_ID), term(t[1], p, ARTIFACT_ID));
        };
    }

    /** One bit per token, most significant first, set when the token is {@code *}. */
    private static int wildcards(String[] tokens) {
        return Arrays.stream(tokens)
                .mapToInt(token -> ANY.equals(token) ? 1 : 0)
                .reduce(0, (bits, bit) -> bits << 1 | bit);
    }

    private static Optional<Term> term(String token, VersionParser parser, Field... fields) {
        if (ANY.equals(token)) {
            return Optional.empty();
        }
        Set<Field> set = EnumSet.copyOf(Arrays.asList(fields));
        return Optional.of(new Term(set, valueTest(token, set, parser)));
    }

    private static Predicate<String> valueTest(String token, Set<Field> fields, VersionParser parser) {
        if (CoordinateGlob.hasWildcard(token)) {
            return new CoordinateGlob(token)::matches;
        }
        if (fields.equals(EnumSet.of(BASE_VERSION)) && (token.startsWith("[") || token.startsWith("("))) {
            return versionRange(token, parser);
        }
        return token::equals;
    }

    private static Predicate<String> versionRange(String token, VersionParser parser) {
        try {
            VersionConstraint range = parser.parseVersionConstraint(token);
            return value -> value != null && range.contains(parser.parseVersion(value));
        } catch (VersionParserException e) {
            throw new IllegalArgumentException("Wrong version spec: " + token, e);
        }
    }

    /**
     * Compares one token with some coordinates: it matches when any of them matches.
     */
    private record Term(Set<Field> fields, Predicate<String> value) {
        boolean matches(ArtifactView view) {
            return fields.stream().anyMatch(field -> value.test(view.get(field)));
        }
    }
}
