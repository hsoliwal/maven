/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.llvm;

import io.synexia.maven.polyglot.M3ToolCapability;
import io.synexia.maven.polyglot.M3ToolProvider;
import io.synexia.maven.polyglot.TaskManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optional LLVM/MLIR/LLD command-vector provider for the existing M3 polyglot
 * task engine. Original LLVM executables remain authoritative. This provider
 * implements no linker, optimizer, compiler, parser, build scheduler or cache.
 *
 * <p>Selectors {@code --m3-llvm-bin=}, {@code --m3-llvm-home=} and the optional
 * {@code --m3-llvm-sha256=} are consumed during planning, not passed to LLVM.
 * Without a task selector, the shared configured LLVM root is used when
 * available; otherwise the original tool name is passed to the runner's PATH
 * resolver. A selected but missing tool always fails closed, not over to PATH.</p>
 *
 * <p>All commands are intentionally provider-owned and ineligible for the
 * generic M3DB incremental/restore path until independently qualified native
 * dependency, toolchain, ABI and output evidence is available.</p>
 */
public final class LlvmNativeToolsProvider implements M3ToolProvider {
    static final String BIN_OPTION = "--m3-llvm-bin=";
    static final String HOME_OPTION = "--m3-llvm-home=";
    static final String SHA_OPTION = "--m3-llvm-sha256=";
    static final String EVIDENCE_OPTION = "--m3-llvm-evidence=";

    private static final Set<M3ToolCapability> ROLES = Set.of(
            M3ToolCapability.LINKER, M3ToolCapability.OPTIMIZER, M3ToolCapability.ASSEMBLER,
            M3ToolCapability.TRANSPILER, M3ToolCapability.INTERPRETER);
    private static final Set<String> LANGUAGES = Set.of(
            "llvm-ir", "mlir", "elf", "coff", "macho", "wasm", "lit", "filecheck");

    @Override
    public String id() {
        return "llvm-tools";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return ROLES;
    }

    @Override
    public Set<String> languages() {
        return LANGUAGES;
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        String executableName = driver(capability, language);
        String binSelector = null;
        String homeSelector = null;
        String expectedHash = null;
        String evidencePath = null;
        boolean literal = false;
        var forwarded = new ArrayList<String>(task.arguments().size());

        for (String argument : task.arguments()) {
            if (!literal && BIN_OPTION.equals(argument)) {
                throw new IOException("empty LLVM bin selector");
            }
            if (!literal && argument.startsWith(BIN_OPTION)) {
                if (binSelector != null || homeSelector != null) {
                    throw new IOException("duplicate or conflicting LLVM toolchain selector");
                }
                binSelector = argument.substring(BIN_OPTION.length());
                if (binSelector.isBlank()) throw new IOException("empty LLVM bin selector");
            } else if (!literal && argument.startsWith(HOME_OPTION)) {
                if (homeSelector != null || binSelector != null) {
                    throw new IOException("duplicate or conflicting LLVM toolchain selector");
                }
                homeSelector = argument.substring(HOME_OPTION.length());
                if (homeSelector.isBlank()) throw new IOException("empty LLVM home selector");
            } else if (!literal && argument.startsWith(SHA_OPTION)) {
                if (expectedHash != null) throw new IOException("duplicate LLVM tool SHA-256");
                expectedHash = argument.substring(SHA_OPTION.length());
                if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("LLVM SHA-256 must be exactly 64 hexadecimal digits");
                }
                expectedHash = expectedHash.toLowerCase(Locale.ROOT);
            } else if (!literal && argument.startsWith(EVIDENCE_OPTION)) {
                if (evidencePath != null) throw new IOException("duplicate LLVM evidence selector");
                evidencePath = argument.substring(EVIDENCE_OPTION.length());
                if (evidencePath.isBlank()) throw new IOException("empty LLVM evidence selector");
            } else if (!literal && argument.startsWith("--m3-llvm-")) {
                throw new IOException("unknown LLVM tool option: " + argument);
            } else {
                forwarded.add(argument);
                if ("--".equals(argument)) literal = true;
            }
        }
        if (forwarded.isEmpty()) {
            throw new IOException("LLVM native tool requires explicit original arguments");
        }

        String binary = executableName
                + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "");
        String configuredHome = homeSelector;
        if (binSelector == null && configuredHome == null) {
            configuredHome = nonblank(System.getProperty("m3.polyglot.llvm.home"));
            if (configuredHome == null) configuredHome = nonblank(System.getProperty("m3.llvm.home"));
            if (configuredHome == null) configuredHome = nonblank(System.getenv("M3_LLVM_HOME"));
            if (configuredHome == null) configuredHome = nonblank(System.getenv("LLVM_HOME"));
        }

        Path executable = null;
        if (binSelector != null || configuredHome != null) {
            try {
                Path folder = binSelector != null ? Path.of(binSelector) : Path.of(configuredHome).resolve("bin");
                if (!folder.isAbsolute()) folder = workspace.resolve(folder);
                executable = folder.toAbsolutePath().normalize().resolve(binary);
            } catch (InvalidPathException invalid) {
                throw new IOException("invalid LLVM toolchain directory", invalid);
            }
            executable = verifiedExecutable(executable);
        } else if (expectedHash != null) {
            executable = findOnPath(binary);
        }

        if (evidencePath != null) {
            if (binSelector == null && configuredHome == null) {
                throw new IOException("LLVM evidence requires an explicit installation root or bin selector");
            }
            try {
                Path folder = binSelector == null ? Path.of(configuredHome).resolve("bin") : Path.of(binSelector);
                if (!folder.isAbsolute()) folder = workspace.resolve(folder);
                folder = folder.toAbsolutePath().normalize();
                if (folder.getFileName() == null || !"bin".equals(folder.getFileName().toString())) {
                    throw new IOException("LLVM evidence requires the installation's bin directory");
                }
                Path evidence = Path.of(evidencePath);
                if (!evidence.isAbsolute()) evidence = workspace.resolve(evidence);
                LlvmToolchainEvidence.verifySelected(
                        folder.getParent(), LlvmToolchainEvidence.read(evidence), executableName);
            } catch (InvalidPathException invalid) {
                throw new IOException("invalid LLVM evidence manifest path", invalid);
            }
        }

        if (expectedHash != null && !expectedHash.equals(sha256(executable))) {
            throw new IOException("LLVM tool SHA-256 mismatch: " + executable.getFileName());
        }

        var argv = new ArrayList<String>(forwarded.size() + 1);
        argv.add(executable == null ? binary : executable.toString());
        argv.addAll(forwarded);
        return List.copyOf(argv);
    }

    /** Keep the original LLVM driver and target format authoritative. */
    private static String driver(M3ToolCapability role, String language) throws IOException {
        if (role == M3ToolCapability.OPTIMIZER && "llvm-ir".equals(language)) return "opt";
        if (role == M3ToolCapability.OPTIMIZER && "mlir".equals(language)) return "mlir-opt";
        if (role == M3ToolCapability.ASSEMBLER && "llvm-ir".equals(language)) return "llvm-as";
        if (role == M3ToolCapability.TRANSPILER && "mlir".equals(language)) return "mlir-translate";
        if (role == M3ToolCapability.LINKER) {
            return switch (language) {
                case "llvm-ir" -> "llvm-link";
                case "elf" -> "ld.lld";
                case "coff" -> "lld-link";
                case "macho" -> "ld64.lld";
                case "wasm" -> "wasm-ld";
                default -> throw unsupported(role, language);
            };
        }
        if (role == M3ToolCapability.INTERPRETER && "lit".equals(language)) return "llvm-lit";
        if (role == M3ToolCapability.INTERPRETER && "filecheck".equals(language)) return "FileCheck";
        throw unsupported(role, language);
    }

    private static IOException unsupported(M3ToolCapability role, String language) {
        return new IOException("unsupported LLVM native tool role/language: " + role + "/" + language);
    }

    private static String nonblank(String string) {
        return string == null || string.isBlank() ? null : string;
    }

    /** Follow genuine LLVM tool symlinks, but reject missing or non-executable files. */
    private static Path verifiedExecutable(Path file) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            throw new IOException("selected LLVM executable unavailable: " + file);
        }
        return file;
    }

    private static Path findOnPath(String executable) throws IOException {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String folder : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
                if (folder.isBlank()) continue;
                try {
                    Path found = Path.of(folder).resolve(executable);
                    if (Files.isRegularFile(found) && Files.isExecutable(found)) {
                        return found.toAbsolutePath().normalize();
                    }
                } catch (InvalidPathException invalid) {
                    // The untrusted PATH entry is unusable; continue to the next.
                }
            }
        }
        throw new IOException("SHA-256 pin requires an installed LLVM tool on PATH: " + executable);
    }

    /** Read the original binary's bytes, never substitute its filename or version string. */
    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65_536];
            for (int count; (count = in.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
