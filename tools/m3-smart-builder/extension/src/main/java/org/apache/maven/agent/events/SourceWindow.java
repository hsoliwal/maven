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
package org.apache.maven.agent.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The numbered source lines around a diagnostic, so a fix needs no extra file read: {@code context} lines (at most
 * 10) before and after the marked line, {@value #LINE_CHARS} characters per line. Only files inside the root are
 * read, judged by their real paths, so a symbolic link in the tree never shows a file outside it.
 */
final class SourceWindow {
    static final int LINE_CHARS = 240;
    static final int MAX_CONTEXT = 10;

    /** Lines {@code startLine..} of a file with {@code markLine} the diagnostic's line. */
    record Window(int startLine, int markLine, List<String> lines) {
        JsonText.Obj json() {
            return new JsonText.Obj().put("startLine", startLine).put("markLine", markLine).put("lines", lines);
        }
    }

    private SourceWindow() {}

    /** The window of root-relative {@code file} around {@code line}; empty when unreadable or outside the root. */
    static Optional<Window> read(Path root, String file, int line, int context) {
        Path base = root.toAbsolutePath().normalize();
        Optional<Path> source = resolve(base, file).filter(path -> path.startsWith(base) && inside(path, base));
        if (line < 1 || source.isEmpty()) {
            return Optional.empty();
        }
        int around = Math.max(0, Math.min(MAX_CONTEXT, context));
        int start = Math.max(1, line - around);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(source.get()), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().skip(start - 1L).limit(line + around - start + 1L)
                    .map(text -> JsonText.clip(text.replace("\t", "    "), LINE_CHARS)).collect(Collectors.toList());
            return lines.size() < line - start + 1 ? Optional.empty() : Optional.of(new Window(start, line, lines));
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            return Optional.empty();
        }
    }

    /** Whether {@code path} is a regular file whose real path (links resolved) lies under that of {@code base}. */
    private static boolean inside(Path path, Path base) {
        try {
            return Files.isRegularFile(path) && path.toRealPath().startsWith(base.toRealPath());
        } catch (IOException | SecurityException unresolved) {
            return false;
        }
    }

    /** {@code file} under {@code base}; empty when it is no path here, such as a model id or a URI on Windows. */
    private static Optional<Path> resolve(Path base, String file) {
        try {
            return Optional.of(base.resolve(file).normalize());
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
    }
}
