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

/** Original CDT compiler configuration wire, without CDT or Gradle runtime. */
public final class M3CdtCompilerFactsWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";

    private M3CdtCompilerFactsWireContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        byte[] packet = facts("Mixed", "Debug", INVOCATION).getBytes(StandardCharsets.UTF_8);
        var cdt = M3CdtCompilerFactsWire.decode(packet);
        var eclipse = eclipse();
        var accepted = M3CdtCompilerFactsWire.bind(cdt, eclipse);
        require(cdt.project().equals("Mixed") && cdt.invocation().equals(INVOCATION),
                "original CDT source owner project and invocation retained");
        require(cdt.eclipseSelected().equals("Debug"), "selected Eclipse configuration");
        require(cdt.cdtConfigId().equals("cdt.native.debug"), "exact selected CDT ID");
        require(cdt.cdtActiveConfigId().equals("cdt.native.release"),
                "active CDT configuration must remain distinct from selected CDT");
        require(cdt.resources() == 2 && cdt.languages() == 3 && cdt.entries() == 17,
                "bounded original scanner entry counts");
        require(accepted.toJson().startsWith(
                "{\"schema\":\"m3-cdt-compiler-facts-v1\",\"provider\":\"eclipse.cdt\""),
                "canonical report schema");
        require(accepted.toJson().contains("\"authorizesBuildSkipping\":false"),
                "native C/C++ scanner facts cannot authorize cache restoration");
        require(accepted.originalDeltaComplete() && accepted.eclipseBuildKind().equals("INCREMENTAL"),
                "original m2e delta facts retained");
        require(accepted.toJson().equals(M3CdtCompilerFactsWire.bind(
                M3CdtCompilerFactsWire.decode(packet), eclipse).toJson()),
                "deterministic original model serialization");

        foreign(facts("Another", "Debug", INVOCATION), eclipse, "foreign CDT project");
        foreign(facts("Mixed", "Release", INVOCATION), eclipse, "different Eclipse configuration");
        foreign(facts("Mixed", "Debug", "c111dc33-33bd-4f33-8aa9-6c3c11223344"),
                eclipse, "unrelated m2e invocation");
        refuses(facts("Mixed", "Debug", INVOCATION).replace(
                M3CdtCompilerFactsWire.MAGIC, "M3-CDT-COMPILER-FACTS-V2"), "future format");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("\n", "\r\n"), "CRLF");
        refuses("\uFEFF" + facts("Mixed", "Debug", INVOCATION), "BOM");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("resourceCount\t2", "resourceCount\t02"),
                "noncanonical counts");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("entryCount\t17", "entryCount\t999999"),
                "overlarge entry count");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("scannerSha256\t" + "a".repeat(64),
                "scannerSha256\t" + "A".repeat(64)), "uppercase digest");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("provider\teclipse.cdt",
                "provider\tvendor.other"), "foreign CDT provider");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("cdtConfigId64",
                "cdtSelectedId64"), "unknown field");
        refuses(facts("Mixed", "Debug", INVOCATION).replace("project64\t" + b64("Mixed"),
                "project64\t" + b64("Mixed") + "="), "noncanonical base64");
        refuses(facts("Mixed", "Debug", INVOCATION).substring(
                0, facts("Mixed", "Debug", INVOCATION).length() - 1), "final LF absent");

        Path root = Files.createTempDirectory("m3-cdt-compiler-facts-");
        try {
            Path nativePacket = Files.write(root.resolve("cdt.ide"), packet);
            Path eclipsePacket = Files.write(root.resolve("handoff.ide"), eclipseBytes());
            require(M3CdtCompilerFactsWire.inspect(nativePacket, eclipsePacket)
                    .toJson().equals(accepted.toJson()), "original CDT owner file custody");
            try {
                Path symlink = root.resolve("redirect.ide");
                Files.createSymbolicLink(symlink, nativePacket);
                try {
                    M3CdtCompilerFactsWire.read(symlink);
                    throw new AssertionError("CDT symlinked evidence must be rejected");
                } catch (IOException expected) {
                    // The original packet path, not a replacement, is authoritative.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // No symlink privileges on some Windows hosts.
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: CDT source-owned scanner evidence and exact Eclipse handoff");
    }

    private static M3EclipseBuildHandoffWire.Snapshot eclipse() throws IOException {
        return M3EclipseBuildHandoffWire.decode(eclipseBytes());
    }

    private static byte[] eclipseBytes() throws IOException {
        try (var input = M3CdtCompilerFactsWireContractTestMain.class.getResourceAsStream(
                "/m3-eclipse-build-handoff-v1.fixture")) {
            if (input == null) throw new IOException("missing original Eclipse handoff fixture");
            return input.readAllBytes();
        }
    }

    private static String facts(String project, String eclipseSelected, String invocation) {
        return M3CdtCompilerFactsWire.MAGIC + "\n"
                + "provider\teclipse.cdt\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "eclipseSelected64\t" + b64(eclipseSelected) + "\n"
                + "cdtConfigId64\t" + b64("cdt.native.debug") + "\n"
                + "cdtConfigName64\t" + b64("Debug (native)") + "\n"
                + "cdtActiveId64\t" + b64("cdt.native.release") + "\n"
                + "buildSystem64\t" + b64("org.eclipse.cdt.managedbuilder") + "\n"
                + "scannerSha256\t" + "a".repeat(64) + "\n"
                + "sourcesSha256\t" + "b".repeat(64) + "\n"
                + "buildSettingSha256\t" + "c".repeat(64) + "\n"
                + "resourceCount\t2\n"
                + "languageCount\t3\n"
                + "entryCount\t17\n";
    }

    private static String b64(String string) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(string.getBytes(StandardCharsets.UTF_8));
    }

    private static void foreign(String original, M3EclipseBuildHandoffWire.Snapshot handoff, String reason)
            throws Exception {
        try {
            M3CdtCompilerFactsWire.bind(
                    M3CdtCompilerFactsWire.decode(original.getBytes(StandardCharsets.UTF_8)), handoff);
            throw new AssertionError("CDT handoff allowed " + reason);
        } catch (IOException expected) {
            // Foreign original owner metadata is never considered a successful build.
        }
    }

    private static void refuses(String original, String reason) {
        try {
            M3CdtCompilerFactsWire.decode(original.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("invalid CDT packet accepted: " + reason);
        } catch (IOException expected) {
            // Fail closed; no inferred language/toolchain configuration.
        }
    }

    private static void require(boolean acceptable, String name) {
        if (!acceptable) throw new AssertionError(name);
    }
}
