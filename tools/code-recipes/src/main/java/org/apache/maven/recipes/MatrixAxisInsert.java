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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Adds a Java major version to a matrix axis list that lacks it. The list holds the version when one of its entries has
 * that major version ({@code 21}, {@code '21'}, {@code 21.0.2}, {@code 21-ea}). The version goes before the first entry
 * with a higher major version, or last when there is none, as a copy of the entry before it (of the first entry when it
 * goes first), so it takes that entry's quoting style. A list is left alone when it is empty, carries an anchor (its
 * aliases would change with it) or has an entry that is not a plain or quoted scalar with a major version: an alias,
 * an expression, a name such as {@code lts}, a literal or folded block, a nested list or mapping.
 */
final class MatrixAxisInsert {
    private static final Set<Yaml.Scalar.Style> QUOTING_STYLES =
            EnumSet.of(Yaml.Scalar.Style.PLAIN, Yaml.Scalar.Style.SINGLE_QUOTED, Yaml.Scalar.Style.DOUBLE_QUOTED);

    private MatrixAxisInsert() {}

    static Optional<SequenceInsert.Result> insert(Yaml.Sequence axis, int version) {
        List<Integer> majors = majors(axis);
        if (axis.getAnchor() != null
                || majors.isEmpty()
                || majors.size() != axis.getEntries().size()
                || majors.contains(version)) {
            return Optional.empty();
        }
        int index = IntStream.range(0, majors.size())
                .filter(i -> majors.get(i) > version)
                .findFirst()
                .orElse(majors.size());
        Yaml.Scalar copied = (Yaml.Scalar) axis.getEntries().get(Math.max(index - 1, 0)).getBlock();
        return SequenceInsert.insert(axis, index, copied, String.valueOf(version));
    }

    /** The major versions of the entries that are plain or quoted scalars with one, in list order. */
    private static List<Integer> majors(Yaml.Sequence axis) {
        return axis.getEntries().stream()
                .map(Yaml.Sequence.Entry::getBlock)
                .filter(Yaml.Scalar.class::isInstance)
                .map(Yaml.Scalar.class::cast)
                .filter(scalar -> QUOTING_STYLES.contains(scalar.getStyle()))
                .map(scalar -> JavaVersions.major(scalar.getValue()))
                .filter(OptionalInt::isPresent)
                .map(OptionalInt::getAsInt)
                .toList();
    }
}
