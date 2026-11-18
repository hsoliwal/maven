/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.llvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Deterministic offline contracts with fake bytes, symlinks and failure cases. */
public final class LlvmToolchainEvidenceContractTestMain {
    private static int passed;
    private LlvmToolchainEvidenceContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("m3-llvm-evidence-");
        try {
            Path home = Files.createDirectories(temp.resolve("llvm install"));
            Path bin = Files.createDirectories(home.resolve("bin"));
            Path clang = file(bin, "clang", "one compiler body\n");
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            if (!windows) Files.createSymbolicLink(bin.resolve("clang++"), Path.of("clang"));
            else file(bin, "clang++", "one compiler body\n");
            file(bin, "opt", "one opt body\n");
            List<String> requested = List.of("opt", "clang++", "clang");
            byte[] manifest = LlvmToolchainEvidence.inspect(home, requested);
            String text = new String(manifest, StandardCharsets.UTF_8);
            check(text.startsWith("M3-LLVM-TOOLCHAIN-V1\ntool\tclang\t"), "canonical manifest ordering");
            check(!text.contains(home.toString()), "no absolute-root material in manifest");
            check(text.lines().count() == 4, "exact three tool rows");
            if (!windows) check(text.contains("tool\tclang++\tbin/clang++\tbin/clang\t"),
                    "logical clang++ driver name and physical executable target");
            LlvmToolchainEvidence.verify(home, manifest);
            check(true, "replay of the exact manifest");
            Path out = temp.resolve("evidence.tsv");
            LlvmToolchainEvidence.write(home, out, requested);
            check(java.util.Arrays.equals(Files.readAllBytes(out), manifest), "atomic manifest publication");
            LlvmToolchainEvidence.main(new String[]{"verify", home.toString(), out.toString()});
            check(true, "portable verify command");
            fail(() -> LlvmToolchainEvidence.inspect(home, List.of("clang", "clang")), "duplicate tool");
            fail(() -> LlvmToolchainEvidence.inspect(home, List.of("../clang")), "path escape tool name");
            fail(() -> LlvmToolchainEvidence.inspect(home, List.of("flang")), "missing requested driver");
            fail(() -> LlvmToolchainEvidence.write(home, home.resolve("evidence.tsv"), requested),
                    "no manifest written inside LLVM installation");
            fail(() -> LlvmToolchainEvidence.verify(home, "M3-LLVM-TOOLCHAIN-V1\ntool\tbogus\tbad\tbad\t1\t0\n"
                    .getBytes(StandardCharsets.UTF_8)), "invalid manifest tool");
            Files.writeString(clang, "changed body\n", StandardCharsets.UTF_8);
            fail(() -> LlvmToolchainEvidence.verify(home, manifest), "changed executable bytes");
            Files.writeString(clang, "one compiler body\n", StandardCharsets.UTF_8);
            LlvmToolchainEvidence.verify(home, manifest);
            check(true, "content restoration returns exact evidence");
            if (!windows) {
                Files.delete(bin.resolve("clang++"));
                Path external = file(temp, "external-compiler", "one compiler body\n");
                Files.createSymbolicLink(bin.resolve("clang++"), external);
                fail(() -> LlvmToolchainEvidence.inspect(home, requested),
                        "external symlink cannot claim LLVM provenance");
            }
            System.out.println("PASS: " + passed + " LLVM toolchain evidence assertions");
        } finally {
            try (Stream<Path> walk = Files.walk(temp)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Path file(Path parent, String name, String bytes) throws IOException {
        Path file = parent.resolve(name + (System.getProperty("os.name", "").startsWith("Windows")
                && !name.equals("external-compiler") ? ".exe" : ""));
        Files.writeString(file, bytes, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
        return file;
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void fail(Attempt action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { passed++; return; }
        throw new AssertionError("expected refusal: " + label);
    }
    private static void check(boolean test, String label) {
        if (!test) throw new AssertionError(label);
        passed++;
    }
}
