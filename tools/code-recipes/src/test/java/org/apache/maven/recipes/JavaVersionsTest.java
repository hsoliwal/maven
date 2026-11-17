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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class JavaVersionsTest {
    @Test
    void readsMajorVersions() {
        assertEquals(OptionalInt.of(17), JavaVersions.major("17"));
        assertEquals(OptionalInt.of(17), JavaVersions.major(" 17.0.2 "));
        assertEquals(OptionalInt.of(25), JavaVersions.major("25-ea"));
        assertEquals(OptionalInt.of(8), JavaVersions.major("1.8"));
        assertEquals(OptionalInt.of(11), JavaVersions.major("11"));
    }

    @Test
    void hasNoMajorVersionForNamesAndExpressions() {
        assertEquals(OptionalInt.empty(), JavaVersions.major("lts"));
        assertEquals(OptionalInt.empty(), JavaVersions.major("${{ matrix.java }}"));
        assertEquals(OptionalInt.empty(), JavaVersions.major("${java.version}"));
        assertEquals(OptionalInt.empty(), JavaVersions.major("17.x"));
    }

    @Test
    void comparesWithAMinimum() {
        assertTrue(JavaVersions.below("17", 21));
        assertFalse(JavaVersions.below("21", 21));
        assertFalse(JavaVersions.below("lts", 21));
    }
}
