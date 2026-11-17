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
    private static final Path SPACED =
            Path.of("dir with spaces", "census file.tsv").toAbsolutePath();

    /** Splits like javac's {@code Arguments}: the {@code -Xplugin:} value on runs of whitespace. */
    private static PluginOptions parse(String value) {
        return PluginOptions.parse(value.split("\\s+"));
    }

    @Test
    void withoutArgumentsThereIsNoCensusTheRootIsTheWorkingDirectoryAndTheBuildDirectoryItsTarget() {
        PluginOptions options = PluginOptions.parse();
        assertEquals(Optional.empty(), options.census());
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), options.root());
        assertEquals(options.root().resolve("target"), options.buildDir());
    }

    @Test
    void censusRootAndBuildDirAreRead() {
        PluginOptions options =
                PluginOptions.parse("census=target/recipes/LST_CENSUS.tsv", "root=src/main/java", "buildDir=out");
        assertEquals(Optional.of(Path.of("target/recipes/LST_CENSUS.tsv")), options.census());
        assertTrue(options.root().isAbsolute());
        assertTrue(options.root().endsWith(Path.of("src", "main", "java")));
        assertEquals(Path.of("out").toAbsolutePath().normalize(), options.buildDir());
    }

    @Test
    void aPathThatJavacSplitAtItsSpacesIsJoinedBack() {
        PluginOptions options = parse("census=" + SPACED + " root=" + SPACED.getParent());
        assertEquals(Optional.of(SPACED), options.census());
        assertEquals(SPACED.getParent(), options.root());
    }

    @Test
    void quotedValuesAndFileUrisAreRead() {
        assertEquals(Optional.of(SPACED), parse("census=\"" + SPACED + "\"").census());
        assertEquals(Optional.of(SPACED), parse("census='" + SPACED + "'").census());
        assertEquals(Optional.of(SPACED), parse("census=" + SPACED.toUri()).census());
        assertEquals(
                SPACED.getParent(), parse("root=" + SPACED.getParent().toUri()).root());
    }

    @Test
    void anUnknownKeyAMissingValueOrAnEmptyPathIsRejectedWithItsReason() {
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("cencus=x.tsv"));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("census"));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("census="));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.parse("census=\"\""));
    }
}
