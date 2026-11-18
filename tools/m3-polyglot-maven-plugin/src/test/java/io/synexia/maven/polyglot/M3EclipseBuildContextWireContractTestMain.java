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

/** No Maven, Eclipse or Gradle runtime. Original owner packets are not build-cache receipts. */
public final class M3EclipseBuildContextWireContractTestMain {
    private M3EclipseBuildContextWireContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        String source = valid();
        var fact = M3EclipseBuildContextWire.decode(source.getBytes(StandardCharsets.UTF_8));
        require(fact.provider().equals("eclipse.m2e"), "original Eclipse source owner");
        require(fact.project().equals("Mixed"), "project identity");
        require(fact.selectedConfiguration().equals("Debug"), "selected configuration");
        require(fact.activeConfiguration().equals("Release"), "active != selected");
        require(fact.configurations().equals(java.util.List.of("Debug", "Release")), "original order");
        require(fact.builders().equals(java.util.List.of("org.eclipse.jdt.core.javabuilder",
                "org.eclipse.cdt.managedbuilder.core.genmakebuilder")), "original builder order");
        require(fact.natures().equals(java.util.List.of("vendor.rustdt.nature")),
                "future and nature-only language extensions preserved");
        require(fact.toJson().startsWith(
                "{\"schema\":\"m3-eclipse-build-context-v1\",\"provider\":\"eclipse.m2e\","
                + "\"project\":\"Mixed\",\"selectedConfiguration\":\"Debug\","
                + "\"activeConfiguration\":\"Release\""), "canonical JSON");
        require(fact.toJson().equals(M3EclipseBuildContextWire.decode(
                source.getBytes(StandardCharsets.UTF_8)).toJson()), "stable codec");

        refuses(source.replace("M3-ECLIPSE-BUILD-CONTEXT-V1", "M3-ECLIPSE-BUILD-CONTEXT-V9"),
                "future schema");
        refuses(source.substring(0, source.length() - 1), "missing final LF");
        refuses(source.replace("\n", "\r\n"), "CRLF");
        refuses("\uFEFF" + source, "BOM");
        refuses(source.replace("selected64\t" + b64("Debug"), "selected64\t" + b64("Unknown")),
                "selected config absent");
        refuses(source.replace("configuration64\t" + b64("Release") + "\n",
                "configuration64\t" + b64("Debug") + "\n"), "duplicate config");
        refuses(source.replace("builder64\t", "builder64\t!!"), "malformed base64");
        refuses(source.replace("builder64\t" + b64("org.eclipse.jdt.core.javabuilder"),
                "nature64\t" + b64("org.eclipse.jdt.core.javabuilder")), "out-of-order rows");
        refuses(source.replace("project64\t" + b64("Mixed"), "project64\t" + b64("M") + "="),
                "noncanonical padding");
        refuses(source.replace("provider\teclipse.m2e", "provider\tbad/provider"),
                "unsafe source owner");

        Path temp = Files.createTempDirectory("m3-eclipse-context-");
        try {
            Path packet = Files.writeString(temp.resolve("owner.ide"), source);
            require(M3EclipseBuildContextWire.read(packet).toJson().equals(fact.toJson()),
                    "real canonical packet");
            Path linked = temp.resolve("linked.ide");
            try {
                Files.createSymbolicLink(linked, packet);
                try {
                    M3EclipseBuildContextWire.read(linked);
                    throw new AssertionError("symlink packet was accepted");
                } catch (IOException expected) {
                    // Never follow a foreign or mutable source.
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Hosts without symlink creation still enforce NOFOLLOW_LINKS.
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("PASS: M3 Eclipse per-configuration owner packet contract");
    }

    private static String valid() {
        return M3EclipseBuildContextWire.MAGIC + "\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64("Mixed") + "\n"
                + "selected64\t" + b64("Debug") + "\n"
                + "active64\t" + b64("Release") + "\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("org.eclipse.jdt.core.javabuilder") + "\n"
                + "builder64\t" + b64("org.eclipse.cdt.managedbuilder.core.genmakebuilder") + "\n"
                + "nature64\t" + b64("vendor.rustdt.nature") + "\n";
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void refuses(String wire, String reason) {
        try {
            M3EclipseBuildContextWire.decode(wire.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("invalid Eclipse owner claim accepted: " + reason);
        } catch (IOException expected) {
            // Fail closed, never infer a usable build configuration.
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
