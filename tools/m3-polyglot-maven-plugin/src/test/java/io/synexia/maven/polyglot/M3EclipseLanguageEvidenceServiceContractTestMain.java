/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Standalone no-SDK Java 17 source-owner extension test for future languages. */
public final class M3EclipseLanguageEvidenceServiceContractTestMain {
    private static final String UUID = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private M3EclipseLanguageEvidenceServiceContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("m3-eclipse-language-spi-");
        try {
            Path packet = Files.writeString(root.resolve("pydev-owner.ide"), source("PyProject", UUID, "Debug"));
            Path eclipse = Files.writeString(root.resolve("eclipse-owner.ide"),
                    handoff("PyProject", UUID, "Debug", M3TestPyDevEvidenceProvider.NATURE));
            var owner = M3EclipseBuildHandoffWire.read(eclipse);
            var fixed = M3EclipseLanguageEvidenceService.inspect(
                    "test.pydev", packet, owner, List.of(new M3TestPyDevEvidenceProvider()));
            require(fixed.providerId().equals("test.pydev"), "exact installed SPI ID");
            require(fixed.schema().equals("M3-TEST-PYDEV-FACTS-V1"), "source-owned versioned schema");
            require(fixed.project().equals("PyProject") && fixed.selectedConfiguration().equals("Debug"),
                    "original Eclipse project and configuration retained");
            require(fixed.observedCount() == 7 && fixed.observedSha256().equals("a".repeat(64)),
                    "synthetic owner digest is not reinterpreted by Maven");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(packet)));
            require(fixed.originalPacketSha256().equals(sha), "host observes exact input bytes once");
            require(!fixed.authorizesBuildSkipping(), "no build/cache permission from metadata");
            require(fixed.toJson().startsWith(
                    "{\"schema\":\"m3-eclipse-language-facts-v1\",\"providerId\":\"test.pydev\""),
                    "stable universal report schema");
            require(fixed.toJson().contains("\"authorizesBuildSkipping\":false"),
                    "immutable safe host result");

            var discovered = M3EclipseLanguageEvidenceService.inspect("test.pydev", packet, eclipse);
            require(discovered.toJson().equals(fixed.toJson()),
                    "real ServiceLoader must discover the installed synthetic provider");

            refuses(() -> M3EclipseLanguageEvidenceService.inspect("missing.provider", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider())), "no installed provider");
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider(), new M3TestPyDevEvidenceProvider())),
                    "duplicate SPI provider ID");
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    M3EclipseBuildHandoffWire.decode(handoff("PyProject", UUID, "Debug",
                            "vendor.otherNature").getBytes(StandardCharsets.UTF_8)),
                    List.of(new M3TestPyDevEvidenceProvider())), "no exact Eclipse nature match");
            Files.writeString(packet, source("AnotherProject", UUID, "Debug"));
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider())), "foreign Eclipse project");
            Files.writeString(packet, source("PyProject", UUID, "Release"));
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider())), "foreign selection");
            Files.writeString(packet, source("PyProject",
                    "c111dc33-33bd-4f33-8aa9-6c3c11223344", "Debug"));
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider())), "foreign generation");
            Files.writeString(packet, source("PyProject", UUID, "Debug").replace(
                    "M3-TEST-PYDEV-FACTS-V1", "M3-TEST-PYDEV-FACTS-V2"));
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3TestPyDevEvidenceProvider())), "unknown language schema");

            Files.writeString(packet, source("PyProject", UUID, "Debug"));
            try {
                Path symlink = root.resolve("redirect.ide");
                Files.createSymbolicLink(symlink, packet);
                refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", symlink,
                        owner, List.of(new M3TestPyDevEvidenceProvider())), "symlinked source packet");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                // NOFOLLOW_LINKS is still enforced where Windows does not allow symlink creation.
            }
            refuses(() -> M3EclipseLanguageEvidenceService.inspect("test.pydev", packet,
                    owner, List.of(new M3EclipseLanguageEvidenceProvider() {
                        @Override public String id() { return "test.pydev"; }
                        @Override public int apiVersion() { return 999; }
                        @Override public Set<String> builderIds() { return Set.of(); }
                        @Override public Set<String> natureIds() {
                            return Set.of(M3TestPyDevEvidenceProvider.NATURE);
                        }
                        @Override public Evidence decode(byte[] bytes) {
                            throw new AssertionError("incompatible provider must never execute");
                        }
                    })), "wrong SPI major version");
            System.out.println("PASS: generic Eclipse language evidence SPI source-owner contracts");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static String source(String project, String invocation, String selected) {
        return "M3-TEST-PYDEV-FACTS-V1\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "observedCount\t7\n"
                + "observedSha256\t" + "a".repeat(64) + "\n";
    }

    private static String handoff(String project, String invocation, String selected, String nature) {
        return "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64(project) + "\n"
                + "invocation\t" + invocation + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "active64\t" + b64("Debug") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "nature64\t" + b64(nature) + "\n"
                + "change\tMODIFIED\tsrc/module.py\n";
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static void refuses(Checked action, String why) throws Exception {
        try {
            action.run();
            throw new AssertionError("unsafe language evidence accepted: " + why);
        } catch (IOException expected) {
            // Always fail closed, no source-owner fact is silently accepted.
        }
    }

    private static void require(boolean success, String why) {
        if (!success) throw new AssertionError(why);
    }
}
