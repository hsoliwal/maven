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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.M3BuildFingerprintService;

/**
 * Versioned canonical hash of producer-owned metadata. No second filesystem
 * scanner, resolver, cache controller, toolchain discovery or goal runner.
 */
@Named
@Singleton
public final class DefaultM3BuildFingerprintService implements M3BuildFingerprintService {
    private static final byte[] DOMAIN = "m3.build.inputs.fingerprint.v1".getBytes(StandardCharsets.UTF_8);
    private static final Comparator<Input> ORDER = Comparator.comparing(Input::relativePath)
            .thenComparing(input -> input.kind().name());

    @Override
    public Fingerprint fingerprint(Request request) {
        Objects.requireNonNull(request, "request");
        Context context = request.context();
        Input[] facts = request.inputs().toArray(Input[]::new);
        Arrays.sort(facts, ORDER);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        digest.update(DOMAIN);
        writeString(digest, context.project());
        writeString(digest, context.pluginGa());
        writeString(digest, context.pluginVersion());
        writeString(digest, context.goal());
        writeString(digest, context.execution());
        writeString(digest, context.configurationSha256());
        writeString(digest, context.toolchainSha256());
        writeString(digest, context.dependenciesSha256());
        writeString(digest, context.environmentSha256());
        writeInt(digest, facts.length);

        long total = 0;
        Input preceding = null;
        for (Input fact : facts) {
            if (preceding != null && ORDER.compare(preceding, fact) == 0) {
                throw new IllegalArgumentException("duplicate build input path and role");
            }
            preceding = fact;
            try {
                total = Math.addExact(total, fact.bytes());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("build input byte total overflow", overflow);
            }
            writeString(digest, fact.relativePath());
            writeString(digest, fact.kind().name());
            writeString(digest, fact.sha256());
            writeLong(digest, fact.bytes());
        }
        return new Fingerprint(HexFormat.of().formatHex(digest.digest()), facts.length, total);
    }

    /** All strings are UTF-8 length-prefixed, avoiding delimiter ambiguity. */
    private static void writeString(MessageDigest digest, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeInt(digest, utf8.length);
        digest.update(utf8);
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void writeLong(MessageDigest digest, long value) {
        writeInt(digest, (int) (value >>> 32));
        writeInt(digest, (int) value);
    }
}
