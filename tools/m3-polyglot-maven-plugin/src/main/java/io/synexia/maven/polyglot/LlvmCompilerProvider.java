/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
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
 * <p>The additive {@code --m3-llvm-home=<installation>} selector, system property
 * {@code m3.polyglot.llvm.home}, and {@code LLVM_HOME} select an installation's
 * bin directory and validate its selected executable. A task bin selector takes
 * precedence over implicit home settings. Optional {@code --m3-llvm-sha256=<hex>}
 * verifies only the selected executable's bytes. Unconfigured and unpinned legacy
 * bin commands retain planning without an installed tool. Compiler selection
 * preserves the driver spelling, including the usual clang++ executable symlink.</p>
 *
 * <p>M3 provider invocations are deliberately excluded from the polyglot
 * incremental/cache skip path until complete native header, sysroot,
 * library, toolchain and generated-input evidence is available.</p>
 */
public final class LlvmCompilerProvider implements M3ToolProvider {
    static final String BIN_ARGUMENT = "--m3-llvm-bin=";
    static final String HOME = "--m3-llvm-home=";
    static final String SHA256 = "--m3-llvm-sha256=";
    static final String HOME_PROPERTY = "m3.polyglot.llvm.home";
    private static final Set<String> LANGUAGES = Set.of("c", "cpp", "objc", "objcpp", "objc++", "fortran", "llvm-ir");

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
            case "cpp", "objcpp", "objc++" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> throw new IOException("unknown LLVM compiler language: " + language);
        };
        Options options = options(task.arguments());
        String executable = executable(driver, options, workspace, task.workingDir());
        List<String> argv = new ArrayList<>(options.arguments().size() + 1);
        argv.add(executable);
        argv.addAll(options.arguments());
        return List.copyOf(argv);
    }

    private record Options(String bin, String home, String sha256, List<String> arguments) {
        private Options {
            arguments = List.copyOf(arguments);
        }
    }

    private static Options options(List<String> arguments) throws IOException {
        String bin = null;
        String home = null;
        String sha256 = null;
        boolean literal = false;
        List<String> flags = new ArrayList<>();
        for (String argument : arguments) {
            // Preserve the original bin selector's position-independent contract.
            // The new home/digest options are recognized only before '--'.
            if (argument.startsWith(BIN_ARGUMENT)) {
                if (bin != null) throw new IOException("LLVM bin directory must be specified only once");
                bin = argument.substring(BIN_ARGUMENT.length());
                if (bin.isBlank()) throw new IOException("LLVM bin directory must not be empty");
            } else if (!literal && argument.startsWith(HOME)) {
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
                flags.add(argument);
                if ("--".equals(argument)) literal = true;
            }
        }
        if (bin != null && home != null) {
            throw new IOException("specify either LLVM bin directory or LLVM home, not both task selectors");
        }
        if (flags.isEmpty()) {
            throw new IOException("LLVM compilation requires explicit compiler arguments");
        }
        return new Options(bin, home, sha256, flags);
    }

    private static String executable(String driver, Options options, Path workspace, String workingDir)
            throws IOException {
        String home = options.home();
        if (home == null) home = System.getProperty(HOME_PROPERTY);
        if (home == null || home.isBlank()) home = System.getenv("LLVM_HOME");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        String name = driver + (windows ? ".exe" : "");
        boolean explicitHome = options.bin() == null && home != null && !home.isBlank();
        if (options.bin() == null && !explicitHome && options.sha256() == null) {
            // Keep the published PATH-based, installation-free planning contract.
            return name;
        }
        Path root = workspace.toAbsolutePath().normalize();
        final Path selected;
        try {
            if (options.bin() != null) {
                selected = root.resolve(options.bin()).resolve(name).normalize();
            } else if (explicitHome) {
                selected = root.resolve(home).resolve("bin").resolve(name).normalize();
            } else {
                Path directory = root.resolve(workingDir).normalize();
                selected = directory.resolve(TaskRunner.resolveTool(name, directory)).normalize();
            }
        } catch (InvalidPathException invalid) {
            throw new IOException("invalid LLVM installation path", invalid);
        }
        // Legacy bin selection remains plan-only unless the new digest is requested.
        // Home and digest selections explicitly require a locally available tool.
        if (explicitHome || options.sha256() != null) {
            if (!Files.isRegularFile(selected) || (!windows && !Files.isExecutable(selected))) {
                throw new IOException("LLVM compiler is not an executable regular file: " + selected);
            }
            if (options.sha256() != null && !options.sha256().equals(EcjCompilerProvider.sha256(selected))) {
                throw new IOException("LLVM compiler does not match " + SHA256 + options.sha256() + ": " + selected);
            }
        }
        // Do not canonicalize away clang++: argv[0] selects its original driver mode.
        return selected.toString();
    }
}
