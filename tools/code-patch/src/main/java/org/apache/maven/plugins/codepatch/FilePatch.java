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

import java.util.List;

/**
 * One file section of a unified diff: the old and new path (relative, prefixes {@code a/} and {@code b/} removed),
 * what happens to the file, and its hunks in patch order.
 */
public record FilePatch(String oldPath, String newPath, Operation operation, List<Hunk> hunks) {
    /** What a section does to its file. */
    public enum Operation { ADD, MODIFY, DELETE, RENAME }

    public FilePatch {
        hunks = List.copyOf(hunks);
    }

    /** The path the section is reported under: the old path of a deleted file, the new path otherwise. */
    public String path() {
        return operation == Operation.DELETE ? oldPath : newPath;
    }

    /**
     * One hunk. {@code oldStart} is 1-based; when {@code oldCount} is 0 it is the line after which the new lines go
     * (0: at the start).
     */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {
        public Hunk {
            lines = List.copyOf(lines);
        }

        public String header() {
            return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@";
        }
    }

    /**
     * One hunk line: {@code kind} is {@code ' '} (context), {@code '-'} (removed) or {@code '+'} (added). {@code bytes}
     * are the line's bytes after the kind, with the line terminator as the patch has it ({@code \r\n} stays
     * {@code \r\n}); a line followed by {@code \ No newline at end of file} has no terminator.
     */
    public record Line(char kind, byte[] bytes) { }
}
