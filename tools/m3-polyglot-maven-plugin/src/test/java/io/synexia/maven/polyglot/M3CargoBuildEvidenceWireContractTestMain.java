/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;

/** Offline strict packet test: no Rust toolchain, Cargo network or compiler. */
public final class M3CargoBuildEvidenceWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";

    private M3CargoBuildEvidenceWireContractTestMain() { }

    public static void main(String[] args) throws Exception {
        byte[] original = golden();
        String source = new String(original, StandardCharsets.UTF_8);
        var facts = M3CargoBuildEvidenceWire.decode(original);
        var eclipse = M3EclipseBuildHandoffWire.decode(handoff("cargo-fixture", "Debug", INVOCATION));
        var bound = M3CargoBuildEvidenceWire.bind(facts, eclipse);
        require(facts.provider().equals("eclipse.corrosion"), "native source-owner identity");
        require(facts.project().equals("cargo-fixture"), "original Rust workspace project");
        require(facts.eclipseSelected().equals("Debug"), "exact Eclipse build selection");
        require(facts.invocation().equals(INVOCATION), "original invocation UUID");
        require(facts.manifestSha256().equals("a".repeat(64))
                && facts.lockSha256().equals("b".repeat(64))
                && facts.cargoMetadataSha256().equals("c".repeat(64)),
                "original native Cargo SHA-256 preserved");
        require(facts.metadataBytes() == 1234, "observed native Cargo metadata byte size");
        require(facts.cargoConfigSha256().equals("absent"), "unknown optional Cargo config retained");
        require(!facts.authorizesBuildSkipping(), "Cargo metadata cannot authorize compiler skip");
        require(bound.eclipseBuildKind().equals("AUTO") && bound.originalDeltaComplete(),
                "original Eclipse selected build generation retained");
        require(bound.toJson().startsWith(
                "{\"schema\":\"m3-cargo-build-facts-v1\",\"provider\":\"eclipse.corrosion\""),
                "stable cross-fork diagnostics");
        require(bound.toJson().contains("\"authorizesBuildSkipping\":false"),
                "no external Cargo outcome inferred");
        require(bound.toJson().equals(M3CargoBuildEvidenceWire.bind(
                M3CargoBuildEvidenceWire.decode(original), eclipse).toJson()),
                "deterministic source-owned packet decoding");

        foreign(facts, handoff("unrelated-project", "Debug", INVOCATION), "foreign Rust project");
        foreign(facts, handoff("cargo-fixture", "Release", INVOCATION), "wrong Eclipse build selection");
        foreign(facts, handoff("cargo-fixture", "Debug",
                "c111dc33-33bd-4f33-8aa9-6c3c11223344"), "wrong original invocation");

        refuses(source.replace("M3-CARGO-BUILD-FACTS-V1", "M3-CARGO-BUILD-FACTS-V2"),
                "new schema not silently accepted");
        refuses("\uFEFF" + source, "BOM");
        refuses(source.replace("\n", "\r\n"), "noncanonical CRLF");
        refuses(source.replace("provider\teclipse.corrosion", "provider\tforeign.rust"),
                "foreign Rust source owner");
        refuses(source.replace("project64\t" + b64("cargo-fixture"),
                "project64\t" + b64("cargo-fixture") + "="), "Base64URL padding");
        refuses(source.replace("metadataBytes\t1234", "metadataBytes\t01234"),
                "noncanonical metadata count");
        refuses(source.replace("metadataBytes\t1234", "metadataBytes\t99999999999"),
                "metadata exceeds limit");
        refuses(source.replace("lockSha256\t" + "b".repeat(64),
                "lockSha256\t" + "B".repeat(64)), "noncanonical digest");
        refuses(source.replace("manifestSha256", "manifestDigest"), "unexpected metadata row");
        refuses(source.substring(0, source.length() - 1), "missing final LF");

        Path temp = Files.createTempDirectory("m3-cargo-owner-packet-");
        try {
            Path cargoFile = Files.write(temp.resolve("cargo.ide"), original);
            Path eclipseFile = Files.write(temp.resolve("eclipse.ide"),
                    handoff("cargo-fixture", "Debug", INVOCATION));
            require(M3CargoBuildEvidenceWire.inspect(cargoFile, eclipseFile).toJson()
                    .equals(bound.toJson()), "source-owned Cargo and Eclipse files agree");
            try {
                Path symlink = temp.resolve("redirect.ide");
                Files.createSymbolicLink(symlink, cargoFile);
                try {
                    M3CargoBuildEvidenceWire.read(symlink);
                    throw new AssertionError("symlinked Cargo evidence accepted");
                } catch (IOException expected) {
                    // Do not follow alternative evidence paths.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Host without symlink privileges; NOFOLLOW_LINKS still enforced.
            }
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: original Corrosion/Cargo packet and same-invocation Eclipse binding");
    }

    private static byte[] golden() throws IOException {
        try (var in = M3CargoBuildEvidenceWireContractTestMain.class.getResourceAsStream(
                "/m3-cargo-build-facts-v1.fixture")) {
            if (in == null) throw new IOException("missing original Corrosion/Cargo golden fixture");
            return in.readAllBytes();
        }
    }

    private static void foreign(M3CargoBuildEvidenceWire.Snapshot facts, byte[] eclipse, String reason)
            throws IOException {
        try {
            M3CargoBuildEvidenceWire.bind(facts, M3EclipseBuildHandoffWire.decode(eclipse));
            throw new AssertionError("accepted " + reason);
        } catch (IOException expected) {
            // All three original owner identity dimensions are required.
        }
    }

    private static byte[] handoff(String project, String selected, String invocation) {
        String data = "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "active64\t" + b64("Debug") + "\n"
                + "kind\tAUTO\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("vendor.corrosion.builder") + "\n"
                + "nature64\t" + b64("vendor.corrosion.nature") + "\n"
                + "change\tMODIFIED\tsrc/lib.rs\n";
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void refuses(String original, String name) {
        try {
            M3CargoBuildEvidenceWire.decode(original.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("accepted invalid Cargo owner record: " + name);
        } catch (IOException expected) {
            // Fail closed on native-source evidence mismatches.
        }
    }

    private static void require(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }
}
