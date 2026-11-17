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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.tree.ParseError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Valid sources that rewrite 8.89.0 cannot turn into a tree that prints them back: a receiver parameter is dropped, a
 * multi-u unicode escape throws, lone CR line ends throw, and a unicode-escaped line feed in a line comment breaks the
 * printout. The classic parser gives a ParseError for each; the bridge refuses each (print mismatch or failed) and
 * hands no tree to recipes. A rewrite upgrade that fixes one shows up here as a test to update.
 */
class KnownRewriteDefectsTest {
    @TestFactory
    Stream<DynamicTest> theBridgeAndTheClassicParserBothRefuseEachCase() {
        return EdgeFixtures.rewrite8890Defects().stream()
                .map(fixture -> dynamicTest(fixture.name(), () -> assertRefused(fixture)));
    }

    private static void assertRefused(Fixture fixture) throws IOException {
        Javac.Compilation compilation =
                Javac.compile("defects", fixture, Javac.Mode.DIRECT, CompilerTreeLstTest.BYFILE);
        assertTrue(compilation.success(), compilation::output);
        FileLst file = compilation.collector().orElseThrow().files().get(0);
        String status = file.row().status();
        assertTrue(
                status.startsWith(LstStatus.PRINT_MISMATCH.label()) || status.startsWith(LstStatus.FAILED.label()),
                file.row()::tsv);
        assertTrue(file.lst().isEmpty(), file.row()::tsv);
        assertInstanceOf(ParseError.class, classic(fixture, file.row().path()), file.row()::tsv);
    }

    private static SourceFile classic(Fixture fixture, String path) {
        Path src = Javac.WORK
                .resolve("defects")
                .resolve(fixture.name())
                .resolve("src")
                .toAbsolutePath();
        List<SourceFile> parsed = JavaParser.fromJavaVersion()
                .build()
                .parse(List.of(src.resolve(path)), src, new InMemoryExecutionContext())
                .toList();
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }
}
