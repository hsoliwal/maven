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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A small tree and a git-style patch over it: a CRLF file, a file without a final newline (kept by the patch), a new
 * file without a final newline, a deleted file, a pure rename and a rename with a change.
 */
final class Fixtures {
    static final Map<String, String> TREE = Map.of(
            "src/crlf.txt", "one\r\ntwo\r\nthree\r\n",
            "src/nonl.txt", "a\nb\nc",
            "src/gone.txt", "bye\n",
            "src/old.txt", "x1\nx2\nx3 caf\u00e9\n",
            "src/keep.txt", "k\n");

    static final String SERIES = String.join("",
            "diff --git a/src/crlf.txt b/src/crlf.txt\n", "index 1111111..2222222 100644\n",
            "--- a/src/crlf.txt\n", "+++ b/src/crlf.txt\n", "@@ -1,3 +1,3 @@\n",
            " one\r\n", "-two\r\n", "+TWO\r\n", " three\r\n",
            "diff --git a/src/gone.txt b/src/gone.txt\n", "deleted file mode 100644\n", "index 3333333..0000000\n",
            "--- a/src/gone.txt\n", "+++ /dev/null\n", "@@ -1 +0,0 @@\n", "-bye\n",
            "diff --git a/src/keep.txt b/src/kept.txt\n", "similarity index 100%\n",
            "rename from src/keep.txt\n", "rename to src/kept.txt\n",
            "diff --git a/src/new.txt b/src/new.txt\n", "new file mode 100644\n", "index 0000000..4444444\n",
            "--- /dev/null\n", "+++ b/src/new.txt\n", "@@ -0,0 +1,2 @@\n", "+fresh\n", "+last\n",
            "\\ No newline at end of file\n",
            "diff --git a/src/nonl.txt b/src/nonl.txt\n", "index 5555555..6666666 100644\n",
            "--- a/src/nonl.txt\n", "+++ b/src/nonl.txt\n", "@@ -1,3 +1,3 @@\n", " a\n", "-b\n", "+B\n", " c\n",
            "\\ No newline at end of file\n",
            "diff --git a/src/old.txt b/src/moved.txt\n", "similarity index 66%\n",
            "rename from src/old.txt\n", "rename to src/moved.txt\n", "index 7777777..8888888 100644\n",
            "--- a/src/old.txt\n", "+++ b/src/moved.txt\n", "@@ -1,3 +1,3 @@\n", " x1\n", "-x2\n", "+X2\n",
            " x3 caf\u00e9\n");

    /** The tree after SERIES: every file that exists then, with its content. */
    static final Map<String, String> AFTER = Map.of(
            "src/crlf.txt", "one\r\nTWO\r\nthree\r\n",
            "src/nonl.txt", "a\nB\nc",
            "src/new.txt", "fresh\nlast",
            "src/moved.txt", "x1\nX2\nx3 caf\u00e9\n",
            "src/kept.txt", "k\n");

    private Fixtures() { }

    static Path tree(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) write(dir.resolve(file.getKey()), file.getValue());
        return dir;
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Every file under the base directory except {@code target/}, as path to the SHA-256 of its bytes. */
    static Map<String, String> snapshot(Path base) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String path = base.relativize(file).toString().replace('\\', '/');
                if (!path.startsWith("target/")) files.put(path, Digests.sha256(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    /** Path to the SHA-256 of the content's UTF-8 bytes, comparable with {@link #snapshot(Path)}. */
    static Map<String, String> digests(Map<String, String> files) {
        Map<String, String> digests = new TreeMap<>();
        files.forEach((path, content) -> digests.put(path, Digests.sha256(content.getBytes(StandardCharsets.UTF_8))));
        return digests;
    }
}
