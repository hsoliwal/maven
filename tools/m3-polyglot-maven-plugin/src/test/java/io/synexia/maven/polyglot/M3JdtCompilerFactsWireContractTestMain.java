/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

/** JVM-only contract: original JDT claims must match the original Eclipse build. */
public final class M3JdtCompilerFactsWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private M3JdtCompilerFactsWireContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        byte[] raw = packet("Mixed", "Debug", INVOCATION).getBytes(StandardCharsets.UTF_8);
        try (var fixture = M3JdtCompilerFactsWireContractTestMain.class.getResourceAsStream(
                "/m3-jdt-compiler-facts-v1.fixture")) {
            if (fixture == null) throw new AssertionError("missing original m2e JDT packet fixture");
            require(java.util.Arrays.equals(raw, fixture.readAllBytes()),
                    "Maven decoder fixture must match original JDT producer bytes");
        }
        var original = M3JdtCompilerFactsWire.decode(raw);
        var owner = M3EclipseBuildHandoffWire.decode(originalEclipseHandoff());
        var agreed = M3JdtCompilerFactsWire.bind(original, owner);
        require(original.project().equals("Mixed") && original.selectedConfiguration().equals("Debug"),
                "project and exact selected Eclipse configuration");
        require(original.rawCount() == 3 && original.resolvedCount() == 5, "JDT classpath counts");
        require(original.rawClasspathSha256().equals("a".repeat(64))
                && original.resolvedClasspathSha256().equals("b".repeat(64))
                && original.compilerOptionsSha256().equals("c".repeat(64)),
                "JDT owner SHA-256 values preserved without recomputing JDT classpaths");
        require(agreed.originalDeltaComplete() && agreed.eclipseBuildKind().equals("INCREMENTAL"),
                "original Eclipse invocation information retained");
        require(agreed.toJson().startsWith(
                "{\"schema\":\"m3-jdt-compiler-facts-v1\",\"provider\":\"eclipse.jdt\""),
                "stable consumer schema");
        require(agreed.toJson().contains("\"authorizesBuildSkipping\":false"),
                "matching JDT evidence must not skip compilation");
        require(!original.authorizesBuildSkipping(), "JDT metadata does not authenticate a successful compiler");
        require(agreed.toJson().equals(M3JdtCompilerFactsWire.bind(
                M3JdtCompilerFactsWire.decode(raw), owner).toJson()), "deterministic output");
        invalid(packet("Different", "Debug", INVOCATION).getBytes(StandardCharsets.UTF_8),
                "foreign JDT project rejected", owner);
        invalid(packet("Mixed", "Release", INVOCATION).getBytes(StandardCharsets.UTF_8),
                "different selected JDT configuration rejected", owner);
        invalid(packet("Mixed", "Debug", "c111dc33-33bd-4f33-8aa9-6c3c11223344")
                .getBytes(StandardCharsets.UTF_8), "stale JDT invocation rejected", owner);
        refuses(packet("Mixed", "Debug", INVOCATION).replace(
                M3JdtCompilerFactsWire.MAGIC, "M3-JDT-COMPILER-FACTS-V99"), "future version");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("\n", "\r\n"), "CRLF");
        refuses("\uFEFF" + packet("Mixed", "Debug", INVOCATION), "BOM");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("rawCount\t3", "rawCount\t03"),
                "noncanonical counts");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("resolvedCount\t5", "resolvedCount\t999999"),
                "overlarge resolved classpath");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("rawSha256\t" + "a".repeat(64),
                "rawSha256\t" + "A".repeat(64)), "uppercase SHA-256");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("project64\t" + b64("Mixed"),
                "project64\t" + b64("Mixed") + "="), "noncanonical project base64");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("provider\teclipse.jdt",
                "provider\tvendor.compiler"), "foreign compiler producer");
        refuses(packet("Mixed", "Debug", INVOCATION).replace("optionsSha256", "sourceSha256"),
                "unknown row label");
        refuses(packet("Mixed", "Debug", INVOCATION).substring(
                0, packet("Mixed", "Debug", INVOCATION).length() - 1), "missing final LF");
        Path temp = Files.createTempDirectory("m3-jdt-facts-");
        try {
            Path facts = Files.write(temp.resolve("jdt-facts.ide"), raw);
            Path handoff = Files.write(temp.resolve("eclipse-handoff.ide"), originalEclipseHandoff());
            require(M3JdtCompilerFactsWire.inspect(facts, handoff).toJson().equals(agreed.toJson()),
                    "original JDT and m2e source-owner file handoff");
            try {
                Path link = temp.resolve("linked.ide");
                Files.createSymbolicLink(link, facts);
                try {
                    M3JdtCompilerFactsWire.read(link);
                    throw new AssertionError("symlinked JDT packet was accepted");
                } catch (IOException expected) {
                    // Original source-owner packet cannot be redirected to another file.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows CI may not support test-created symlinks.
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("PASS: JDT source-owned compiler facts and original Eclipse generation");
    }

    private static byte[] originalEclipseHandoff() throws IOException {
        try (var input = M3JdtCompilerFactsWireContractTestMain.class.getResourceAsStream(
                "/m3-eclipse-build-handoff-v1.fixture")) {
            if (input == null) throw new IOException("missing original Eclipse handoff fixture");
            return input.readAllBytes();
        }
    }

    private static String packet(String project, String selected, String invocation) {
        return M3JdtCompilerFactsWire.MAGIC + "\n"
                + "provider\teclipse.jdt\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "output64\t" + b64("/Mixed/target/classes") + "\n"
                + "rawSha256\t" + "a".repeat(64) + "\n"
                + "resolvedSha256\t" + "b".repeat(64) + "\n"
                + "optionsSha256\t" + "c".repeat(64) + "\n"
                + "rawCount\t3\n"
                + "resolvedCount\t5\n";
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void invalid(byte[] raw, String label,
            M3EclipseBuildHandoffWire.Snapshot owner) throws IOException {
        try {
            M3JdtCompilerFactsWire.bind(M3JdtCompilerFactsWire.decode(raw), owner);
            throw new AssertionError(label);
        } catch (IOException expected) {
            // Source and selected configuration/UUID must match the original m2e invocation.
        }
    }

    private static void refuses(String packet, String label) {
        try {
            M3JdtCompilerFactsWire.decode(packet.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("invalid JDT source-owner record accepted: " + label);
        } catch (IOException expected) {
            // Canonical UTF-8, names, and digests are mandatory.
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
