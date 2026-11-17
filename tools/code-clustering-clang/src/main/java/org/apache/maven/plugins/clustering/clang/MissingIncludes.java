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
package org.apache.maven.plugins.clustering.clang;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in stubs for headers clang cannot find ({@code clustering.clang.stubIncludes=<folder>}), for trees whose
 * generated headers do not exist yet (JNI headers from {@code javac -h}, {@code config.h}). Clang stops at a missing
 * header; with this option an empty file of that name is created in the stub folder, which is searched after every
 * other include folder, and the file is parsed again. Declarations from the missing header are then unknown, so
 * clang reports errors, but the tree of every definition in the file is complete. Only relative names without
 * {@code ..} are stubbed.
 */
final class MissingIncludes {
    private static final Pattern MISSING = Pattern.compile("fatal error: '([^']+)' file not found");

    private MissingIncludes() { }

    /** The header named by clang's "file not found" fatal error, if that is the error. */
    static Optional<String> missing(String errors) {
        Matcher m = MISSING.matcher(errors);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Creates an empty stub for the header; false when the name is not a safe relative path. */
    static boolean stub(Path stubs, String header) throws IOException {
        Optional<Path> target = target(stubs.toAbsolutePath().normalize(), header);
        if (target.isEmpty()) return false;
        Files.createDirectories(target.get().getParent());
        try {
            Files.createFile(target.get());
        } catch (FileAlreadyExistsException madeByAnotherFile) {
            return true;
        }
        return true;
    }

    private static Optional<Path> target(Path root, String header) {
        try {
            Path target = root.resolve(header).normalize();
            boolean inside = target.startsWith(root) && !target.equals(root) && !header.contains("..");
            return inside ? Optional.of(target) : Optional.empty();
        } catch (InvalidPathException unusable) {
            return Optional.empty();
        }
    }
}
