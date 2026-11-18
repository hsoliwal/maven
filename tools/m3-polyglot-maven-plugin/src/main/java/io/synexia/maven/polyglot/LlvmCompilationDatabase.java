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
    private static final Set<String> NON_OBJECT_FLAGS =
            Set.of("-E", "-S", "-fsyntax-only", "-M", "-MM");
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
            // Link-only Clang tasks are legitimate M3 nodes, not compdb rows.
            // The source parser independently validates that -c is not merely
            // the operand of another compiler flag.
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

    /**
     * Resolve a single compile-only translation unit and independently verify
     * the original command's output ownership. No response files or untracked
     * compiler side effects may be represented as complete IDE metadata.
     */
    private static Path source(Path root, Path cwd, TaskManifest.Task task) throws IOException {
        var candidates = new ArrayList<Path>();
        String operandOf = null;
        String objectName = null;
        boolean compileOnly = false;
        for (String arg : task.arguments()) {
            // Check *before* consuming the operand of -Xclang/-include/-o etc.
            // A forwarded @file could otherwise bypass the response-file gate.
            if (arg.startsWith("@")) {
                throw new IOException("LLVM compile response-file operands are not expanded: " + task.name());
            }
            if (operandOf != null) {
                if ("-o".equals(operandOf)) {
                    if (objectName != null) throw new IOException("duplicate LLVM -o output: " + task.name());
                    objectName = arg;
                }
                operandOf = null;
                continue;
            }
            if (NEXT_OPERAND.contains(arg)) {
                operandOf = arg;
                continue;
            }
            if ("-c".equals(arg)) {
                compileOnly = true;
                continue;
            }
            if (NON_OBJECT_FLAGS.contains(arg) || "--".equals(arg)) {
                throw new IOException("unsupported non-object/opaque Clang argument: " + arg);
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
        if (operandOf != null) {
            throw new IOException("dangling Clang option " + operandOf + " in " + task.name());
        }
        if (!compileOnly || candidates.size() != 1 || objectName == null || objectName.isBlank()) {
            throw new IOException("Clang export requires -c, exactly one source and an explicit -o: " + task.name());
        }
        Path object = cwd.resolve(objectName).normalize();
        if (!object.startsWith(root)) {
            throw new IOException("Clang output escapes module: " + task.name());
        }
        Path checkedObject = InputStamps.checked(root, root.relativize(object).toString());
        boolean owned = false;
        for (String output : task.outputs()) {
            if (checkedObject.equals(InputStamps.checked(root, output))) {
                owned = true;
                break;
            }
        }
        if (!owned) {
            throw new IOException("Clang -o output is not a declared M3 output: " + checkedObject);
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
