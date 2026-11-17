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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Puts the compile database where clangd looks: a copy at the project root (or a configured path), or a
 * {@code .clangd} file whose {@code CompileFlags: CompilationDatabase:} points into the build tree. A file the plugin
 * did not write is never replaced: what the plugin writes is recorded as a SHA-256 next to the build tree, and an
 * existing file with another hash and other content is kept.
 */
public final class CompileCommandsPublisher {
    /** How the database is published. */
    public enum Publish {
        /** Copy the database. */
        COPY,
        /** Write a .clangd pointer. */
        CLANGD_CONFIG,
        /** Leave it in the build tree. */
        NONE;

        /** The mode named by a parameter value such as {@code clangd-config}. */
        public static Publish parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    /** What happened to the published file. */
    public enum Outcome {
        /** Written (new, or replacing what the plugin wrote before). */
        WROTE,
        /** Already held exactly this content. */
        UNCHANGED,
        /** Someone else's file, left alone. */
        KEPT_FOREIGN,
        /** Publishing is switched off. */
        SKIPPED
    }

    private CompileCommandsPublisher() {}

    /**
     * Publishes {@code database} to {@code target}; {@code ownership} records the hash of what was written,
     * {@code databaseDirectory} is the project-relative folder a .clangd file points to.
     */
    public static Outcome publish(Publish mode, Path database, Path target, Path ownership, String databaseDirectory)
            throws IOException {
        return switch (mode) {
            case COPY -> place(target, Files.readAllBytes(database), ownership);
            case CLANGD_CONFIG -> place(target, clangd(databaseDirectory).getBytes(StandardCharsets.UTF_8), ownership);
            case NONE -> Outcome.SKIPPED;
        };
    }

    /** The .clangd text. */
    public static String clangd(String databaseDirectory) {
        return "CompileFlags:\n  CompilationDatabase: " + databaseDirectory + "\n";
    }

    /** Writes {@code content} to {@code target} unless the existing file belongs to someone else. */
    public static Outcome place(Path target, byte[] content, Path ownership) throws IOException {
        byte[] current = Files.exists(target) ? Files.readAllBytes(target) : null;
        if (current != null && !Arrays.equals(current, content) && !recorded(ownership).equals(sha256(current))) {
            return Outcome.KEPT_FOREIGN;
        }
        boolean same = current != null && Arrays.equals(current, content);
        if (!same) {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, content);
        }
        TaskFileWriter.write(ownership, sha256(content) + "\n");
        return same ? Outcome.UNCHANGED : Outcome.WROTE;
    }

    private static String recorded(Path ownership) throws IOException {
        return Files.isRegularFile(ownership) ? Files.readString(ownership, StandardCharsets.UTF_8).strip() : "";
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
