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
package org.apache.maven.plugins.polyglot.adapters;

import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Argument rules shared by the adapters: positional counts, path forms, pinned versions, {@code key=value} options and
 * the launcher check. Every adapter plans an argv only; nothing here starts a process or touches the workspace.
 */
final class ToolArgs {
    /** cmd.exe expands or splits on these even inside quotes, so a .cmd/.bat launcher must never receive them. */
    private static final String CMD_METACHARACTERS = "%!^&|<>\"";
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");

    private ToolArgs() { }

    /** The task's arguments when their count is within the bounds; otherwise the usage as an error. */
    static List<String> count(TaskManifest.Task task, int min, int max, String usage) throws IOException {
        List<String> args = task.arguments();
        if (args.size() < min || args.size() > max) {
            throw new IOException("usage: " + usage);
        }
        return args;
    }

    /** The argument as an absolute path, resolved as the task's process would: workspace, then working directory. */
    static String absolute(Path workspace, TaskManifest.Task task, String argument) {
        return workspace.resolve(task.workingDir()).resolve(argument).toAbsolutePath().normalize().toString();
    }

    /** The argument in the form outputs are declared in: workspace-relative with '/' separators. */
    static String declared(TaskManifest.Task task, String argument) {
        return Path.of(task.workingDir()).resolve(argument).normalize().toString().replace('\\', '/');
    }

    /** The running JDK's launcher, so tool processes use the JDK the engine was started with. */
    static String java() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** The class path entry (jar or class folder) the adapter classes were loaded from. */
    static String home(Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException | RuntimeException failure) {
            throw new IOException("cannot locate the adapter class path entry", failure);
        }
    }

    /** A pinned version: never LATEST or RELEASE, never a range. */
    static String version(String value) throws IOException {
        if (!VERSION.matcher(value).matches() || "LATEST".equals(value) || "RELEASE".equals(value)) {
            throw new IOException("a pinned tool version is required: " + value);
        }
        return value;
    }

    /** A value that the tool must not read as an option of its own. */
    static String operand(String value, String what) throws IOException {
        if (value.isEmpty() || value.startsWith("-")) {
            throw new IOException(what + " must not be empty or start with '-': " + value);
        }
        return value;
    }

    /** The value of an optional {@code key=value} argument; a repeated key is refused. */
    static Optional<String> option(List<String> args, String key) throws IOException {
        List<String> values = args.stream().filter(a -> a.startsWith(key + "="))
                .map(a -> a.substring(key.length() + 1)).toList();
        if (values.size() > 1) {
            throw new IOException("option given more than once: " + key);
        }
        return values.stream().findFirst();
    }

    /** The arguments that are not one of the given {@code key=value} options. */
    static List<String> positional(List<String> args, List<String> keys) {
        return args.stream().filter(a -> keys.stream().noneMatch(k -> a.startsWith(k + "="))).toList();
    }

    /** Refuses a .cmd/.bat launcher when any argument holds a cmd.exe metacharacter (no shell ever interprets argv). */
    static List<String> launch(List<String> argv) throws IOException {
        String launcher = argv.get(0).toLowerCase(Locale.ROOT);
        boolean viaCmd = launcher.endsWith(".cmd") || launcher.endsWith(".bat");
        if (viaCmd && argv.stream().anyMatch(a -> a.chars().anyMatch(c -> CMD_METACHARACTERS.indexOf(c) >= 0))) {
            throw new IOException("a .cmd/.bat launcher cannot take cmd.exe metacharacters safely: " + argv.get(0));
        }
        return List.copyOf(argv);
    }

    /** Refuses an output folder that lies inside (or equals) one of the folders the tool reads. */
    static void outside(String output, List<String> roots) throws IOException {
        Path out = Path.of(output);
        if (roots.stream().map(Path::of).anyMatch(out::startsWith)) {
            throw new IOException("the output folder lies inside a source root: " + output);
        }
    }
}
