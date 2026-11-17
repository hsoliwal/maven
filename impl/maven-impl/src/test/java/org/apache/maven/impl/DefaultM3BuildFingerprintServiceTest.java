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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.api.services.M3BuildFingerprintService;
import org.apache.maven.api.services.M3BuildFingerprintService.Context;
import org.apache.maven.api.services.M3BuildFingerprintService.Input;
import org.apache.maven.api.services.M3BuildFingerprintService.Kind;
import org.apache.maven.api.services.M3BuildFingerprintService.Request;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DefaultM3BuildFingerprintServiceTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String ONE = "1".repeat(64);
    private static final String TWO = "2".repeat(64);
    private final M3BuildFingerprintService service = new DefaultM3BuildFingerprintService();

    @Test
    void mavenInjectorExposesNativeFingerprintService() {
        Injector injector = Injector.create();
        try {
            injector.bindImplicit(DefaultM3BuildFingerprintService.class);
            var bound = injector.getInstance(M3BuildFingerprintService.class);
            assertInstanceOf(DefaultM3BuildFingerprintService.class, bound);
            assertSame(bound, injector.getInstance(M3BuildFingerprintService.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void canonicalFingerprintIsOrderIndependentAndMatchesVersionedGoldenDigest() {
        Input java = new Input("src/main/java/A.java", Kind.SOURCE, ONE, 11);
        Input resource = new Input("src/main/resources/config.xml", Kind.RESOURCE, TWO, 23);
        var first = service.fingerprint(new Request(context(), List.of(resource, java)));
        var second = service.fingerprint(new Request(context(), List.of(java, resource)));

        assertEquals("007f41c16f571f9656efd7b54baac44d7319aaeb1bd32fb2224734e6dcef84ce", first.sha256());
        assertEquals(first, second);
        assertEquals(2, first.inputCount());
        assertEquals(34, first.inputBytes());
    }

    @Test
    void changingEachInputDimensionChangesTheKeyWithoutReadingFilesystem() {
        Input java = new Input("src/main/java/A.java", Kind.SOURCE, ONE, 11);
        String expected =
                service.fingerprint(new Request(context(), List.of(java))).sha256();
        assertNotEquals(
                expected,
                service.fingerprint(new Request(
                                context(), List.of(new Input("src/main/java/B.java", Kind.SOURCE, ONE, 11))))
                        .sha256());
        assertNotEquals(
                expected,
                service.fingerprint(new Request(
                                context(), List.of(new Input("src/main/java/A.java", Kind.RESOURCE, ONE, 11))))
                        .sha256());
        assertNotEquals(
                expected,
                service.fingerprint(new Request(
                                context(), List.of(new Input("src/main/java/A.java", Kind.SOURCE, TWO, 11))))
                        .sha256());
        assertNotEquals(
                expected,
                service.fingerprint(new Request(
                                context(), List.of(new Input("src/main/java/A.java", Kind.SOURCE, ONE, 12))))
                        .sha256());

        Context changed = new Context(
                "demo:demo:1",
                "org.apache.maven.plugins:maven-resources-plugin",
                "3.3.1",
                "testResources",
                "default-resources",
                A,
                B,
                C,
                D);
        assertNotEquals(
                expected,
                service.fingerprint(new Request(changed, List.of(java))).sha256());
    }

    @Test
    void duplicatePathAndRoleCannotSilentlyChangeFingerprintSemantics() {
        Input java = new Input("src/main/java/A.java", Kind.SOURCE, ONE, 11);
        assertThrows(
                IllegalArgumentException.class, () -> service.fingerprint(new Request(context(), List.of(java, java))));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.fingerprint(
                        new Request(context(), List.of(java, new Input(java.relativePath(), Kind.SOURCE, TWO, 12)))));
        // A producer can legitimately declare one path in two distinct roles.
        assertEquals(
                2,
                service.fingerprint(new Request(
                                context(), List.of(java, new Input(java.relativePath(), Kind.CONFIGURATION, ONE, 11))))
                        .inputCount());
    }

    @Test
    void rejectsUncanonicalPathsInvalidHashesAndOverflowingMetadata() {
        for (String path : List.of("/absolute", "a/../b", "a/./b", "a//b", "a\\b", "C:/drive", "a/")) {
            assertThrows(IllegalArgumentException.class, () -> new Input(path, Kind.SOURCE, ONE, 1), path);
        }
        assertThrows(IllegalArgumentException.class, () -> new Input("a", Kind.SOURCE, "invalid", 1));
        assertThrows(IllegalArgumentException.class, () -> new Input("a", Kind.SOURCE, ONE, -1));
        assertThrows(
                IllegalArgumentException.class, () -> new Input(String.valueOf((char) 0xD800), Kind.SOURCE, ONE, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Context(String.valueOf((char) 0xDC00), "plugin", "1", "goal", "execution", A, B, C, D));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Context("demo", "group:plugin", "1", "goal", "execution", "bad", B, C, D));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Context("demo", "plugin", "1", "goal", "execution", A, B, C, D));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.fingerprint(new Request(
                        context(),
                        List.of(
                                new Input("a", Kind.SOURCE, ONE, Long.MAX_VALUE),
                                new Input("b", Kind.SOURCE, TWO, 1)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Request(
                        context(),
                        Collections.nCopies(
                                M3BuildFingerprintService.MAX_INPUTS + 1, new Input("a", Kind.SOURCE, ONE, 1))));
    }

    @Test
    void requestRetainsItsOwnImmutableInputMetadataSnapshot() {
        List<Input> mutable = new ArrayList<>();
        mutable.add(new Input("src/main/java/A.java", Kind.SOURCE, ONE, 11));
        Request request = new Request(context(), mutable);
        String before = service.fingerprint(request).sha256();
        mutable.clear();
        assertEquals(before, service.fingerprint(request).sha256());
        assertThrows(UnsupportedOperationException.class, () -> request.inputs().clear());
    }

    private static Context context() {
        return new Context(
                "demo:demo:1",
                "org.apache.maven.plugins:maven-resources-plugin",
                "3.3.1",
                "resources",
                "default-resources",
                A,
                B,
                C,
                D);
    }
}
