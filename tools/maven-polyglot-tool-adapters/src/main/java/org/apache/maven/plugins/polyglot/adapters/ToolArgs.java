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

import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.TaskManifest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Argument rules shared by the adapters: positional counts, path forms, pinned versions, {@code key=value} options and
 * the launcher check. Every adapter plans an argv only; nothing here starts a process or touches the workspace.
 */
final class ToolArgs {
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

    /**
     * The adapter's capabilities. An engine older than the additive tool kinds lacks their enum constants, so reading
     * one throws NoSuchFieldError; that becomes an IllegalStateException, which every engine's registry (also the
     * first v1 one, which does not guard LinkageError) refuses as a provider that cannot load, naming the reason.
     */
    static Set<M3ToolCapability> kinds(Supplier<Set<M3ToolCapability>> kinds) {
        try {
            return kinds.get();
        } catch (LinkageError olderEngine) {
            throw new IllegalStateException("maven-polyglot-tool-adapters needs an m3-polyglot-maven-plugin engine with"
                    + " the additive tool kinds (linter, static-analyzer, differ, patcher): " + olderEngine, olderEngine);
        }
    }

    /**
     * Refuses a command interpreter as the launcher, and a .cmd/.bat launcher when any argument holds a cmd.exe
     * metacharacter, so no shell ever interprets argv ({@link CmdLauncher}).
     */
    static List<String> launch(List<String> argv) throws IOException {
        return CmdLauncher.shellSafe(argv);
    }

    /**
     * The same check for a command the tool process starts itself (a language server, git): the planned argv starts
     * java, so the launcher and every value that reaches it are checked here as well as in the tool process.
     */
    static List<String> nested(List<String> command) throws IOException {
        return CmdLauncher.shellSafe(command);
    }

    /** Refuses an output folder that lies inside (or equals) one of the folders the tool reads. */
    static void outside(String output, List<String> roots) throws IOException {
        Path out = Path.of(output);
        if (roots.stream().map(Path::of).anyMatch(out::startsWith)) {
            throw new IOException("the output folder lies inside a source root: " + output);
        }
    }

    /**
     * A folder a tool writes into, as an absolute path: inside the workspace and in declared output space (a declared
     * output, or a folder that holds or is held by one), never related to a declared input. Every adapter checks its
     * output folders here, with the same parse of the arguments its reports come from.
     */
    static String outputFolder(Path workspace, TaskManifest.Task task, String folder) throws IOException {
        Optional<String> refusal = outputRefusal(workspace, task, folder);
        if (refusal.isPresent()) {
            throw new IOException(refusal.get());
        }
        return absolute(workspace, task, folder);
    }

    /** Why the folder cannot be an output folder ({@link #outputFolder}), if it cannot. */
    static Optional<String> outputRefusal(Path workspace, TaskManifest.Task task, String folder) {
        if (!within(workspace, task, folder)) {
            return Optional.of("the output folder lies outside the workspace: " + folder);
        }
        return outputSpace(task, declared(task, folder)) ? Optional.empty()
                : Optional.of("an output folder must be declared output space, never a declared input: " + folder);
    }

    /** The argument as an absolute path that must lie inside the workspace (the root itself included). */
    static String inside(Path workspace, TaskManifest.Task task, String argument, String what) throws IOException {
        if (!within(workspace, task, argument)) {
            throw new IOException("the " + what + " lies outside the workspace: " + argument);
        }
        return absolute(workspace, task, argument);
    }

    private static boolean within(Path workspace, TaskManifest.Task task, String argument) {
        return Path.of(absolute(workspace, task, argument)).startsWith(workspace.toAbsolutePath().normalize());
    }

    /** Declared output space: related to a declared output, unrelated to every declared input, not the root. */
    static boolean outputSpace(TaskManifest.Task task, String folder) {
        boolean source = folder.isEmpty() || task.inputs().stream().anyMatch(input -> related(input, folder));
        return !source && task.outputs().stream().anyMatch(output -> related(output, folder));
    }

    /** Equal, or one holds the other, in the declared '/' form. */
    static boolean related(String a, String b) {
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }
}
