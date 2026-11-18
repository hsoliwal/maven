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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes planned steps as a polyglot task manifest (one tab-separated fact per line, LF line ends, no shell text).
 * The manifests have no prerequisites: Maven's phases order the steps, one goal per phase. A root
 * {@code m3-build.tsv} for the polyglot lifecycle participant is the polyglot Mavenizer's, never this plugin's: one
 * owner per build descriptor (see {@link OwnerCheck}).
 */
public final class TaskFileWriter {
    private TaskFileWriter() {}

    /** The manifest text. Every argument stays one fact; one with a tab, line break or other control fails. */
    public static String render(List<NativeStep> steps) {
        return PolyglotEngine.MAGIC + "\n" + steps.stream().map(TaskFileWriter::facts).collect(Collectors.joining());
    }

    private static String facts(NativeStep step) {
        StringBuilder out = new StringBuilder(fact("task", step.name(), step.tool(), step.workingDir()));
        step.arguments().forEach(a -> out.append(fact("arg", step.name(), a)));
        step.inputs().forEach(p -> out.append(fact("input", step.name(), p)));
        step.outputs().forEach(p -> out.append(fact("output", step.name(), p)));
        return out.toString();
    }

    /** Writes {@code content} unless the file already holds exactly it; true when it wrote. */
    public static boolean write(Path file, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), bytes)) {
            return false;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, bytes);
        return true;
    }

    private static String fact(String kind, String... values) {
        Stream.of(values).forEach(TaskFileWriter::checkValue);
        return kind + "\t" + String.join("\t", values) + "\n";
    }

    private static void checkValue(String value) {
        if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("polyglot manifest values must be non-empty without tabs, line "
                    + "breaks or other control characters: '" + value.replace("\t", "\\t").replace("\n", "\\n") + "'");
        }
    }
}
