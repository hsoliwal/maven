/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Opt-in adapter for the original LLVM command-line compilers. A Maven task
 * names a language and retains control of all compiler flags and outputs.
 * There is no compiler implementation, native library, automatic download,
 * source rewriting or implicit lifecycle binding inside this provider.
 *
 * <p>Use {@code --m3-llvm-bin=<directory>} once in a task's arguments to
 * select the bin directory of an explicitly installed LLVM toolchain, such
 * as the build/bin directory of the pinned hsoliwal/llvm-project fork. This
 * provider consumes the selector; all other arguments go directly to the
 * original compiler without shell interpolation. A relative bin directory
 * is resolved from the module workspace, not the task working directory.</p>
 *
 * <p>M3 provider invocations are deliberately excluded from the polyglot
 * incremental/cache skip path until complete native header, sysroot,
 * library, toolchain and generated-input evidence is available.</p>
 */
public final class LlvmCompilerProvider implements M3ToolProvider {
    static final String BIN_ARGUMENT = "--m3-llvm-bin=";
    private static final Set<String> LANGUAGES = Set.of("c", "cpp", "objc", "objcpp", "fortran", "llvm-ir");

    @Override
    public String id() {
        return "llvm";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.COMPILER);
    }

    @Override
    public Set<String> languages() {
        return LANGUAGES;
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        if (capability != M3ToolCapability.COMPILER || !LANGUAGES.contains(language)) {
            throw new IOException("LLVM provider does not compile " + language + " as " + capability);
        }
        String driver = switch (language) {
            case "c", "objc" -> "clang";
            case "cpp", "objcpp" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> throw new IOException("unknown LLVM compiler language: " + language);
        };
        Path bin = null;
        List<String> flags = new ArrayList<>();
        for (String argument : task.arguments()) {
            if (!argument.startsWith(BIN_ARGUMENT)) {
                flags.add(argument);
                continue;
            }
            if (bin != null) {
                throw new IOException("LLVM bin directory must be specified only once");
            }
            String value = argument.substring(BIN_ARGUMENT.length());
            if (value.isBlank()) {
                throw new IOException("LLVM bin directory must not be empty");
            }
            try {
                Path chosen = Path.of(value);
                bin = (chosen.isAbsolute() ? chosen : workspace.resolve(chosen))
                        .toAbsolutePath().normalize();
            } catch (InvalidPathException invalid) {
                throw new IOException("invalid LLVM bin directory", invalid);
            }
        }
        if (flags.isEmpty()) {
            throw new IOException("LLVM compilation requires explicit compiler arguments");
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            driver += ".exe";
        }
        List<String> argv = new ArrayList<>(flags.size() + 1);
        argv.add(bin == null ? driver : bin.resolve(driver).toString());
        argv.addAll(flags);
        return List.copyOf(argv);
    }
}
