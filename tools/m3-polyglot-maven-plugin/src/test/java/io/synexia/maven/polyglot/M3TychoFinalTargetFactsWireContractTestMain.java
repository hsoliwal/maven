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

/** Original Tycho final p2 target evidence, no OSGi, Maven or Gradle runtime. */
public final class M3TychoFinalTargetFactsWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private M3TychoFinalTargetFactsWireContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        byte[] original = golden();
        String source = new String(original, StandardCharsets.UTF_8);
        var facts = M3TychoFinalTargetFactsWire.decode(original);
        var eclipse = M3EclipseBuildHandoffWire.decode(handoff("Sample", "Debug", INVOCATION));
        var valid = M3TychoFinalTargetFactsWire.bind(facts, eclipse);
        require(facts.eclipseProject().equals("Sample"), "original Eclipse source project");
        require(facts.reactorGroup().equals("example.bundle")
                && facts.reactorArtifact().equals("sample")
                && facts.reactorVersion().equals("1.2.3")
                && facts.reactorPackaging().equals("eclipse-plugin"), "original Tycho reactor coordinates");
        require(facts.unitCount() == 3 && facts.reactorOriginCount() == 2
                && facts.mavenOriginCount() == 1, "original final p2 target unit counts");
        require(facts.unitSha256().equals("a".repeat(64)), "Tycho source-owner digest preserved");
        require(!facts.authorizesBuildSkipping(), "p2 coordinates never authorize goal skipping");
        require(!valid.authorizesBuildSkipping(), "Tycho evidence never admits cache replay");
        require(valid.eclipseBuildKind().equals("INCREMENTAL") && valid.originalDeltaComplete(),
                "original Eclipse invocation still authoritative");
        require(valid.toJson().startsWith(
                "{\"schema\":\"m3-tycho-final-target-facts-v1\",\"provider\":\"eclipse.tycho\""),
                "distinct versioned Tycho JSON");
        require(valid.toJson().contains("\"authorizesBuildSkipping\":false"),
                "Tycho metadata never declares a successful build");
        require(valid.toJson().equals(M3TychoFinalTargetFactsWire.bind(
                M3TychoFinalTargetFactsWire.decode(original), eclipse).toJson()),
                "deterministic standalone Tycho facts");

        rejectOwner(facts, handoff("another.project", "Debug", INVOCATION), "foreign project");
        rejectOwner(facts, handoff("Sample", "Release", INVOCATION), "foreign selected Eclipse config");
        rejectOwner(facts, handoff("Sample", "Debug",
                "c111dc33-33bd-4f33-8aa9-6c3c11223344"), "stale invocation");

        refuses(source.replace("M3-TYCHO-FINAL-TARGET-FACTS-V1",
                "M3-TYCHO-FINAL-TARGET-FACTS-V2"), "unknown schema");
        refuses("\uFEFF" + source, "BOM");
        refuses(source.replace("\n", "\r\n"), "noncanonical CRLF");
        refuses(source.replace("provider\teclipse.tycho", "provider\tforeign.p2"), "foreign owner");
        refuses(source.replace("project64\t" + b64("Sample"),
                "project64\t" + b64("Sample") + "="), "padded Base64URL");
        refuses(source.replace("unitSha256\t" + "a".repeat(64),
                "unitSha256\t" + "A".repeat(64)), "uppercase hash");
        refuses(source.replace("unitCount\t3", "unitCount\t03"), "noncanonical count");
        refuses(source.replace("unitCount\t3", "unitCount\t999999"), "unbounded units");
        refuses(source.replace("reactorPackaging64", "packaging64"), "unknown field");
        refuses(source.substring(0, source.length()-1), "missing LF");

        Path directory = Files.createTempDirectory("m3-tycho-p2-facts-");
        try {
            Path packet = Files.write(directory.resolve("target.ide"), original);
            Path handoff = Files.write(directory.resolve("eclipse.ide"),
                    handoff("Sample", "Debug", INVOCATION));
            require(M3TychoFinalTargetFactsWire.inspect(packet, handoff).toJson()
                    .equals(valid.toJson()), "original source file handoff");
            try {
                Path link = directory.resolve("redirect.ide");
                Files.createSymbolicLink(link, packet);
                try {
                    M3TychoFinalTargetFactsWire.read(link);
                    throw new AssertionError("linked Tycho evidence accepted");
                } catch (IOException expected) {
                    // Never allow a substituted source-owner file.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows CI may disallow symlink creation.
            }
        } finally {
            try (var stream = Files.walk(directory)) {
                for (Path file : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: final Tycho p2 target and exact Eclipse owner generation");
    }

    private static byte[] golden() throws IOException {
        try (var input = M3TychoFinalTargetFactsWireContractTestMain.class.getResourceAsStream(
                "/m3-tycho-final-target-facts-v1.fixture")) {
            if (input == null) throw new IOException("original Tycho/Maven golden fixture unavailable");
            return input.readAllBytes();
        }
    }

    private static void rejectOwner(M3TychoFinalTargetFactsWire.Snapshot facts,
            byte[] owner, String reason) throws IOException {
        try {
            M3TychoFinalTargetFactsWire.bind(facts, M3EclipseBuildHandoffWire.decode(owner));
            throw new AssertionError("accepted " + reason);
        } catch (IOException expected) {
            // Tycho must never silently substitute another project/configuration/invocation.
        }
    }

    private static byte[] handoff(String project, String selected, String invocation) {
        String source = "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "active64\t" + b64("Debug") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("org.eclipse.pde.ManifestBuilder") + "\n"
                + "nature64\t" + b64("org.eclipse.pde.PluginNature") + "\n"
                + "change\tMODIFIED\tMETA-INF/MANIFEST.MF\n";
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void refuses(String source, String reason) {
        try {
            M3TychoFinalTargetFactsWire.decode(source.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("accepted malformed Tycho source-owner packet: " + reason);
        } catch (IOException expected) {
            // Independent Java 17 parser always refuses malformed/foreign facts.
        }
    }

    private static void require(boolean accepted, String message) {
        if (!accepted) throw new AssertionError(message);
    }
}
