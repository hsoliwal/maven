/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in clangd/CDT compilation database projection of the existing M3 task
 * graph. This reads original LLVM provider command vectors; it neither runs
 * Clang nor replaces CMake's authoritative compile_commands.json.
 *
 * <p>It only exports unambiguous, already materialized translation units for
 * Clang compile-only tasks. An unresolved response file, missing source, or
 * untracked source fails closed, rather than emitting a plausible but false
 * IDE compilation command. The output is owned only under target/m3-llvm/.</p>
 */
public final class LlvmCompilationDatabase {
    private static final Set<String> CLANG_LANGUAGES =
            Set.of("c", "cpp", "objc", "objcpp", "objc++");
    private static final Set<String> SOURCE_SUFFIXES =
            Set.of(".c", ".cc", ".cpp", ".cxx", ".c++", ".m", ".mm");
    private static final Set<String> NEXT_OPERAND = Set.of("-o", "-MF", "-MT", "-MQ",
            "-I", "-isystem", "-iquote", "-include", "-imacros", "-x", "-isysroot",
            "--sysroot", "-Xclang", "-Xpreprocessor", "-Xassembler", "-Xlinker");
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final String RELATIVE_OUTPUT = "target/m3-llvm/compile_commands.json";

    private record Entry(String task, String directory, String file, List<String> arguments) { }

    private LlvmCompilationDatabase() { }

    /** Only the existing graph's requested target closure is projected. */
    public static String render(TaskManifest manifest, Path workspace, String target) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3 module root must be a real directory");
        }
        var entries = new ArrayList<Entry>();
        for (TaskManifest.Task task : manifest.order(target)) {
            if (!task.tool().startsWith("@m3:llvm:compiler:")) continue;
            String language = task.tool().substring("@m3:llvm:compiler:".length());
            if (!CLANG_LANGUAGES.contains(language) || !task.arguments().contains("-c")) continue;
            Path cwd = InputStamps.checked(root, task.workingDir());
            if (!Files.isDirectory(cwd, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LLVM task working directory is not materialized: " + task.name());
            }
            Path source = source(root, cwd, task);
            // The provider owns argument spelling, LLVM-home selection and digest checks.
            List<String> argv = M3ToolProviders.command(task, root);
            entries.add(new Entry(task.name(), cwd.toString(), source.toString(), argv));
        }
        if (entries.isEmpty()) {
            throw new IOException("requested M3 target has no eligible materialized Clang compile tasks");
        }
        entries.sort(Comparator.comparing(Entry::file).thenComparing(Entry::task));
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i > 0) out.append(",\n");
            out.append("  {\"directory\":").append(quoted(entry.directory()))
                    .append(",\"file\":").append(quoted(entry.file()))
                    .append(",\"arguments\":[");
            for (int a = 0; a < entry.arguments().size(); a++) {
                if (a > 0) out.append(',');
                out.append(quoted(entry.arguments().get(a)));
            }
            out.append("]}");
            if (out.length() > MAX_OUTPUT_BYTES) {
                throw new IOException("LLVM compilation database exceeds the 4 MiB budget");
            }
        }
        out.append("\n]\n");
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IOException("LLVM compilation database exceeds the 4 MiB budget");
        }
        return out.toString();
    }

    /**
     * Materialize exactly one module-owned output. No task execution or build
     * receipt/cache mutation; identical output does not update its timestamp.
     */
    public static Path publish(TaskManifest manifest, Path workspace, String target) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        byte[] bytes = render(manifest, root, target).getBytes(StandardCharsets.UTF_8);
        Path folder = InputStamps.checked(root, "target/m3-llvm");
        Path output = InputStamps.checked(root, RELATIVE_OUTPUT);
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(output)) {
            throw new IOException("unsafe LLVM compilation database output");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LLVM compilation database output is not a regular file");
        }
        if (Files.exists(output) && java.util.Arrays.equals(Files.readAllBytes(output), bytes)) {
            return output;
        }
        Path temp = Files.createTempFile(folder, ".m3-llvm-compdb-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic LLVM compilation database publication unsupported", unsupported);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return output;
    }

    /** An explicit source operand must be tracked by the original M3 inputs. */
    private static Path source(Path root, Path cwd, TaskManifest.Task task) throws IOException {
        var candidates = new ArrayList<Path>();
        boolean skip = false;
        for (String arg : task.arguments()) {
            if (skip) {
                skip = false;
                continue;
            }
            if (NEXT_OPERAND.contains(arg)) {
                skip = true;
                continue;
            }
            if (arg.startsWith("@")) {
                throw new IOException("LLVM compile response-file operands are not expanded: " + task.name());
            }
            if (arg.startsWith("-")) continue;
            String lower = arg.toLowerCase(Locale.ROOT);
            if (SOURCE_SUFFIXES.stream().noneMatch(lower::endsWith)) continue;
            Path file = cwd.resolve(arg).normalize();
            if (!file.startsWith(root)) throw new IOException("LLVM source escapes module: " + task.name());
            Path checked = InputStamps.checked(root, root.relativize(file).toString());
            if (!Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LLVM source is not materialized: " + checked);
            }
            boolean declared = false;
            for (String input : task.inputs()) {
                Path owner = InputStamps.checked(root, input);
                if (checked.equals(owner) || (Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)
                        && checked.startsWith(owner))) {
                    declared = true;
                    break;
                }
            }
            if (!declared) throw new IOException("LLVM source is not a declared M3 input: " + checked);
            candidates.add(checked);
        }
        if (candidates.size() != 1) {
            throw new IOException("LLVM compile task requires exactly one explicit translation unit: "
                    + task.name() + ", found " + candidates.size());
        }
        return candidates.get(0);
    }

    /** Pure JSON strings; compilation commands remain argv arrays, never shell text. */
    private static String quoted(String data) {
        StringBuilder out = new StringBuilder(data.length() + 2).append('"');
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        out.append("0000", 0, 4 - hex.length()).append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
