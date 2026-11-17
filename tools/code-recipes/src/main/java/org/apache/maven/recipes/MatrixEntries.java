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
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openrewrite.yaml.tree.Yaml;

/**
 * Raises axis values inside the objects of a matrix {@code include} or {@code exclude} list. In each object, a scalar
 * value that the predicate accepts (given the key and the value text) is raised like a step value; an object that
 * becomes equal to another object of the list (the same keys with the same scalar values, in any order) is dropped.
 */
final class MatrixEntries {
    private MatrixEntries() {}

    static RaisedDuplicates.Result raise(Yaml.Sequence objects, BiPredicate<String, String> raisable, int minimum) {
        List<Yaml.Sequence.Entry> raised =
                objects.getEntries().stream().map(object -> raise(object, raisable, minimum)).toList();
        return RaisedDuplicates.drop(objects, raised, MatrixEntries::key);
    }

    private static Yaml.Sequence.Entry raise(
            Yaml.Sequence.Entry object, BiPredicate<String, String> raisable, int minimum) {
        if (!(object.getBlock() instanceof Yaml.Mapping mapping)) {
            return object;
        }
        List<Yaml.Mapping.Entry> values = mapping.getEntries();
        List<Yaml.Mapping.Entry> raised = values.stream().map(value -> raise(value, raisable, minimum)).toList();
        return IntStream.range(0, values.size()).allMatch(i -> values.get(i) == raised.get(i))
                ? object
                : object.withBlock(mapping.withEntries(raised));
    }

    private static Yaml.Mapping.Entry raise(
            Yaml.Mapping.Entry value, BiPredicate<String, String> raisable, int minimum) {
        return value.getValue() instanceof Yaml.Scalar scalar
                        && raisable.test(value.getKey().getValue(), VersionScalar.content(scalar))
                ? value.withValue(VersionScalar.raise(scalar, minimum))
                : value;
    }

    /** Equal for objects with the same keys and scalar values in any order; any other object is unique. */
    private static String key(Yaml.Sequence.Entry object) {
        return object.getBlock() instanceof Yaml.Mapping mapping
                        && mapping.getEntries().stream().allMatch(value -> value.getValue() instanceof Yaml.Scalar)
                ? mapping.getEntries().stream().map(MatrixEntries::pair).sorted().collect(Collectors.joining("\n"))
                : object.getId().toString();
    }

    private static String pair(Yaml.Mapping.Entry value) {
        return value.getKey().getValue() + "=" + VersionScalar.content((Yaml.Scalar) value.getValue());
    }
}
