/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.ide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.M3ExternalBuildService;
import org.apache.maven.api.services.M3ExternalBuildService.Assessment;
import org.apache.maven.api.services.M3ExternalBuildService.Generation;
import org.apache.maven.api.services.M3ExternalBuildWire;

/**
 * Explicit Maven 4 bridge to an original IDE source-owner change packet.
 * This Mojo does not compile, scan sources, change files, or skip Maven goals.
 */
@org.apache.maven.api.plugin.annotations.Mojo(name = "ide-assess", projectRequired = true)
public final class IdeAssessMojo implements Mojo {
    @Inject
    private Session session;

    @Inject
    private Log log;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDirectory;

    @Parameter(property = "m3.ide.packet", required = true)
    private File packetFile;

    @Parameter(property = "m3.ide.generation", required = true)
    private String generation;

    @Parameter(property = "m3.ide.modelSha256", required = true)
    private String modelSha256;

    @Parameter(property = "m3.ide.toolchainSha256", required = true)
    private String toolchainSha256;

    @Parameter(property = "m3.ide.dependenciesSha256", required = true)
    private String dependenciesSha256;

    @Parameter(property = "m3.ide.sourceRootsSha256", required = true)
    private String sourceRootsSha256;

    @Parameter(property = "m3.ide.contextComplete", defaultValue = "false")
    private boolean contextComplete;

    @Parameter(property = "m3.ide.previousGeneration")
    private String previousGeneration;

    @Parameter(property = "m3.ide.previousModelSha256")
    private String previousModelSha256;

    @Parameter(property = "m3.ide.previousToolchainSha256")
    private String previousToolchainSha256;

    @Parameter(property = "m3.ide.previousDependenciesSha256")
    private String previousDependenciesSha256;

    @Parameter(property = "m3.ide.previousSourceRootsSha256")
    private String previousSourceRootsSha256;

    @Parameter(property = "m3.ide.previousComplete", defaultValue = "false")
    private boolean previousComplete;

    @Override
    public void execute() throws Exception {
        Objects.requireNonNull(session, "Maven 4 session");
        Objects.requireNonNull(log, "Maven 4 logger");
        M3ExternalBuildService service = session.getService(M3ExternalBuildService.class);
        Assessment result = assess(service, loadPacket(projectDirectory.toPath(), packetFile.toPath()),
                generation, contextComplete, currentHashes(), previousGeneration,
                previousComplete, previousHashes());
        log.info("M3 IDE: " + result.disposition() + " reasons=" + result.reasons()
                + " changedFiles=" + result.size() + " allowsMavenGoalSkip="
                + result.authorizesMavenGoalSkipping());
    }

    /** Explicit caller-supplied metadata only. No fingerprints are invented. */
    static Assessment assess(M3ExternalBuildService service, byte[] bytes, String currentGeneration,
            boolean contextComplete, String[] currentHashes, String previousGeneration,
            boolean previousComplete, String[] previousHashes) throws IOException {
        Objects.requireNonNull(service, "service");
        M3ExternalBuildWire.Envelope envelope = M3ExternalBuildWire.decode(bytes);
        Generation current = new Generation(envelope.providerId(), envelope.projectId(),
                positiveGeneration(currentGeneration), checked(currentHashes, "current context")[0],
                currentHashes[1], currentHashes[2], currentHashes[3], contextComplete);
        Generation previous = null;
        if (previousGeneration != null && !previousGeneration.isBlank()) {
            String[] hashes = checked(previousHashes, "previous context");
            previous = new Generation(envelope.providerId(), envelope.projectId(),
                    positiveGeneration(previousGeneration), hashes[0], hashes[1], hashes[2], hashes[3],
                    previousComplete);
            if (!previousComplete) {
                // An incomplete host baseline is never usable, even if an older
                // Maven implementation only validates current completeness.
                previous = null;
            }
        } else if (previousComplete || anyPresent(previousHashes)) {
            throw new IllegalArgumentException("previous context requires an explicit generation");
        }
        // This is Maven's original strict packet binding and assessment service.
        return service.assess(M3ExternalBuildWire.bind(envelope, previous, current));
    }

    private static String[] checked(String[] hashes, String kind) {
        Objects.requireNonNull(hashes, kind);
        if (hashes.length != 4) {
            throw new IllegalArgumentException(kind + " requires four source-owned digests");
        }
        for (String sha : hashes) {
            if (sha == null) {
                throw new IllegalArgumentException(kind + " has missing digest");
            }
        }
        return hashes;
    }

    private static boolean anyPresent(String[] entries) {
        if (entries != null) {
            for (String value : entries) {
                if (value != null && !value.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long positiveGeneration(String value) {
        Objects.requireNonNull(value, "generation");
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid positive generation", invalid);
        }
        throw new IllegalArgumentException("generation must be positive");
    }

    private String[] currentHashes() {
        return new String[] {modelSha256, toolchainSha256, dependenciesSha256, sourceRootsSha256};
    }

    private String[] previousHashes() {
        return new String[] {previousModelSha256, previousToolchainSha256,
                previousDependenciesSha256, previousSourceRootsSha256};
    }

    /** Confined, read-only, no-follow operation. The caller owns the packet bytes. */
    static byte[] loadPacket(Path projectDirectory, Path packetFile) throws IOException {
        Path root = Objects.requireNonNull(projectDirectory, "projectDirectory").toRealPath();
        Path requested = Objects.requireNonNull(packetFile, "packetFile");
        Path packet = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
        if (!packet.startsWith(root) || packet.equals(root)) {
            throw new IOException("IDE packet must be inside the project directory");
        }
        Path relative = root.relativize(packet);
        Path cursor = root;
        for (Path segment : relative) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("IDE packet cannot traverse symbolic links");
            }
        }
        BasicFileAttributes attr = Files.readAttributes(packet, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attr.isRegularFile() || attr.size() > M3ExternalBuildWire.MAX_BYTES) {
            throw new IOException("IDE packet must be a bounded regular file");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(packet,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) attr.size());
            ByteBuffer buffer = ByteBuffer.allocate(16_384);
            int read;
            while ((read = channel.read(buffer)) != -1) {
                if (read == 0) {
                    throw new IOException("IDE packet read made no progress");
                }
                buffer.flip();
                int count = buffer.remaining();
                if ((long) bytes.size() + count > M3ExternalBuildWire.MAX_BYTES) {
                    throw new IOException("IDE packet byte budget exceeded");
                }
                bytes.write(buffer.array(), 0, count);
                buffer.clear();
            }
            if (bytes.size() != attr.size()) {
                throw new IOException("IDE packet changed length during read");
            }
            return bytes.toByteArray();
        }
    }
}
