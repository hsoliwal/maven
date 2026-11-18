/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative javac qualification for the existing JDK provider. Unknown flags,
 * processors, plugins, modules, argument files, default search paths and JAR
 * classpaths retain ordinary execution. Explicit source preference removes
 * javac's timestamp-based choice between implicit source/class dependencies.
 * JAR manifest Class-Path dependencies are deliberately outside this first
 * directory-classpath contract.
 */
final class JdkReuseInputs {
    private static final List<String> INJECTED_OPTIONS = List.of(
            "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
            "DYLD_FALLBACK_LIBRARY_PATH");
    private static final Set<String> FLAGS = Set.of(
            "-g", "-g:none", "-parameters", "-nowarn", "-deprecation", "-Werror",
            "-Xlint", "-Xlint:all", "-Xlint:none");
    private static final int MAX_TREE_ENTRIES = 262_144;

    private JdkReuseInputs() {}

    static Optional<M3ToolReuseInputs> inspect(TaskManifest.Task task, Path workspace) throws IOException {
        return inspect(task, workspace, System.getenv(), Path.of(System.getProperty("java.home")));
    }

    /** Package-local qualification seam; production always uses the current JVM and process environment. */
    static Optional<M3ToolReuseInputs> inspect(TaskManifest.Task task, Path workspace,
            Map<String, String> environment, Path javaHome) throws IOException {
        for (String name : INJECTED_OPTIONS) {
            if (!environment.getOrDefault(name, "").isBlank()) return Optional.empty();
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if ((entry.getKey().startsWith("LD_") || entry.getKey().startsWith("DYLD_"))
                    && !entry.getValue().isBlank()) return Optional.empty();
        }
        try {
            Arguments args = arguments(task.arguments());
            if (args == null || task.outputs().size() != 1 || task.inputs().isEmpty()) {
                return Optional.empty();
            }
            Path root = workspace.toAbsolutePath().normalize().toRealPath();
            TaskManifest.relativePath(task.workingDir(), true);
            Path work = root.resolve(task.workingDir()).normalize();
            if (!safeDirectory(root, work)) return Optional.empty();
            Path output = relative(work, args.output);
            if (!root.resolve(task.outputs().get(0)).equals(output)
                    || overlapsAnyInput(task, root, output)) return Optional.empty();
            for (String source : args.sources) {
                Path file = relative(work, source);
                if (!file.getFileName().toString().endsWith(".java")
                        || file.getFileName().toString().equals("module-info.java")
                        || !covered(task, root, file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !safeAncestors(root, file)) return Optional.empty();
            }
            if (!directories(task, root, work, args.classpath)
                    || !directories(task, root, work, args.sourcepath)) return Optional.empty();
            List<Path> toolchain = toolchain(javaHome);
            if (toolchain.isEmpty()) return Optional.empty();
            // Absolute paths in debug/source metadata have not been qualified for
            // cross-workspace restoration. Local receipt reuse is still useful.
            return Optional.of(new M3ToolReuseInputs("m3.jdk.javac.directory-inputs.v1",
                    toolchain, task.outputs(), false));
        } catch (IOException | IllegalArgumentException unsupported) {
            // Qualification failure never rewrites argv or weakens the ordinary
            // compiler's diagnostics: this invocation simply remains ALWAYS_RUN.
            return Optional.empty();
        }
    }

    private static final class Arguments {
        String output;
        String classpath;
        String sourcepath;
        String encoding;
        boolean noProcessors;
        boolean noImplicit;
        boolean preferSource;
        final List<String> sources = new ArrayList<>();
    }

    private static Arguments arguments(List<String> values) {
        Arguments result = new Arguments();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null || value.isEmpty()) return null;
            if ("-proc:none".equals(value)) {
                if (result.noProcessors) return null;
                result.noProcessors = true;
            } else if ("-implicit:none".equals(value)) {
                if (result.noImplicit) return null;
                result.noImplicit = true;
            } else if ("-Xprefer:source".equals(value)) {
                if (result.preferSource) return null;
                result.preferSource = true;
            } else if (FLAGS.contains(value)) {
                if (!seen.add(value)) return null;
            } else if (value.startsWith("-")) {
                String option = option(value);
                if (option == null || !seen.add(option) || ++i >= values.size()) return null;
                String argument = values.get(i);
                if (argument == null || argument.isEmpty() || argument.startsWith("@")) return null;
                switch (option) {
                    case "output" -> result.output = argument;
                    case "classpath" -> result.classpath = argument;
                    case "sourcepath" -> result.sourcepath = argument;
                    case "encoding" -> result.encoding = argument;
                    case "release", "source", "target" -> {
                        if (!argument.matches("(?:[1-9][0-9]?|1\\.[1-9])")) return null;
                    }
                    default -> throw new IllegalStateException("unexpected javac option");
                }
            } else {
                if (value.startsWith("@") || !value.endsWith(".java") || result.sources.contains(value)) return null;
                result.sources.add(value);
            }
        }
        if (seen.contains("release") && (seen.contains("source") || seen.contains("target"))) return null;
        return result.noProcessors && result.noImplicit && result.preferSource && "UTF-8".equals(result.encoding)
                && result.output != null && result.classpath != null && result.sourcepath != null
                && !result.sources.isEmpty() ? result : null;
    }

    private static String option(String value) {
        return switch (value) {
            case "-d" -> "output";
            case "-cp", "-classpath", "--class-path" -> "classpath";
            case "-sourcepath", "--source-path" -> "sourcepath";
            case "-encoding" -> "encoding";
            case "--release" -> "release";
            case "-source", "--source" -> "source";
            case "-target", "--target" -> "target";
            default -> null;
        };
    }

    private static Path relative(Path work, String value) throws IOException {
        TaskManifest.relativePath(value, false);
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) throw new IOException("search wildcard");
        return work.resolve(value).normalize();
    }

    private static boolean directories(TaskManifest.Task task, Path root, Path work,
            String classpath) throws IOException {
        String[] entries = classpath.split(Pattern.quote(File.pathSeparator), -1);
        if (entries.length > 256) return false;
        for (String entry : entries) {
            Path directory = relative(work, entry);
            if (!covered(task, root, directory) || !safeDirectory(root, directory)
                    || !safeTree(directory)) return false;
        }
        return entries.length > 0;
    }

    private static boolean covered(TaskManifest.Task task, Path root, Path path) throws IOException {
        if (!path.startsWith(root)) return false;
        for (String input : task.inputs()) {
            TaskManifest.relativePath(input, false);
            Path declared = root.resolve(input);
            if (path.equals(declared) || path.startsWith(declared)) return true;
        }
        return false;
    }

    private static boolean overlapsAnyInput(TaskManifest.Task task, Path root, Path output) throws IOException {
        if (!output.startsWith(root) || output.equals(root)) return true;
        for (String input : task.inputs()) {
            TaskManifest.relativePath(input, false);
            Path declared = root.resolve(input);
            if (output.startsWith(declared) || declared.startsWith(output)) return true;
        }
        return false;
    }

    private static boolean safeDirectory(Path root, Path directory) throws IOException {
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && safeAncestors(root, directory);
    }

    private static boolean safeAncestors(Path root, Path path) {
        if (!path.startsWith(root)) return false;
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) return false;
        }
        return true;
    }

    private static List<Path> toolchain(Path javaHome) throws IOException {
        Path home = javaHome.toAbsolutePath().normalize();
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> inputs = new ArrayList<>();
        for (String directory : List.of("bin", "lib", "conf")) {
            Path path = home.resolve(directory);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !safeTree(path)) return List.of();
            inputs.add(path);
        }
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) return List.of();
        inputs.add(release);
        String java = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        if (!Files.isRegularFile(home.resolve("bin").resolve(java), LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(home.resolve("bin").resolve(java))
                || !Files.isRegularFile(home.resolve("lib/modules"), LinkOption.NOFOLLOW_LINKS)) return List.of();
        return List.copyOf(inputs);
    }

    private static boolean safeTree(Path directory) throws IOException {
        int[] count = {0};
        boolean[] safe = {true};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                if (++count[0] > MAX_TREE_ENTRIES || !attrs.isDirectory()) {
                    safe[0] = false;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (++count[0] > MAX_TREE_ENTRIES || !attrs.isRegularFile()) {
                    safe[0] = false;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return safe[0];
    }
}
