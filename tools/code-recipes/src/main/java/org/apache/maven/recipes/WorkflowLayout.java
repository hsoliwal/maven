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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openrewrite.Cursor;
import org.openrewrite.yaml.tree.Yaml;

/**
 * Where things sit in a GitHub Actions workflow or composite action: jobs ({@code jobs.<job>}), step {@code with}
 * inputs and their {@code java-version}, the steps of a composite action ({@code runs.steps}), the job matrix
 * ({@code jobs.<job>.strategy.matrix}), its axes, {@code include} and {@code exclude} lists, and the axes that an
 * {@code actions/setup-java} step of a job reads through {@code java-version: ${{ matrix.<axis> }}}.
 */
final class WorkflowLayout {
    static final String INCLUDE = "include";
    static final String EXCLUDE = "exclude";
    private static final String ANY = "*";
    private static final String JAVA_VERSION = "java-version";
    private static final Pattern SETUP_JAVA = Pattern.compile("actions/setup-java@.*");
    private static final Pattern MATRIX_REFERENCE =
            Pattern.compile("\\$\\{\\{\\s*matrix\\.([A-Za-z_][A-Za-z0-9_-]*)\\s*}}");

    private WorkflowLayout() {}

    static boolean isJob(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY);
    }

    /** {@code jobs.<job>.steps[].with.java-version}. */
    static boolean isStepJavaVersion(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "steps", YamlBlocks.SEQUENCE, "with", JAVA_VERSION);
    }

    /** {@code runs.steps[].with.java-version} of a composite action. */
    static boolean isActionStepJavaVersion(List<String> keyPath) {
        return matches(keyPath, "runs", "steps", YamlBlocks.SEQUENCE, "with", JAVA_VERSION);
    }

    /** {@code jobs.<job>.steps[].with} or {@code runs.steps[].with}. */
    static boolean isStepWith(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "steps", YamlBlocks.SEQUENCE, "with")
                || matches(keyPath, "runs", "steps", YamlBlocks.SEQUENCE, "with");
    }

    static boolean isMatrix(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "strategy", "matrix");
    }

    /** {@code jobs.<job>.strategy.matrix.<key>}: an axis, {@code include} or {@code exclude}. */
    static boolean isMatrixAxis(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "strategy", "matrix", ANY);
    }

    /** An object of {@code jobs.<job>.strategy.matrix.include[]} or {@code exclude[]}. */
    static boolean isMatrixObject(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "strategy", "matrix", INCLUDE, YamlBlocks.SEQUENCE)
                || matches(keyPath, "jobs", ANY, "strategy", "matrix", EXCLUDE, YamlBlocks.SEQUENCE);
    }

    /** {@code jobs.<job>.strategy.matrix.exclude[].<axis>}. */
    static boolean isExcludeValue(List<String> keyPath) {
        return matches(keyPath, "jobs", ANY, "strategy", "matrix", EXCLUDE, YamlBlocks.SEQUENCE, ANY);
    }

    /** The matrix axes the job's {@code actions/setup-java} steps take their {@code java-version} from. */
    static Set<String> setupJavaAxes(Yaml.Block job) {
        return YamlBlocks.child(job, "steps").stream()
                .flatMap(YamlBlocks::entries)
                .filter(WorkflowLayout::usesSetupJava)
                .map(step -> YamlBlocks.child(step, "with").flatMap(with -> YamlBlocks.child(with, JAVA_VERSION)))
                .flatMap(Optional::stream)
                .map(WorkflowLayout::matrixAxis)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the step mapping, the given number of mappings above the cursor, uses {@code actions/setup-java}. */
    static boolean isInSetupJavaStep(Cursor cursor, int mappingsBetween) {
        return cursor.getPathAsStream()
                .filter(Yaml.Mapping.class::isInstance)
                .skip(mappingsBetween)
                .findFirst()
                .filter(step -> usesSetupJava((Yaml.Mapping) step))
                .isPresent();
    }

    static boolean usesSetupJava(Yaml.Block step) {
        return YamlBlocks.child(step, "uses")
                .flatMap(YamlBlocks::scalar)
                .filter(uses -> SETUP_JAVA.matcher(uses.trim()).matches())
                .isPresent();
    }

    private static boolean matches(List<String> keyPath, String... pattern) {
        return keyPath.size() == pattern.length
                && IntStream.range(0, pattern.length)
                        .allMatch(i -> ANY.equals(pattern[i]) || pattern[i].equals(keyPath.get(i)));
    }

    /** The axis that a {@code ${{ matrix.<axis> }}} text refers to, when the whole text (trimmed) is one. */
    static Optional<String> matrixReference(String text) {
        return Optional.of(MATRIX_REFERENCE.matcher(text.trim()))
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1));
    }

    private static Optional<String> matrixAxis(Yaml.Block javaVersion) {
        return javaVersion instanceof Yaml.Scalar scalar
                ? matrixReference(VersionScalar.content(scalar))
                : Optional.empty();
    }
}
