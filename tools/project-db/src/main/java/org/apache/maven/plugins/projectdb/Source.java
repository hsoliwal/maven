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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * One unit of build data to ingest: a kind, the module it belongs to, its project-relative path and the files whose
 * bytes it covers. Its SHA-256 over kind, path and every file's name and bytes is the incremental key: an unchanged
 * source is never parsed again.
 *
 * @param module the module directory relative to the project root
 * @param path   the project-relative path of the source ({@code /} separated)
 * @param kind   the parser that reads it
 * @param files  the files it covers, in a stable order
 * @param sha    the content hash of the source
 */
public record Source(String module, String path, String kind, List<Path> files, String sha) {
    /** Parser version; bumping it re-parses every source once. */
    static final String PARSER_VERSION = "v1";

    static Source of(String module, String path, String kind, List<Path> files) throws IOException {
        MessageDigest digest = sha256();
        frame(digest, PARSER_VERSION + "\t" + kind + "\t" + path);
        for (Path file : files) {
            frame(digest, file.getFileName().toString());
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65_536];
                for (int n = in.read(buffer); n > 0; n = in.read(buffer)) digest.update(buffer, 0, n);
            }
        }
        return new Source(module, path, kind, List.copyOf(files), HexFormat.of().formatHex(digest.digest()));
    }

    /** The catalog key: one entry per module and path. */
    String key() {
        return module + "|" + path;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha256(String text) {
        return HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
