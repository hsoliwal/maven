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
package org.apache.maven.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.services.ToolchainManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the additive ToolchainManager API without an installed JDK inventory. */
final class ToolchainSelectionApiTest {

    @Test
    void matchingActiveToolchainWinsWithoutListingOrStoring() {
        Fixture manager = new Fixture();
        Session session = mock(Session.class);
        Toolchain active = tool("jdk", Map.of("version", "21"), true);
        manager.active = Optional.of(active);
        manager.available = List.of(tool("jdk", Map.of("version", "21"), true));

        assertSame(
                active,
                manager.selectToolchain(session, "jdk", Map.of("version", "21")).orElseThrow());
        assertEquals(0, manager.searches);
        assertEquals(0, manager.writes);
    }

    @Test
    void nonmatchingActiveToolchainFallsBackToExistingProviderOrder() {
        Fixture manager = new Fixture();
        Session session = mock(Session.class);
        Map<String, String> required = Map.of("version", "21");
        manager.active = Optional.of(tool("jdk", required, false));
        Toolchain wrong = tool("compiler", required, true);
        Toolchain selected = tool("jdk", required, true);
        manager.available = List.of(wrong, selected);

        assertSame(selected, manager.selectToolchain(session, "jdk", required).orElseThrow());
        assertEquals(1, manager.searches);
        assertEquals(0, manager.writes);
    }

    @Test
    void absenceAndOptionalRequirementsNeverModifyBuildContext() {
        Fixture manager = new Fixture();
        Session session = mock(Session.class);
        assertFalse(
                manager.selectToolchain(session, "jdk", Map.of("version", "25")).isPresent());
        assertEquals(1, manager.searches);

        Toolchain fallback = tool("jdk", Map.of(), true);
        manager.available = List.of(fallback);
        assertSame(fallback, manager.selectToolchain(session, "jdk", null).orElseThrow());
        assertEquals(0, manager.writes);
        assertThrows(NullPointerException.class, () -> manager.selectToolchain(null, "jdk", Map.of()));
        assertThrows(NullPointerException.class, () -> manager.selectToolchain(session, null, Map.of()));
    }

    private static Toolchain tool(String type, Map<String, String> requirements, boolean matches) {
        Toolchain toolchain = mock(Toolchain.class);
        when(toolchain.getType()).thenReturn(type);
        when(toolchain.matchesRequirements(requirements)).thenReturn(matches);
        return toolchain;
    }

    private static final class Fixture implements ToolchainManager {
        private Optional<Toolchain> active = Optional.empty();
        private List<Toolchain> available = List.of();
        private int searches;
        private int writes;

        @Override
        public List<Toolchain> getToolchains(Session session, String type, Map<String, String> requirements) {
            searches++;
            return available;
        }

        @Override
        public Optional<Toolchain> getToolchainFromBuildContext(Session session, String type) {
            return active;
        }

        @Override
        public void storeToolchainToBuildContext(Session session, Toolchain toolchain) {
            writes++;
        }
    }
}
