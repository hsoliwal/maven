/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in adapter for an operator-built LLVM toolchain, including the hsoliwal/llvm-project fork.
 * LLVM owns compilation and LLVM IR code generation; Maven owns only the task graph and evidence.
 * This adapter never downloads, bundles, loads native libraries or substitutes another compiler.
 *
 * <p>Supply a toolchain root with {@code bin/clang}, {@code bin/clang++}, {@code bin/flang},
 * and/or {@code bin/llc} via {@code --m3-llvm-home=}, {@code -Dm3.llvm.home=}, or
 * {@code M3_LLVM_HOME}. To seal a task against a changed compiler executable, pass
 * {@code --m3-llvm-sha256=<64 hexadecimal digits>}. These two adapter directives are
 * consumed during planning, not sent to the original LLVM driver.</p>
 */
public final class LlvmCompilerProvider implements M3ToolProvider {
    static final String HOME_ARGUMENT = "--m3-llvm-home=";
    static final String SHA256_ARGUMENT = "--m3-llvm-sha256=";
    static final String HOME_PROPERTY = "m3.llvm.home";
    static final String HOME_ENVIRONMENT = "M3_LLVM_HOME";
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
            throw new IOException("LLVM provider cannot compile language: " + language);
        }
        String home = null;
        String expectedSha256 = null;
        var arguments = new ArrayList<String>();
        for (String argument : task.arguments()) {
            if (argument.startsWith(HOME_ARGUMENT)) {
                if (home != null || argument.length() == HOME_ARGUMENT.length()) {
                    throw new IOException("duplicate or empty " + HOME_ARGUMENT);
                }
                home = argument.substring(HOME_ARGUMENT.length());
            } else if (argument.startsWith(SHA256_ARGUMENT)) {
                if (expectedSha256 != null) throw new IOException("duplicate " + SHA256_ARGUMENT);
                expectedSha256 = argument.substring(SHA256_ARGUMENT.length());
                if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
                    throw new IOException("LLVM compiler SHA-256 must be exactly 64 hexadecimal digits");
                }
            } else if (argument.startsWith("--m3-llvm-")) {
                throw new IOException("unknown LLVM adapter directive: " + argument);
            } else {
                arguments.add(argument);
            }
        }
        if (arguments.isEmpty()) throw new IOException("LLVM compiler requires original compiler arguments");
        if (home == null) home = System.getProperty(HOME_PROPERTY, "");
        if (home.isBlank()) home = System.getenv(HOME_ENVIRONMENT);
        if (home == null || home.isBlank()) {
            throw new IOException("LLVM toolchain not configured: set " + HOME_ARGUMENT + "<root>, -D"
                    + HOME_PROPERTY + "=<root>, or " + HOME_ENVIRONMENT);
        }
        Path executable = resolve(home, driver(language), workspace);
        if (expectedSha256 != null && !expectedSha256.toLowerCase(Locale.ROOT)
                .equals(InputStamps.fileSha256(executable))) {
            throw new IOException("LLVM compiler SHA-256 mismatch: " + executable);
        }
        var command = new ArrayList<String>(arguments.size() + 3);
        command.add(executable.toString());
        if ("objc".equals(language) || "objcpp".equals(language)) {
            command.add("-x");
            command.add("objc".equals(language) ? "objective-c" : "objective-c++");
        }
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static String driver(String language) {
        return switch (language) {
            case "cpp", "objcpp" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> "clang";
        };
    }

    /** Resolve a real driver under the explicit toolchain, never an unrelated PATH executable. */
    static Path resolve(String home, String driver, Path workspace) throws IOException {
        try {
            Path root = workspace.resolve(home).normalize();
            String extension = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
            Path executable = root.resolve("bin").resolve(driver + extension);
            if ("flang".equals(driver) && !Files.isRegularFile(executable)) {
                executable = root.resolve("bin").resolve("flang-new" + extension);
            }
            Path actual = executable.toRealPath();
            if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LLVM driver is not a regular file: " + executable);
            }
            return actual;
        } catch (InvalidPathException | java.nio.file.NoSuchFileException invalid) {
            throw new IOException("LLVM driver not found under toolchain " + home + "/bin: " + driver, invalid);
        }
    }
}
