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
import java.util.OptionalInt;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Reads the Java sources of a GitHub Actions workflow and whether each can run a Java major version. The sources are
 * the {@code actions/setup-java} steps of the jobs, and the inputs of reusable workflow calls ({@code jobs.<job>.uses})
 * whose name holds {@code java} or {@code jdk} and whose value names a Java version or a matrix axis. A source can
 * run the version when one of its values has that major version, where a {@code ${{ matrix.<axis> }}} value stands
 * for the values the job's matrix gives the axis: its list and its {@code include} objects (a matrix or an object
 * written as a nested flow mapping is read as text). Expressions, names such as {@code lts}, aliases,
 * {@code java-version-file} and steps without a {@code java-version} cannot.
 */
final class WorkflowJavaLegs {
    private static final String JAVA_VERSION = "java-version";
    private static final Pattern JAVA_INPUT = Pattern.compile("(?i).*(java|jdk).*");
    private static final Pattern VERSION_TOKEN = Pattern.compile("[0-9][0-9A-Za-z.+-]*");

    private WorkflowJavaLegs() {}

    /** One value per Java source of the workflow document: whether it can run the version. */
    static List<Boolean> legs(Yaml.Block workflow, int version) {
        return YamlBlocks.child(workflow, "jobs").stream()
                .flatMap(WorkflowJavaLegs::mappingValues)
                .flatMap(job -> Stream.concat(stepLegs(job, version), reusableLegs(job, version)))
                .toList();
    }

    private static Stream<Boolean> stepLegs(Yaml.Block job, int version) {
        return YamlBlocks.child(job, "steps").stream()
                .flatMap(YamlBlocks::entries)
                .filter(WorkflowLayout::usesSetupJava)
                .map(step -> javaVersions(step).stream().anyMatch(value -> runs(job, value, version)));
    }

    /** The inputs of a reusable workflow call that name a Java version or a matrix axis. */
    private static Stream<Boolean> reusableLegs(Yaml.Block job, int version) {
        return YamlBlocks.child(job, "uses").isEmpty()
                ? Stream.empty()
                : YamlBlocks.child(job, "with").stream()
                        .flatMap(WorkflowJavaLegs::mappingEntries)
                        .filter(input -> JAVA_INPUT.matcher(input.getKey().getValue()).matches())
                        .map(Yaml.Mapping.Entry::getValue)
                        .filter(Yaml.Scalar.class::isInstance)
                        .map(value -> VersionScalar.content((Yaml.Scalar) value))
                        .filter(text -> WorkflowLayout.matrixReference(text).isPresent() || hasVersionToken(text))
                        .map(text -> runs(job, text, version));
    }

    /** The {@code java-version} texts of a setup-java step: the lines of the input, or its flow mapping values. */
    private static List<String> javaVersions(Yaml.Block step) {
        return YamlBlocks.child(step, "with")
                .map(with -> with instanceof Yaml.Scalar flow
                        ? flowValues(flow, JAVA_VERSION).toList()
                        : YamlBlocks.child(with, JAVA_VERSION)
                                .filter(Yaml.Scalar.class::isInstance)
                                .map(value -> VersionScalar.lines((Yaml.Scalar) value))
                                .orElse(List.of()))
                .orElse(List.of());
    }

    /** Whether the text runs the version: as a matrix reference through the job's matrix, or as version tokens. */
    private static boolean runs(Yaml.Block job, String text, int version) {
        return WorkflowLayout.matrixReference(text)
                .map(axis -> axisValues(job, axis).anyMatch(value -> isVersion(value, version)))
                .orElseGet(() -> versionTokens(text).anyMatch(token -> isVersion(token, version)));
    }

    /** The values the job's matrix gives the axis: its list and the axis values of its include objects. */
    private static Stream<String> axisValues(Yaml.Block job, String axis) {
        return YamlBlocks.child(job, "strategy")
                .flatMap(strategy -> YamlBlocks.child(strategy, "matrix"))
                .stream()
                .flatMap(matrix -> matrix instanceof Yaml.Scalar flow
                        ? flowValues(flow, axis)
                        : Stream.concat(listValues(matrix, axis), includeValues(matrix, axis)));
    }

    private static Stream<String> listValues(Yaml.Block matrix, String axis) {
        return YamlBlocks.child(matrix, axis)
                .filter(Yaml.Sequence.class::isInstance)
                .stream()
                .flatMap(list -> MatrixAxis.values((Yaml.Sequence) list));
    }

    private static Stream<String> includeValues(Yaml.Block matrix, String axis) {
        return YamlBlocks.child(matrix, WorkflowLayout.INCLUDE).stream()
                .flatMap(YamlBlocks::entries)
                .flatMap(object -> object instanceof Yaml.Scalar flow
                        ? flowValues(flow, axis)
                        : YamlBlocks.child(object, axis)
                                .filter(Yaml.Scalar.class::isInstance)
                                .map(value -> VersionScalar.content((Yaml.Scalar) value))
                                .stream());
    }

    private static Stream<String> flowValues(Yaml.Scalar flow, String key) {
        return FlowText.isFlowMapping(flow) ? FlowText.values(flow.getValue(), key).stream() : Stream.empty();
    }

    private static boolean hasVersionToken(String text) {
        return versionTokens(text).findAny().isPresent();
    }

    /** The words of the text that read as a Java version ({@code 17}, {@code 21.0.2}, {@code 25-ea}). */
    private static Stream<String> versionTokens(String text) {
        return VERSION_TOKEN.matcher(text).results()
                .map(MatchResult::group)
                .filter(token -> JavaVersions.major(token).isPresent());
    }

    private static boolean isVersion(String text, int version) {
        OptionalInt major = JavaVersions.major(text);
        return major.isPresent() && major.getAsInt() == version;
    }

    private static Stream<Yaml.Block> mappingValues(Yaml.Block block) {
        return mappingEntries(block).map(Yaml.Mapping.Entry::getValue);
    }

    private static Stream<Yaml.Mapping.Entry> mappingEntries(Yaml.Block block) {
        return block instanceof Yaml.Mapping mapping ? mapping.getEntries().stream() : Stream.empty();
    }
}
