/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Universal, read-only Eclipse language source-owner evidence review.
 * Supports arbitrary future Eclipse language plugins through installed,
 * SHA-pinned Java 17 provider JARs without re-editing Maven/Gradle core.
 *
 * <p>Binding is conservative: exact Eclipse builder/nature declaration,
 * exact project/configuration/invocation and independent packet SHA-256
 * must agree. A match is never compiler, package, ABI or cache admission.
 * The selected provider JAR is trusted code, not a sandbox.</p>
 */
public final class M3EclipseLanguageEvidenceService {
    public static final int MAX_PACKET_BYTES = 1 * 1024 * 1024;
    public static final int MAX_INSTALLED_PROVIDERS = 128;
    public static final int MAX_OWNER_EXTENSION_IDS = 4096;
    private M3EclipseLanguageEvidenceService() { }

    public record Report(String providerId, String schema, String project,
            String invocation, String selectedConfiguration,
            int observedCount, String observedSha256, String originalPacketSha256) {
        public Report {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(selectedConfiguration, "selectedConfiguration");
            Objects.requireNonNull(observedSha256, "observedSha256");
            Objects.requireNonNull(originalPacketSha256, "originalPacketSha256");
        }

        public String toJson() {
            StringBuilder result = new StringBuilder("{\"schema\":\"m3-eclipse-language-facts-v1\"");
            field(result, "providerId", providerId);
            field(result, "sourceSchema", schema);
            field(result, "project", project);
            field(result, "invocation", invocation);
            field(result, "selectedConfiguration", selectedConfiguration);
            result.append(",\"observedCount\":").append(observedCount);
            field(result, "observedSha256", observedSha256);
            field(result, "originalPacketSha256", originalPacketSha256);
            return result.append(",\"authorizesBuildSkipping\":false}").toString();
        }
        public boolean authorizesBuildSkipping() { return false; }
    }

    /** Original host classpath ServiceLoader provider and Eclipse owner. */
    public static Report inspect(String providerId, Path originalPacket, Path eclipseHandoff)
            throws IOException {
        Objects.requireNonNull(eclipseHandoff, "Eclipse owner handoff");
        return inspect(providerId, originalPacket,
                M3EclipseBuildHandoffWire.read(eclipseHandoff),
                ServiceLoader.load(M3EclipseLanguageEvidenceProvider.class,
                        M3EclipseLanguageEvidenceService.class.getClassLoader()));
    }

    /** Package-visible deterministic test lane with a fixed installed provider set. */
    static Report inspect(String providerId, Path originalPacket,
            M3EclipseBuildHandoffWire.Snapshot eclipse,
            Iterable<M3EclipseLanguageEvidenceProvider> installed) throws IOException {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(originalPacket, "original language packet");
        Objects.requireNonNull(eclipse, "original Eclipse owner");
        Objects.requireNonNull(installed, "installed providers");

        M3EclipseLanguageEvidenceProvider chosen = null;
        Set<String> chosenBuilders = Set.of();
        Set<String> chosenNatures = Set.of();
        Set<String> providerIds = new HashSet<>();
        Map<String, String> buildersOwnedBy = new HashMap<>();
        int discovered = 0;
        try {
            for (M3EclipseLanguageEvidenceProvider provider : installed) {
                if (++discovered > MAX_INSTALLED_PROVIDERS) {
                    throw new IOException("Eclipse language evidence provider count exceeds bound");
                }
                if (provider == null
                        || provider.apiVersion() != M3EclipseLanguageEvidenceProvider.API_VERSION) {
                    throw new IOException("unsupported original Eclipse language evidence SPI");
                }
                String id = provider.id();
                if (id == null || !id.matches("[a-z][a-z0-9._-]{0,63}")
                        || !providerIds.add(id)) {
                    throw new IOException("duplicate/invalid installed language evidence provider ID: " + id);
                }
                Set<String> builders = Objects.requireNonNull(provider.builderIds(), "builder IDs");
                Set<String> natures = Objects.requireNonNull(provider.natureIds(), "nature IDs");
                if ((builders.isEmpty() && natures.isEmpty())
                        || builders.size() > MAX_OWNER_EXTENSION_IDS
                        || natures.size() > MAX_OWNER_EXTENSION_IDS) {
                    throw new IOException("Eclipse evidence provider has invalid builder/nature bounds: " + id);
                }
                for (String builder : builders) {
                    checkExtension(builder);
                    String previous = buildersOwnedBy.putIfAbsent(builder, id);
                    if (previous != null) {
                        throw new IOException("ambiguous Eclipse evidence builder owner: " + builder
                                + " (" + previous + ", " + id + ")");
                    }
                }
                // Nature-only extensions may intentionally share a nature
                // (e.g. multiple language-server observers). Unlike builder
                // IDs they do not claim exclusive incremental-build ownership.
                for (String nature : natures) checkExtension(nature);
                if (providerId.equals(id)) {
                    chosen = provider;
                    chosenBuilders = Set.copyOf(builders);
                    chosenNatures = Set.copyOf(natures);
                }
            }
        } catch (RuntimeException | ServiceConfigurationError problem) {
            throw new IOException("installed language provider could not be verified", problem);
        }
        if (chosen == null) {
            throw new IOException("no approved original Eclipse language provider: " + providerId);
        }

        boolean declaredBuilder = chosenBuilders.stream().anyMatch(eclipse.builders()::contains);
        boolean declaredNature = chosenNatures.stream().anyMatch(eclipse.natures()::contains);
        if (!declaredBuilder && !declaredNature) {
            throw new IOException("language provider is not bound to the original Eclipse builders/natures");
        }

        byte[] packet = readNoLinks(originalPacket);
        String packetSha256 = sha256(packet);
        final M3EclipseLanguageEvidenceProvider.Evidence nativeEvidence;
        try {
            nativeEvidence = Objects.requireNonNull(chosen.decode(packet.clone()),
                    "source-owner language evidence");
        } catch (RuntimeException | ServiceConfigurationError failure) {
            throw new IOException("original language provider rejected its source evidence", failure);
        }
        if (!nativeEvidence.project().equals(eclipse.project())
                || !nativeEvidence.invocation().equals(eclipse.invocation())
                || !nativeEvidence.selectedConfiguration().equals(eclipse.selectedConfiguration())) {
            throw new IOException("foreign language evidence or original Eclipse build generation");
        }
        return new Report(providerId, nativeEvidence.schema(),
                nativeEvidence.project(), nativeEvidence.invocation(),
                nativeEvidence.selectedConfiguration(), nativeEvidence.observedCount(),
                nativeEvidence.observedSha256(), packetSha256);
    }

    private static byte[] readNoLinks(Path packet) throws IOException {
        Path absolute = packet.toAbsolutePath().normalize();
        for (Path parent = absolute; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("source-owned language packet path contains symlinks");
            }
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
                || Files.size(absolute) > MAX_PACKET_BYTES) {
            throw new IOException("original language packet must be a regular file under 1 MiB");
        }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(absolute,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] bytes = in.readNBytes(MAX_PACKET_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) {
                throw new IOException("original language packet byte budget exceeded");
            }
            return bytes;
        }
    }

    private static void checkExtension(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_.-]{0,254}")) {
            throw new IOException("invalid Eclipse builder/nature extension: " + id);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(',');
        quote(out, name);
        out.append(':');
        quote(out, value);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit((c >>> shift) & 15, 16));
                        }
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
