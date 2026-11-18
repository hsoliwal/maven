/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Original LLVM compiler drivers through the existing Maven tool SPI. The selected
 * installation can be built from the hsoliwal/llvm-project fork; no LLVM libraries
 * or alternative compiler implementation are bundled in the plugin.
 *
 * <p>Planning validates a local executable and, when requested, its SHA-256 without
 * starting it. Compiler arguments, including target, sysroot, dependency-file and
 * linker options, are forwarded as individual unchanged arguments. The runner's
 * existing provider policy always executes these tasks: a driver digest or a
 * previously emitted depfile is not a complete native build dependency identity.</p>
 */
public final class LlvmCompilerProvider implements M3ToolProvider {
    static final String HOME = "--m3-llvm-home=";
    static final String SHA256 = "--m3-llvm-sha256=";
    static final String HOME_PROPERTY = "m3.polyglot.llvm.home";
    private static final Set<String> LANGUAGES = Set.of("c", "cpp", "objc", "objc++", "fortran", "llvm-ir");

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
            throw new IOException("LLVM provider supports compilation of c, cpp, objc, objc++, fortran and llvm-ir");
        }
        Options options = options(task.arguments());
        Path driver = executable(driver(language), options.home(), workspace, task.workingDir());
        if (options.sha256() != null && !options.sha256().equals(EcjCompilerProvider.sha256(driver))) {
            throw new IOException("LLVM compiler does not match " + SHA256 + options.sha256() + ": " + driver);
        }
        var argv = new ArrayList<String>(options.arguments().size() + 1);
        // Preserve the selected name (particularly the clang++ symlink): argv[0]
        // is part of the compiler driver's mode selection.
        argv.add(driver.toString());
        argv.addAll(options.arguments());
        return List.copyOf(argv);
    }

    private record Options(String home, String sha256, List<String> arguments) {
        private Options {
            arguments = List.copyOf(arguments);
        }
    }

    /** Only provider options before '--' are consumed; response files remain opaque. */
    private static Options options(List<String> arguments) throws IOException {
        String home = null;
        String sha256 = null;
        boolean literal = false;
        var forwarded = new ArrayList<String>(arguments.size());
        for (String argument : arguments) {
            if (!literal && argument.startsWith(HOME)) {
                if (home != null) throw new IOException("duplicate LLVM home option");
                home = argument.substring(HOME.length());
                if (home.isBlank()) throw new IOException("LLVM home must name an installation directory");
            } else if (!literal && argument.startsWith(SHA256)) {
                if (sha256 != null) throw new IOException("duplicate LLVM SHA-256 option");
                sha256 = argument.substring(SHA256.length());
                if (!sha256.matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("LLVM SHA-256 must contain exactly 64 hexadecimal digits");
                }
                sha256 = sha256.toLowerCase(Locale.ROOT);
            } else if (!literal && argument.startsWith("--m3-llvm-")) {
                throw new IOException("unknown LLVM provider option: " + argument);
            } else {
                forwarded.add(argument);
                if ("--".equals(argument)) literal = true;
            }
        }
        return new Options(home, sha256, forwarded);
    }

    private static String driver(String language) {
        return switch (language) {
            case "cpp", "objc++" -> "clang++";
            case "fortran" -> "flang";
            default -> "clang";
        };
    }

    private static Path executable(String driver, String configuredHome, Path workspace, String workingDir)
            throws IOException {
        String home = configuredHome;
        if (home == null) home = System.getProperty(HOME_PROPERTY);
        if (home == null || home.isBlank()) home = System.getenv("LLVM_HOME");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path root = workspace.toAbsolutePath().normalize();
        final Path selected;
        try {
            if (home != null && !home.isBlank()) {
                // Both property and task-relative homes have the same module-root base.
                selected = root.resolve(home).resolve("bin").resolve(driver + (windows ? ".exe" : ""))
                        .normalize();
            } else {
                Path directory = root.resolve(workingDir).normalize();
                String resolved = TaskRunner.resolveTool(driver, directory);
                selected = directory.resolve(resolved).normalize();
            }
        } catch (InvalidPathException invalid) {
            throw new IOException("invalid LLVM installation path", invalid);
        }
        // LLVM installations commonly use compiler-name symlinks. Follow them for
        // file validation and hashing, but keep the chosen driver spelling in argv.
        if (!Files.isRegularFile(selected) || (!windows && !Files.isExecutable(selected))) {
            throw new IOException("LLVM compiler is not an executable regular file: " + selected
                    + "; select the installation with " + HOME + "<path>, -D" + HOME_PROPERTY
                    + "=<path>, LLVM_HOME, or the task working directory/PATH");
        }
        return selected;
    }
}
