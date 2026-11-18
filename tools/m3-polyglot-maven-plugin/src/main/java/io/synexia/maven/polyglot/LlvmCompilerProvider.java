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
import java.util.function.UnaryOperator;

/**
 * Opt-in adapter for original LLVM command-line compilers, including the hsoliwal/llvm-project
 * fork. LLVM owns compilation and IR code generation; Maven owns the task graph and evidence.
 * This adapter never downloads, bundles, loads native libraries or rewrites sources.
 *
 * <p>For existing manifests, {@code --m3-llvm-bin=<directory>} selects a bin directory,
 * relative to the module workspace or absolute; without any selection, the driver is looked
 * up on PATH. Alternatively select a toolchain root via {@code --m3-llvm-home=},
 * {@code -Dm3.llvm.home=}, or {@code M3_LLVM_HOME}. The shared LLVM native-tools
 * aliases {@code m3.polyglot.llvm.home} and {@code LLVM_HOME} also work here, in that
 * precedence order: polyglot property, M3 property, M3 environment, LLVM environment.
 * An explicit bin selector takes precedence over a global home. Explicit home and bin
 * selectors are mutually exclusive.
 * To verify the selected executable's bytes, use {@code --m3-llvm-sha256=<64 hex digits>}.
 * All selector directives are removed from the original compiler arguments.</p>
 *
 * <p>M3 provider invocations are excluded from incremental/cache skips until native headers,
 * sysroots, libraries, toolchain and generated-input evidence is complete.</p>
 */
public final class LlvmCompilerProvider implements M3ToolProvider {
    static final String BIN_ARGUMENT = "--m3-llvm-bin=";
    static final String HOME_ARGUMENT = "--m3-llvm-home=";
    static final String SHA256_ARGUMENT = "--m3-llvm-sha256=";
    static final String POLYGLOT_HOME_PROPERTY = "m3.polyglot.llvm.home";
    static final String HOME_PROPERTY = "m3.llvm.home";
    static final String HOME_ENVIRONMENT = "M3_LLVM_HOME";
    static final String FALLBACK_HOME_ENVIRONMENT = "LLVM_HOME";
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
        String home = null;
        String bin = null;
        String expectedSha256 = null;
        var arguments = new ArrayList<String>();
        for (String argument : task.arguments()) {
            if (argument.startsWith(HOME_ARGUMENT)) {
                if (home != null || argument.length() == HOME_ARGUMENT.length()) {
                    throw new IOException("duplicate or empty " + HOME_ARGUMENT);
                }
                home = argument.substring(HOME_ARGUMENT.length());
            } else if (argument.startsWith(BIN_ARGUMENT)) {
                if (bin != null) throw new IOException("LLVM bin directory must be specified only once");
                bin = argument.substring(BIN_ARGUMENT.length());
                if (bin.isBlank()) throw new IOException("LLVM bin directory must not be empty");
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
        if (arguments.isEmpty()) throw new IOException("LLVM compilation requires explicit compiler arguments");
        if (home != null && bin != null) {
            throw new IOException("LLVM home and bin selectors cannot both be set on one task");
        }
        String driver = driver(language) + executableSuffix();
        Path executable = null;
        if (bin != null) {
            try {
                Path selected = Path.of(bin);
                executable = (selected.isAbsolute() ? selected : workspace.resolve(selected))
                        .toAbsolutePath().normalize().resolve(driver);
            } catch (InvalidPathException invalid) {
                throw new IOException("invalid LLVM bin directory", invalid);
            }
        } else {
            if (home == null) home = configuredHome();
            if (home != null && !home.isBlank()) {
                executable = resolve(home, driver(language), workspace);
            }
        }
        // Legacy PATH selection is deliberately preserved, but a digest cannot be verified
        // against an unresolved executable name; opt into a directory for a sealed task.
        if (expectedSha256 != null) {
            if (executable == null) {
                throw new IOException("LLVM SHA-256 pin requires a selected home or bin directory");
            }
            if (!expectedSha256.toLowerCase(Locale.ROOT)
                    .equals(InputStamps.fileSha256(executable.toRealPath()))) {
                throw new IOException("LLVM compiler SHA-256 mismatch: " + executable);
            }
        }
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add(executable == null ? driver : executable.toString());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    /** One Maven-owned selection rule, shared by provider tasks and generated native CMake builds. */
    static String configuredHome() {
        return configuredHome(System::getProperty, System::getenv);
    }

    /** Same global selector precedence as LLVM/MLIR/LLD tasks, with injectable lookup for contract tests. */
    static String configuredHome(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        String home = properties.apply(POLYGLOT_HOME_PROPERTY);
        if (home == null || home.isBlank()) home = properties.apply(HOME_PROPERTY);
        if (home == null || home.isBlank()) home = environment.apply(HOME_ENVIRONMENT);
        if (home == null || home.isBlank()) home = environment.apply(FALLBACK_HOME_ENVIRONMENT);
        return home;
    }

    private static String driver(String language) {
        return switch (language) {
            case "cpp", "objcpp", "objc++" -> "clang++";
            case "fortran" -> "flang";
            case "llvm-ir" -> "llc";
            default -> "clang";
        };
    }

    private static String executableSuffix() {
        return System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
    }

    /** Resolve a real driver under the explicit toolchain, never an unrelated PATH executable. */
    static Path resolve(String home, String driver, Path workspace) throws IOException {
        try {
            Path root = workspace.resolve(home).normalize();
            String extension = executableSuffix();
            Path executable = root.resolve("bin").resolve(driver + extension);
            if ("flang".equals(driver) && !Files.isRegularFile(executable)) {
                executable = root.resolve("bin").resolve("flang-new" + extension);
            }
            Path actual = executable.toRealPath();
            if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LLVM driver is not a regular file: " + executable);
            }
            // clang++ is often a symlink to clang. Keep the driver spelling for argv[0]:
            // Clang uses it to choose C++ defaults and its linker runtime. Hash the
            // resolved target above, but execute the named path.
            return executable.toAbsolutePath().normalize();
        } catch (InvalidPathException | java.nio.file.NoSuchFileException invalid) {
            throw new IOException("LLVM driver not found under toolchain " + home + "/bin: " + driver, invalid);
        }
    }
}
