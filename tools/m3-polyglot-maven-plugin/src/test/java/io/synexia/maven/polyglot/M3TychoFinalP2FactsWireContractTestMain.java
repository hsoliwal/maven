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

/** No Tycho, Equinox, Maven runtime or dependency download required. */
public final class M3TychoFinalP2FactsWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";

    private M3TychoFinalP2FactsWireContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        byte[] source = fixture();
        String packet = new String(source, StandardCharsets.UTF_8);
        var facts = M3TychoFinalP2FactsWire.decode(source);
        var eclipse = M3EclipseBuildHandoffWire.decode(handoff("EclipsePDE", "Debug", INVOCATION));
        var accepted = M3TychoFinalP2FactsWire.bind(facts, eclipse);
        require(facts.groupId().equals("org.example") && facts.artifactId().equals("pde"),
                "original Tycho Maven coordinates");
        require(facts.eclipseProject().equals("EclipsePDE"),
                "Eclipse .project is independent from Maven artifact ID");
        require(facts.stage().equals("final") && facts.unitCount() == 2,
                "final p2 IUs, not the preliminary target");
        require(facts.unitsSha256().equals("a".repeat(64)), "original p2 unit digest");
        require(facts.os().equals("linux") && facts.ws().equals("gtk")
                && facts.arch().equals("x86_64"), "target environment is not the caller's host");
        require(!facts.authorizesBuildSkipping(), "p2 target IU metadata is not cache admission");
        require(accepted.toJson().startsWith(
                "{\"schema\":\"m3-tycho-final-p2-facts-v1\",\"provider\":\"tycho.p2\""),
                "canonical original source-owner report schema");
        require(accepted.toJson().contains("\"authorizesBuildSkipping\":false"),
                "Tycho target identities never skip compilation");
        require(accepted.toJson().equals(M3TychoFinalP2FactsWire.bind(
                M3TychoFinalP2FactsWire.decode(source), eclipse).toJson()), "deterministic decoder");

        refuses(packet.replace(M3TychoFinalP2FactsWire.MAGIC,
                "M3-TYCHO-FINAL-P2-FACTS-V2"), "unrecognized source-owner version");
        refuses(packet.replace("stage\tfinal", "stage\tpreliminary"), "preliminary p2 is never accepted");
        refuses(packet.replace("unitCount\t2", "unitCount\t02"), "noncanonical unit count");
        refuses(packet.replace("unitCount\t2", "unitCount\t999999999"), "oversized p2 unit count");
        refuses(packet.replace("unitsSha256\t" + "a".repeat(64),
                "unitsSha256\t" + "A".repeat(64)), "uppercase identity digest");
        refuses(packet.replace("eclipseProject64\t" + b64("EclipsePDE"),
                "eclipseProject64\t" + b64("EclipsePDE") + "="), "noncanonical Base64URL");
        refuses(packet.replace("provider\ttycho.p2", "provider\tvendor.p2"), "foreign platform provider");
        refuses(packet.replace("group64", "mavenGroup64"), "unknown source metadata field");
        refuses(packet.replace("\n", "\r\n"), "noncanonical CRLF");
        refuses("\uFEFF" + packet, "BOM");
        refuses(packet.substring(0, packet.length() - 1), "missing final newline");
        foreign(facts, handoff("Different", "Debug", INVOCATION), "Eclipse project mismatch");
        foreign(facts, handoff("EclipsePDE", "Release", INVOCATION),
                "Eclipse configuration mismatch");
        foreign(facts, handoff("EclipsePDE", "Debug", "c111dc33-33bd-4f33-8aa9-6c3c11223344"),
                "different original invocation");

        Path root = Files.createTempDirectory("m3-tycho-final-p2-");
        try {
            Path tycho = Files.write(root.resolve("tycho.ide"), source);
            Path ide = Files.write(root.resolve("eclipse.ide"), handoff("EclipsePDE", "Debug", INVOCATION));
            require(M3TychoFinalP2FactsWire.inspect(tycho, ide).toJson().equals(accepted.toJson()),
                    "Tycho source-owner file custody and exact Eclipse handoff");
            try {
                Path redirect = root.resolve("redirect.ide");
                Files.createSymbolicLink(redirect, tycho);
                try {
                    M3TychoFinalP2FactsWire.read(redirect);
                    throw new AssertionError("source-owner symlink was accepted");
                } catch (IOException expected) {
                    // Canonical original file is the only admitted source.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows CI without symlink privileges.
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: original Tycho FINAL p2 target facts and Eclipse build invocation");
    }

    private static void foreign(M3TychoFinalP2FactsWire.Snapshot facts,
            byte[] sourceHandoff, String description) throws IOException {
        try {
            M3TychoFinalP2FactsWire.bind(facts,
                    M3EclipseBuildHandoffWire.decode(sourceHandoff));
            throw new AssertionError("accepted foreign Tycho p2 evidence: " + description);
        } catch (IOException expected) {
            // Cross-project/configuration/generation splicing is forbidden.
        }
    }

    private static void refuses(String source, String description) {
        try {
            M3TychoFinalP2FactsWire.decode(source.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("accepted invalid Tycho p2 packet: " + description);
        } catch (IOException expected) {
            // Fail closed and do not infer a complete p2 build.
        }
    }

    private static byte[] fixture() throws IOException {
        try (var input = M3TychoFinalP2FactsWireContractTestMain.class.getResourceAsStream(
                "/m3-tycho-final-p2-facts-v1.fixture")) {
            if (input == null) throw new IOException("missing original Tycho/Maven golden wire");
            return input.readAllBytes();
        }
    }

    private static byte[] handoff(String project, String selected, String invocation) {
        String text = "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
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
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
