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
import java.util.List;

/** Cross-language Eclipse owner-handoff contract, runnable without Maven or Eclipse. */
public final class M3EclipseBuildHandoffWireContractTestMain {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    private M3EclipseBuildHandoffWireContractTestMain() { }

    public static void main(String[] args) throws Exception {
        String original = valid();
        var snapshot = M3EclipseBuildHandoffWire.decode(original.getBytes(StandardCharsets.UTF_8));
        try (var fixture = M3EclipseBuildHandoffWireContractTestMain.class.getResourceAsStream(
                "/m3-eclipse-build-handoff-v1.fixture")) {
            if (fixture == null) throw new AssertionError("missing shared m2e/Maven golden wire fixture");
            byte[] originalBytes = fixture.readAllBytes();
            require(java.util.Arrays.equals(originalBytes, original.getBytes(StandardCharsets.UTF_8)),
                    "original m2e producer fixture must match the Maven consumer test exactly");
            require(M3EclipseBuildHandoffWire.decode(originalBytes).toJson().equals(snapshot.toJson()),
                    "cross-repository canonical bytes decode identically");
        }
        require(snapshot.project().equals("Mixed"), "original Eclipse project ID");
        require(snapshot.invocation().equals(INVOCATION), "single bound build invocation");
        require(snapshot.selectedConfiguration().equals("Debug"), "selected build config");
        require(snapshot.activeConfiguration().equals("Release"), "active config may differ");
        require(snapshot.configurations().equals(List.of("Debug", "Release")), "config order");
        require(snapshot.builders().equals(List.of("org.eclipse.jdt.core.javabuilder",
                "org.eclipse.cdt.managedbuilder.core.genmakebuilder")), "original builder order");
        require(snapshot.natures().equals(List.of("vendor.rustdt.nature")), "future nature preserved");
        require(snapshot.deltaComplete() && snapshot.buildKind().equals("INCREMENTAL"),
                "original delta status");
        require(snapshot.size() == 2, "compact change capacity");
        require(snapshot.entry(0).equals(new M3EclipseBuildHandoffWire.Change(
                "src/A.java", M3EclipseBuildHandoffWire.ChangeKind.ADDED)), "source addition");
        require(snapshot.entry(1).kind() == M3EclipseBuildHandoffWire.ChangeKind.MODIFIED,
                "source modification");
        require(snapshot.toJson().startsWith(
                "{\"schema\":\"m3-eclipse-build-handoff-v1\",\"provider\":\"eclipse.m2e\""),
                "versioned diagnostics");
        String firstScope = snapshot.scopeSha256("workspace-1");
        require(firstScope.matches("[a-f0-9]{64}"), "stable SHA-256 identity");
        require(firstScope.equals(snapshot.scopeSha256("workspace-1")), "stable identity");
        require(!firstScope.equals(snapshot.scopeSha256("workspace-2")),
                "different Eclipse workspaces may not share a build scope");
        String alternate = valid().replace("selected64\t" + b64("Debug"),
                "selected64\t" + b64("Release"));
        require(!firstScope.equals(M3EclipseBuildHandoffWire.decode(
                alternate.getBytes(StandardCharsets.UTF_8)).scopeSha256("workspace-1")),
                "Debug and Release remain distinct for M3DB state");
        // A new invocation does not make a different configuration scope.
        String nextInvocation = valid().replace(INVOCATION, "c111dc33-33bd-4f33-8aa9-6c3c11223344");
        require(firstScope.equals(M3EclipseBuildHandoffWire.decode(
                nextInvocation.getBytes(StandardCharsets.UTF_8)).scopeSha256("workspace-1")),
                "invocation identity is not the stable configuration scope");

        refuses(original.replace(M3EclipseBuildHandoffWire.MAGIC, "M3-ECLIPSE-BUILD-HANDOFF-V99"),
                "unsupported wire");
        refuses(original.substring(0, original.length() - 1), "no terminal newline");
        refuses(original.replace("\n", "\r\n"), "wrong line endings");
        refuses("\uFEFF" + original, "BOM");
        refuses(original.replace("provider\teclipse.m2e", "provider\tanother.host"), "foreign owner");
        refuses(original.replace(INVOCATION, "bad-id"), "invocation not UUID");
        refuses(original.replace("complete\ttrue", "complete\tTRUE"), "uncanonical boolean");
        refuses(original.replace("kind\tINCREMENTAL", "kind\tFULL"), "invalid full delta claim");
        refuses(original.replace("configuration64\t" + b64("Release"),
                "configuration64\t" + b64("Debug")), "duplicate configs");
        refuses(original.replace("selected64\t" + b64("Debug"),
                "selected64\t" + b64("Other")), "unknown selected config");
        refuses(original.replace("builder64\t", "builder64\t*"), "invalid base64");
        refuses(original.replace("change\tADDED\tsrc/A.java", "change\tADDED\t../unsafe.java"),
                "unsafe path");
        refuses(original.replace("change\tMODIFIED\tsrc/B.java", "change\tMODIFIED\tsrc/A.java"),
                "duplicate path");
        refuses(original.replace("change\tADDED\tsrc/A.java", "change\tBOGUS\tsrc/A.java"),
                "unknown source delta kind");
        refuses(original.replace("builder64\t" + b64("org.eclipse.jdt.core.javabuilder"),
                "nature64\t" + b64("org.eclipse.jdt.core.javabuilder")), "out-of-order facts");

        Path dir = Files.createTempDirectory("m3-eclipse-handoff-");
        try {
            Path packet = Files.writeString(dir.resolve("handoff.ide"), original);
            require(M3EclipseBuildHandoffWire.read(packet).toJson().equals(snapshot.toJson()),
                    "exact source-owner handoff file");
            try {
                Path link = dir.resolve("linked.ide");
                Files.createSymbolicLink(link, packet);
                try {
                    M3EclipseBuildHandoffWire.read(link);
                    throw new AssertionError("symlink packet accepted");
                } catch (IOException expected) {
                    // Always refuse linked handoff evidence.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows CI without symlink privileges still checks no-follow paths.
            }
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: Eclipse same-invocation handoff and configuration scope");
    }

    private static String valid() {
        return M3EclipseBuildHandoffWire.MAGIC + "\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64("Mixed") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + b64("Debug") + "\n"
                + "active64\t" + b64("Release") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("org.eclipse.jdt.core.javabuilder") + "\n"
                + "builder64\t" + b64("org.eclipse.cdt.managedbuilder.core.genmakebuilder") + "\n"
                + "nature64\t" + b64("vendor.rustdt.nature") + "\n"
                + "change\tADDED\tsrc/A.java\n"
                + "change\tMODIFIED\tsrc/B.java\n";
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void refuses(String source, String label) {
        try {
            M3EclipseBuildHandoffWire.decode(source.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("invalid handoff accepted: " + label);
        } catch (IOException expected) {
            // Fail closed, without declaring a successful build.
        }
    }

    private static void require(boolean satisfied, String claim) {
        if (!satisfied) throw new AssertionError(claim);
    }
}
