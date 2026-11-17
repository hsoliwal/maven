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
package org.apache.maven.plugins.codepatch;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.apache.maven.plugins.clustering.PlaneCompare.State;

/**
 * The states that fail {@code verify}: code clustering states for atoms, and {@link #NO_ATOMS} for changed files that
 * give no atoms at all (verify cannot compare those).
 */
public record FailOn(Set<State> states, boolean noAtoms) {
    /** The state of a changed file without atoms on either side. */
    public static final String NO_ATOMS = "NO_ATOMS";

    public FailOn {
        states = Set.copyOf(states);
    }

    /** Parses a comma-separated list of states; blank means none. An unknown name is refused, naming the known ones. */
    public static FailOn parse(String list) throws PatchException {
        Set<State> states = EnumSet.noneOf(State.class);
        boolean noAtoms = false;
        for (String raw : list == null ? new String[0] : list.split(",")) {
            String name = raw.strip();
            if (name.equals(NO_ATOMS)) {
                noAtoms = true;
            } else if (!name.isEmpty()) {
                states.add(state(name));
            }
        }
        return new FailOn(states, noAtoms);
    }

    private static State state(String name) throws PatchException {
        try {
            return State.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new PatchException("patch.failOn: unknown state " + name + "; known: " + Arrays.toString(State.values())
                    + " and " + NO_ATOMS);
        }
    }
}
