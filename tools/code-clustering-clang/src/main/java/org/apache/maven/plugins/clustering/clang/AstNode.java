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
package org.apache.maven.plugins.clustering.clang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of clang's JSON syntax tree, as far as the front end needs it: the kind, the name, the scalar attributes
 * as written ({@code opcode}, {@code castKind}, {@code value}, {@code isImplicit}, {@code completeDefinition},
 * {@code parentDeclContextId}, ...), three names taken from nested objects ({@code type}: the type as written,
 * {@code ref}: the referenced declaration, {@code interface}: an Objective-C category's class), the range, and the
 * children that were kept. Attributes are kept only for nodes in the main file.
 */
final class AstNode {
    String id = "";
    String kind = "";
    String name = "";
    AstLoc loc;
    AstLoc begin;
    AstLoc end;
    final Map<String, String> attrs = new HashMap<>();
    final List<AstNode> children = new ArrayList<>();

    String attr(String key) {
        return attrs.getOrDefault(key, "");
    }

    boolean flag(String key) {
        return "true".equals(attrs.get(key));
    }

    /** Where the node starts: its range's first location, else its own location; null when the dump has neither. */
    AstLoc start() {
        return begin != null ? begin : loc;
    }

    /** Where the node ends: its range's last location, else its own location. */
    AstLoc last() {
        return end != null ? end : loc;
    }
}
