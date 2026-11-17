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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The toolchain identity of a build, declared as an input of the configure step: polyglot's content stamps hash the
 * task definition and its declared input bytes but not tool versions or the environment, so this file carries them.
 * Sorted tool paths with each tool's first {@code --version} line, the platform, and allow-listed compiler
 * variables. No timestamps: an unchanged toolchain writes identical bytes.
 */
public final class ToolchainFingerprint {
    /** Environment variables that change what a native build produces. */
    public static final List<String> VARIABLES =
            List.of("CC", "CXX", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "PKG_CONFIG_PATH");
    private static final Set<String> UNVERSIONED = Set.of("sh", "mkdir");

    private ToolchainFingerprint() {}

    /** The fingerprint text; {@code version} maps a tool path to its first version line. */
    public static String render(Platform platform, ToolSet tools, Function<String, String> version,
            Map<String, String> environment) {
        Map<String, String> env = PlanContext.environment(environment);
        Stream<String> head = Stream.of("# native toolchain fingerprint (configure input)",
                "platform=" + platform.os() + "-" + platform.arch());
        Stream<String> toolLines = tools.all().stream().flatMap(t -> Stream.of("tool." + t.name() + ".path=" + t.path(),
                "tool." + t.name() + ".version=" + (UNVERSIONED.contains(t.name()) ? "-" : version.apply(t.path()))));
        Stream<String> envLines = VARIABLES.stream().filter(env::containsKey).map(v -> "env." + v + "=" + env.get(v));
        return Stream.of(head, toolLines, envLines).flatMap(s -> s).collect(Collectors.joining("\n", "", "\n"));
    }

    /** Writes the fingerprint unless unchanged; true when it wrote. */
    public static boolean write(Path file, String content) throws IOException {
        return TaskFileWriter.write(file, content);
    }

    /** The first line {@code <tool> --version} prints, or {@code unknown (...)}. */
    public static String versionLine(String tool, Path directory) {
        try {
            CapturedProcess.Result result = CapturedProcess.run(List.of(tool, "--version"), directory,
                    Duration.ofSeconds(30));
            return result.exit() == 0 ? result.firstLine() : "unknown (exit " + result.exit() + ")";
        } catch (IOException failure) {
            return "unknown (" + failure.getMessage() + ")";
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
            return "unknown (interrupted)";
        }
    }
}
