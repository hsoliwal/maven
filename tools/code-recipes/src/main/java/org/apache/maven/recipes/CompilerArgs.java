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
import java.util.stream.IntStream;

import org.openrewrite.Cursor;
import org.openrewrite.xml.tree.Xml;

/**
 * Reads the Java level a {@code <compilerArgs>} entry passes to javac: the value of {@code --release=25},
 * {@code --source=25} or {@code --target=25}, or the entry that follows a {@code --release}, {@code --source},
 * {@code -source}, {@code --target} or {@code -target} entry. Other entries pass no level.
 */
final class CompilerArgs {
    private static final Set<String> LEVEL_OPTIONS = Set.of("--release", "--source", "-source", "--target", "-target");
    private static final Pattern LEVEL_ASSIGNMENT = Pattern.compile("--(release|source|target)=(.*)");

    private CompilerArgs() {}

    /** The level text of the compiler argument tag at the cursor, before {@code ${...}} resolution. */
    static Optional<String> level(Cursor cursor) {
        Xml.Tag arg = cursor.getValue();
        Optional<String> value = arg.getValue().map(String::trim);
        Optional<String> assigned = value.map(LEVEL_ASSIGNMENT::matcher).filter(Matcher::matches).map(m -> m.group(2));
        return assigned.isPresent() ? assigned : value.filter(v -> previous(cursor, arg).isPresent());
    }

    /** The previous {@code <compilerArgs>} entry, when it is a level option. */
    private static Optional<String> previous(Cursor cursor, Xml.Tag arg) {
        Object parent = cursor.getParentTreeCursor().getValue();
        List<Xml.Tag> args = parent instanceof Xml.Tag compilerArgs ? compilerArgs.getChildren() : List.of();
        return IntStream.range(1, args.size())
                .filter(i -> args.get(i).getId().equals(arg.getId()))
                .mapToObj(i -> args.get(i - 1).getValue().map(String::trim))
                .flatMap(Optional::stream)
                .filter(LEVEL_OPTIONS::contains)
                .findFirst();
    }
}
