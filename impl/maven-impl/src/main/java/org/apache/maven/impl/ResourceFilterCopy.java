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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.UnaryOperator;

import org.apache.maven.api.services.ResourceFilterRequest.ChangeDetection;

/**
 * Writes one target file under a {@link ChangeDetection} rule, either as a byte copy or as filtered text.
 * <p>
 * The rules are those of maven-filtering 3.x {@code FilteringUtils.copyFile} (Apache-2.0): {@code NEVER} writes a
 * missing target only; {@code ALWAYS} writes; {@code TIMESTAMP} writes when the target is older than the source
 * ({@code File.lastModified}, 0 for a missing file); {@code CONTENT} writes when the bytes differ;
 * {@code TIMESTAMP_AND_CONTENT} writes when both hold. Text is decoded strictly (malformed input fails) and encoded
 * with replacement of unmappable characters, as the {@code CachingWriter} of the {@code CONTENT} default does.
 * A read-only target is made writable first, and POSIX permissions are copied from the source when the file
 * system supports them, as maven-filtering does.
 *
 * @param source the source file
 * @param target the target file
 * @param encoding the text encoding, or {@code null} for a byte copy
 * @param filter wraps the decoding reader with the expression filter; unused for a byte copy
 */
record ResourceFilterCopy(Path source, Path target, Charset encoding, UnaryOperator<Reader> filter) {

    /**
     * Writes the target if the rule asks for it.
     *
     * @param rule when an existing target is overwritten
     * @return whether the target was written
     * @throws IOException if reading, decoding or writing fails
     */
    boolean copy(ChangeDetection rule) throws IOException {
        return switch (rule) {
            case NEVER -> !Files.isRegularFile(target) && write(true);
            case ALWAYS -> write(true);
            case TIMESTAMP -> sourceIsNewer() && write(true);
            case CONTENT -> write(false);
            case TIMESTAMP_AND_CONTENT -> sourceIsNewer() && write(false);
        };
    }

    private boolean sourceIsNewer() {
        return target.toFile().lastModified() < source.toFile().lastModified();
    }

    private boolean write(boolean unconditionally) throws IOException {
        byte[] content = encoding == null ? null : filtered();
        if (!unconditionally && Files.isRegularFile(target) && unchanged(content)) {
            return false;
        }
        makeWritable();
        if (content == null) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.write(target, content);
        }
        copyPermissions();
        return true;
    }

    /** Whether the target already holds the content ({@code null}: the source bytes). */
    private boolean unchanged(byte[] content) throws IOException {
        return content == null
                ? Files.mismatch(source, target) == -1L
                : Arrays.equals(content, Files.readAllBytes(target));
    }

    private byte[] filtered() throws IOException {
        try (Reader reader = filter.apply(Files.newBufferedReader(source, encoding));
                StringWriter writer = new StringWriter()) {
            reader.transferTo(writer);
            return writer.toString().getBytes(encoding);
        }
    }

    private void makeWritable() {
        if (Files.exists(target) && !Files.isWritable(target)) {
            target.toFile().setWritable(true);
        }
    }

    /** Best effort, as maven-filtering: failures to copy permissions are ignored. */
    private void copyPermissions() throws IOException {
        try {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
        } catch (UnsupportedOperationException | FileSystemException e) {
            File from = source.toFile();
            File to = target.toFile();
            to.setExecutable(from.canExecute());
            to.setReadable(from.canRead());
            to.setWritable(from.canWrite());
        }
    }
}
