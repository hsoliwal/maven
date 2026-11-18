/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.llvm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable, root-relative content evidence for explicitly selected LLVM tools.
 * This is not an LLVM build attestation and cannot authorize a build-cache hit:
 * headers, plugin passes, libraries, arguments, environment and output ABI remain
 * independent dependencies owned by the native build and M3DB authorities.
 */
public final class LlvmToolchainEvidence {
    public static final String MAGIC = "M3-LLVM-TOOLCHAIN-V1";
    private static final Set<String> KNOWN = Set.of("clang", "clang++", "clangd", "clang-tidy",
            "clang-format", "flang", "flang-new", "llc", "opt", "llvm-as", "llvm-link",
            "mlir-opt", "mlir-translate", "ld.lld", "lld-link", "ld64.lld", "wasm-ld",
            "llvm-lit", "FileCheck");
    private static final int MAX_BYTES = 1024 * 1024;

    private LlvmToolchainEvidence() { }

    private record Fingerprint(long size, FileTime modified, String sha256) { }

    /** Inspect only the tools explicitly requested by the build owner. */
    public static byte[] inspect(Path home, List<String> requested) throws IOException {
        if (requested == null || requested.isEmpty()) throw new IOException("at least one LLVM tool required");
        Path root = home.toRealPath();
        Path bin = root.resolve("bin").toRealPath();
        if (!Files.isDirectory(bin) || !bin.startsWith(root)) {
            throw new IOException("LLVM bin must be within the selected installation");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String id : requested) {
            if (!KNOWN.contains(id) || !sorted.add(id)) throw new IOException("unknown or repeated LLVM tool: " + id);
        }
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        StringBuilder evidence = new StringBuilder(MAGIC).append('\n');
        Map<Path, Fingerprint> seen = new HashMap<>();
        for (String id : sorted) {
            Path selected = bin.resolve(id + suffix);
            if (!Files.isRegularFile(selected) || !Files.isExecutable(selected)) {
                throw new IOException("LLVM tool is not executable: " + id);
            }
            Path actual = selected.toRealPath();
            if (!actual.startsWith(root) || !Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LLVM tool escapes selected installation: " + id);
            }
            Fingerprint fingerprint = seen.get(actual);
            FileTime modified = Files.getLastModifiedTime(actual);
            long size = Files.size(actual);
            if (fingerprint == null) {
                String digest = hash(actual);
                if (Files.size(actual) != size || !modified.equals(Files.getLastModifiedTime(actual))) {
                    throw new IOException("LLVM tool changed during evidence collection: " + id);
                }
                fingerprint = new Fingerprint(size, modified, digest);
                seen.put(actual, fingerprint);
            } else if (fingerprint.size() != size || !fingerprint.modified().equals(modified)) {
                throw new IOException("LLVM tool changed between aliases: " + id);
            }
            evidence.append("tool\t").append(id).append('\t')
                    .append(relative(root, selected)).append('\t')
                    .append(relative(root, actual)).append('\t')
                    .append(fingerprint.size()).append('\t').append(fingerprint.sha256()).append('\n');
        }
        byte[] content = evidence.toString().getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_BYTES) throw new IOException("LLVM manifest exceeds maximum size");
        return content;
    }

    /** Compare actual bytes and symlink targets with the selected toolchain seal. */
    public static void verify(Path home, byte[] manifest) throws IOException {
        if (manifest == null || manifest.length == 0 || manifest.length > MAX_BYTES) {
            throw new IOException("invalid LLVM evidence size");
        }
        String input = new String(manifest, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(input.getBytes(StandardCharsets.UTF_8), manifest)
                || !input.startsWith(MAGIC + "\n") || !input.endsWith("\n") || input.contains("\r")) {
            throw new IOException("invalid LLVM evidence format");
        }
        List<String> requested = new ArrayList<>();
        String[] lines = input.split("\n", -1);
        for (int i = 1; i < lines.length - 1; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length != 6 || !"tool".equals(fields[0])) {
                throw new IOException("malformed LLVM tool evidence row");
            }
            requested.add(fields[1]);
        }
        if (!java.util.Arrays.equals(manifest, inspect(home, requested))) {
            throw new IOException("LLVM toolchain evidence changed");
        }
    }

    /**
     * A task can require proof that the very driver it will invoke is in the
     * supplied, fully reverified manifest. Other selected tools are also checked.
     * This is an identity gate, never a native dependency-completeness gate.
     */
    public static void verifySelected(Path home, byte[] manifest, String tool) throws IOException {
        if (!KNOWN.contains(tool)) throw new IOException("unknown LLVM evidence tool: " + tool);
        verify(home, manifest);
        if (!new String(manifest, StandardCharsets.UTF_8).contains("\ntool\t" + tool + "\t")) {
            throw new IOException("selected LLVM tool absent from evidence: " + tool);
        }
    }

    /** Reject oversized and symlinked external manifests before allocating bytes. */
    public static byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw new IOException("LLVM toolchain manifest must be a regular file within the byte budget");
        }
        return Files.readAllBytes(file);
    }

    /** Output is committed only after a complete, valid manifest exists. */
    public static void write(Path home, Path output, List<String> tools) throws IOException {
        Path canonicalHome = home.toRealPath();
        Path target = output.toAbsolutePath().normalize();
        if (target.startsWith(canonicalHome)) {
            throw new IOException("LLVM evidence output must be outside its toolchain");
        }
        if (Files.isSymbolicLink(target)) throw new IOException("LLVM evidence output may not be a symlink");
        byte[] contents = inspect(home, tools);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("LLVM evidence output directory missing");
        if (parent.toRealPath().startsWith(canonicalHome)) {
            throw new IOException("LLVM evidence output directory aliases the toolchain");
        }
        Path temporary = Files.createTempFile(parent, ".m3-llvm-evidence-", ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic LLVM evidence publication unavailable", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String relative(Path root, Path file) throws IOException {
        String value = root.relativize(file).toString().replace('\\', '/');
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("LLVM toolchain paths must not contain control characters");
        }
        return value;
    }

    private static String hash(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[65536];
            for (int n; (n = in.read(chunk)) >= 0;) digest.update(chunk, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * Portable CLI: scan <home> <manifest> <comma-separated tools>
     * or verify <home> <manifest>. Neither operation runs any LLVM executable.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "scan".equals(args[0])) {
            write(Path.of(args[1]), Path.of(args[2]), List.of(args[3].split(",", -1)));
            System.out.println("SEALED " + Path.of(args[2]).toAbsolutePath().normalize());
        } else if (args.length == 3 && "verify".equals(args[0])) {
            verify(Path.of(args[1]), read(Path.of(args[2])));
            System.out.println("VERIFIED " + Path.of(args[2]).toAbsolutePath().normalize());
        } else {
            throw new IllegalArgumentException("usage: scan <llvm-home> <manifest.tsv> <comma-separated-tools> | verify <llvm-home> <manifest.tsv>");
        }
    }
}
