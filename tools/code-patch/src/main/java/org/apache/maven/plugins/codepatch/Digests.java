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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 in lower-case hex; {@link #ABSENT} stands for a file that does not exist. */
final class Digests {
    static final String ABSENT = "-";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");

    private Digests() { }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        MessageDigest digest = sha256();
        digest.update(bytes);
        return hex(digest);
    }

    /** The SHA-256 of a file, or {@link #ABSENT} when there is no regular file. */
    static String ofFile(Path file) throws IOException {
        return Files.isRegularFile(file) ? sha256(Files.readAllBytes(file)) : ABSENT;
    }

    /** Refuses anything but 64 lower-case hex digits, so an id is never a path. */
    static String id(String value, String what) throws PatchException {
        String id = value == null ? "" : value.strip();
        if (!ID.matcher(id).matches()) throw new PatchException(what + " must be a 64-hex-digit plan id, got '" + id + "'");
        return id;
    }
}
