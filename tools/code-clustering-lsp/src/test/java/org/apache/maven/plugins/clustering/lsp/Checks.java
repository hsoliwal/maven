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
package org.apache.maven.plugins.clustering.lsp;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/** PASS, FAIL and SKIP lines on standard error for the test mains, and the failure count at the end. */
final class Checks {
    private final String suite;
    private int failures;

    Checks(String suite) {
        this.suite = suite;
    }

    boolean check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
        return ok;
    }

    void skip(String name, String why) {
        System.err.println("SKIP " + name + ": " + why);
    }

    void finish() {
        if (failures > 0) throw new AssertionError(failures + " " + suite + " checks failed");
        System.err.println(suite + ": all checks passed");
    }

    /** {@code KIND#symbol} of each atom, in order. */
    static List<String> names(List<Atom> atoms) {
        return atoms.stream().map(a -> a.kind() + "#" + a.symbol()).collect(Collectors.toList());
    }
}
