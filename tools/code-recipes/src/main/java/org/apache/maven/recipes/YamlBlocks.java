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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.openrewrite.Cursor;
import org.openrewrite.yaml.tree.Yaml;

/** Read-only navigation over YAML trees: child values by key, scalar text, and the key path of a cursor. */
final class YamlBlocks {
    /** Stands for one sequence level in a key path. */
    static final String SEQUENCE = "[]";

    private YamlBlocks() {}

    static Optional<Yaml.Block> child(Yaml.Block block, String key) {
        return block instanceof Yaml.Mapping mapping
                ? mapping.getEntries().stream()
                        .filter(entry -> key.equals(entry.getKey().getValue()))
                        .map(Yaml.Mapping.Entry::getValue)
                        .findFirst()
                : Optional.empty();
    }

    static Optional<String> scalar(Yaml.Block block) {
        return block instanceof Yaml.Scalar scalar ? Optional.of(scalar.getValue()) : Optional.empty();
    }

    static Stream<Yaml.Block> entries(Yaml.Block block) {
        return block instanceof Yaml.Sequence sequence
                ? sequence.getEntries().stream().map(Yaml.Sequence.Entry::getBlock)
                : Stream.empty();
    }

    /** The keys from the document root down to the cursor, with {@link #SEQUENCE} for each sequence level. */
    static List<String> keyPath(Cursor cursor) {
        List<String> keys = cursor.getPathAsStream()
                .filter(value -> value instanceof Yaml.Mapping.Entry || value instanceof Yaml.Sequence)
                .map(value -> value instanceof Yaml.Mapping.Entry entry ? entry.getKey().getValue() : SEQUENCE)
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(keys);
        return keys;
    }
}
