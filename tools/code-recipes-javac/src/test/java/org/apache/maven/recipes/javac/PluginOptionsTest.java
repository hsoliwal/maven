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
package org.apache.maven.recipes.javac;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginOptionsTest {
    @Test
    void withoutArgumentsThereIsNoCensusAndTheRootIsTheWorkingDirectory() {
        PluginOptions options = PluginOptions.parse();
        assertEquals(Optional.empty(), options.census());
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), options.root());
    }

    @Test
    void censusAndRootAreRead() {
        PluginOptions options = PluginOptions.parse("census=target/recipes/LST_CENSUS.tsv", "root=src/main/java");
        assertEquals(Optional.of(Path.of("target/recipes/LST_CENSUS.tsv")), options.census());
        assertTrue(options.root().isAbsolute());
        assertTrue(options.root().endsWith(Path.of("src", "main", "java")));
    }

    @Test
    void anUnknownKeyOrAMissingValueFailsInsteadOfDroppingTheCensus() {
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("cencus=x.tsv"));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("census"));
    }
}
