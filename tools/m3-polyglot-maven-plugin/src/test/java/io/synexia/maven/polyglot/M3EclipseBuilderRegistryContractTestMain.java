/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Offline, language-independent Eclipse builder-owner SPI qualification. */
public final class M3EclipseBuilderRegistryContractTestMain {
    private M3EclipseBuilderRegistryContractTestMain() { }

    public static final class JdtOwner implements M3EclipseBuilderProvider {
        @Override public String id() { return "test.jdt"; }
        @Override public Set<String> builderIds() {
            return Set.of("org.eclipse.jdt.core.javabuilder");
        }
        @Override public Set<String> natureIds() {
            return Set.of("org.eclipse.jdt.core.javanature");
        }
    }

    public static final class RustNatureOnly implements M3EclipseBuilderProvider {
        @Override public String id() { return "test.rust"; }
        @Override public Set<String> builderIds() { return Set.of(); }
        @Override public Set<String> natureIds() {
            return Set.of("vendor.rustdt.nature");
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-eclipse-owner-contract-");
        try {
            Files.writeString(root.resolve(".project"), """
                    <projectDescription>
                      <name>Mixed&amp;Future</name>
                      <buildSpec>
                        <buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand>
                        <buildCommand><name>org.eclipse.cdt.managedbuilder.core.genmakebuilder</name></buildCommand>
                        <buildCommand><name>vendor.future.builder</name></buildCommand>
                      </buildSpec>
                      <natures>
                        <nature>org.eclipse.jdt.core.javanature</nature>
                        <nature>org.eclipse.cdt.core.cnature</nature>
                        <nature>vendor.rustdt.nature</nature>
                        <nature>vendor.future.nature</nature>
                      </natures>
                    </projectDescription>
                    """);
            Path settings = Files.createDirectory(root.resolve(".settings"));
            Files.writeString(settings.resolve("vendor.rustdt.prefs"), "channel=stable\n");
            Files.writeString(settings.resolve("org.eclipse.lsp4e.prefs"), "server=owner\n");

            var snapshot = EclipseProjectModel.inspect(root);
            require(snapshot.descriptorSha256().containsKey(".settings/vendor.rustdt.prefs"),
                    "unknown Rust plugin metadata fingerprint");
            require(snapshot.descriptorSha256().containsKey(".settings/org.eclipse.lsp4e.prefs"),
                    "unknown LSP language settings retained");
            var registered = M3EclipseBuilderRegistry.assess(snapshot, List.of(new JdtOwner(), new RustNatureOnly()));
            require(registered.builders().size() == 3, "every original builder preserved");
            require(registered.builders().get(0).providerId().equals("test.jdt"), "exact JDT builder binding");
            require(registered.builders().get(1).providerId() == null, "CDT owner is not assumed installed");
            require(registered.builders().get(2).providerId() == null, "future plugin stays unbound");
            require(registered.natures().size() == 4, "original Eclipse nature order retained");
            require(registered.natures().get(2).providerIds().equals(List.of("test.rust")),
                    "nature-only Rust provider is represented without claiming a builder");
            require(registered.natures().get(1).providerIds().isEmpty(),
                    "CDT remains an unbound nature until its exact adapter is installed");
            require(registered.unboundNatures().equals(List.of(
                    "org.eclipse.cdt.core.cnature", "vendor.future.nature")), "nature-only Rust recognized");
            require(registered.providers().equals(List.of("test.jdt", "test.rust")), "sorted installed adapters");
            require(registered.toJson().startsWith(
                    "{\"schema\":\"m3-eclipse-builder-owners-v1\",\"project\":\"Mixed&Future\""),
                    "stable JSON schema and project identity");
            require(registered.toJson().equals(M3EclipseBuilderRegistry.assess(
                    snapshot, List.of(new RustNatureOnly(), new JdtOwner())).toJson()),
                    "registered adapter iteration order cannot change report");
            var empty = M3EclipseBuilderRegistry.assess(snapshot, List.of());
            require(empty.builders().stream().allMatch(b -> b.providerId() == null),
                    "the mere presence of a known nature never claims a compiler");
            require(empty.unboundNatures().size() == 4, "all unrecognized natures explicitly visible");

            // Exact Eclipse builder collisions fail instead of picking a random implementation.
            rejects(() -> M3EclipseBuilderRegistry.assess(snapshot,
                    List.of(new JdtOwner(), new JdtOwner())), "repeated adapter IDs");
            rejects(() -> M3EclipseBuilderRegistry.assess(snapshot,
                    List.of(new JdtOwner(), new M3EclipseBuilderProvider() {
                        @Override public String id() { return "second"; }
                        @Override public Set<String> builderIds() {
                            return Set.of("org.eclipse.jdt.core.javabuilder");
                        }
                        @Override public Set<String> natureIds() { return Set.of(); }
                    })), "conflicting exact builder ownership");
            rejects(() -> M3EclipseBuilderRegistry.assess(snapshot,
                    List.of(new M3EclipseBuilderProvider() {
                        @Override public String id() { return "future"; }
                        @Override public Set<String> builderIds() { return Set.of("bad/name"); }
                        @Override public Set<String> natureIds() { return Set.of(); }
                    })), "invalid builder extension ID");
            rejects(() -> M3EclipseBuilderRegistry.assess(snapshot,
                    List.of(new M3EclipseBuilderProvider() {
                        @Override public String id() { return "old"; }
                        @Override public int apiVersion() { return 999; }
                        @Override public Set<String> builderIds() { return Set.of(); }
                        @Override public Set<String> natureIds() { return Set.of("vendor.unknown"); }
                    })), "API version mismatch");

            String originalMetadataHash = snapshot.descriptorSha256().get(".settings/vendor.rustdt.prefs");
            Files.writeString(settings.resolve("vendor.rustdt.prefs"), "channel=beta\\n");
            require(!originalMetadataHash.equals(EclipseProjectModel.inspect(root).descriptorSha256()
                    .get(".settings/vendor.rustdt.prefs")),
                    "arbitrary language metadata changes invalidate the corresponding digest");
            Path external = Files.writeString(root.resolve("external.prefs"), "do-not-follow");
            Path shortcut = settings.resolve("vendor.external.prefs");
            try {
                Files.createSymbolicLink(shortcut, external);
                rejects(() -> EclipseProjectModel.inspect(root), "symlinked vendor settings");
                Files.delete(shortcut);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Platforms without symlink creation still enforce NOFOLLOW_LINKS.
            }
            // The installed test ServiceLoader descriptors must work in the real CLI lane.
            var discovered = M3EclipseBuilderRegistry.inspect(root);
            require(discovered.builders().equals(registered.builders())
                    && discovered.natures().equals(registered.natures()),
                    "portable installed provider discovery");
            System.out.println("PASS: Eclipse JDT/CDT/Rust/future provider-neutral ownership contracts");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @FunctionalInterface private interface Checked { void invoke() throws Exception; }

    private static void rejects(Checked attempt, String reason) throws Exception {
        try {
            attempt.invoke();
            throw new AssertionError("expected refusal: " + reason);
        } catch (IOException expected) {
            // Unknown/duplicate/malformed provider declarations always fail closed.
        }
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
