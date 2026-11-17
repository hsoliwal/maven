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
package org.apache.maven.plugins.clustering;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Which {@code Java_} functions that no scanned native method names are ORPHAN_NATIVE. Only those whose name starts
 * with a scanned package ({@code Java_p_q_}, from the classes the javac front ends parsed): a {@code Java_} prefix on a
 * helper outside every scanned package ({@code Java_CTProvider}, a library's own Java side that is not scanned) is not
 * reported. And not the functions of an anonymous or local class of a scanned class ({@code Java_p_Outer_000241_run},
 * from {@code p/Outer$1}: {@code _00024} and a digit after the escaped class), whose native methods are left out
 * because their binary names depend on the compiler's numbering.
 */
final class JniOrphans {
    private static final String PREFIX = "Java_";
    private static final String NUMBERED = "_00024";
    private final Set<String> classes;
    private final Set<String> packages;

    /** {@code scanned}: the binary names in internal form of every class the javac front ends parsed. */
    JniOrphans(Set<String> scanned) {
        this.classes = scanned.stream().map(JniNames::escape).collect(Collectors.toSet());
        this.packages = scanned.stream().map(c -> c.substring(0, Math.max(0, c.lastIndexOf('/')))).map(JniNames::escape)
                .collect(Collectors.toSet());
    }

    boolean reported(String function) {
        List<Integer> cuts = IntStream.range(PREFIX.length(), function.length()).filter(i -> function.charAt(i) == '_')
                .boxed().toList();
        boolean scannedPackage = packages.contains("")
                || cuts.stream().anyMatch(i -> packages.contains(function.substring(PREFIX.length(), i)));
        return scannedPackage && cuts.stream().noneMatch(i -> local(function, i));
    }

    /** Whether the class part before {@code i} is a scanned class and {@code $} and a digit follow. */
    private boolean local(String function, int i) {
        int digit = i + NUMBERED.length();
        return function.startsWith(NUMBERED, i) && digit < function.length() && Character.isDigit(function.charAt(digit))
                && classes.contains(function.substring(PREFIX.length(), i));
    }
}
