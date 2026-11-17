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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Raises the Java versions of one matrix axis list to a minimum. Entries below the minimum take the minimum, in
 * their own quoting style; a raised entry whose new value the list already holds is dropped. Entries at or above
 * the minimum and entries without a major version (expressions, {@code lts}) are kept as they are.
 */
final class MatrixAxis {
    private MatrixAxis() {}

    static RaisedDuplicates.Result raise(Yaml.Sequence axis, int minimum) {
        List<Yaml.Sequence.Entry> raised =
                axis.getEntries().stream().map(entry -> raise(entry, minimum)).toList();
        return RaisedDuplicates.drop(axis, raised, MatrixAxis::value);
    }

    /**
     * The values of a matrix axis list that the raise renames to the minimum: the values below the minimum, when
     * the raise drops no entry (one value becomes the minimum, which the list did not hold). When values merge into
     * one, or the matrix has no such list, there are none.
     */
    static Set<String> renamedValues(Yaml.Block matrix, String axis, int minimum) {
        return YamlBlocks.child(matrix, axis)
                .filter(Yaml.Sequence.class::isInstance)
                .map(Yaml.Sequence.class::cast)
                .filter(list -> raise(list, minimum).sequence().getEntries().size() == list.getEntries().size())
                .map(list -> values(list).filter(value -> JavaVersions.below(value, minimum)))
                .map(values -> values.collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }

    /** The scalar values of an axis list, as {@link VersionScalar#content} reads them. */
    static Stream<String> values(Yaml.Sequence axis) {
        return axis.getEntries().stream()
                .map(Yaml.Sequence.Entry::getBlock)
                .filter(Yaml.Scalar.class::isInstance)
                .map(block -> VersionScalar.content((Yaml.Scalar) block));
    }

    private static Yaml.Sequence.Entry raise(Yaml.Sequence.Entry entry, int minimum) {
        return entry.getBlock() instanceof Yaml.Scalar scalar
                ? entry.withBlock(VersionScalar.raise(scalar, minimum))
                : entry;
    }

    private static String value(Yaml.Sequence.Entry entry) {
        return entry.getBlock() instanceof Yaml.Scalar scalar
                ? VersionScalar.content(scalar)
                : entry.getId().toString();
    }
}
