/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.ide;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.M3ExternalBuildService.Change;
import org.apache.maven.api.services.M3ExternalBuildService.ChangeKind;
import org.apache.maven.api.services.M3ExternalBuildService.Disposition;
import org.apache.maven.api.services.M3ExternalBuildService.Reason;
import org.apache.maven.api.services.M3ExternalBuildWire;
import org.apache.maven.impl.DefaultM3ExternalBuildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol and filesystem refusal tests using the original Maven classifier. */
final class IdeAssessMojoTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String[] HASHES = {A, A, A, A};
    private final DefaultM3ExternalBuildService service = new DefaultM3ExternalBuildService();

    @TempDir
    Path directory;

    @Test
    void completeHostContextAllowsAdvisoryChangeButNeverSkipsGoal() throws Exception {
        var assessment = IdeAssessMojo.assess(service, packet("INCREMENTAL", true, "src/A.java"),
                "2", true, HASHES, "1", true, HASHES);
        assertEquals(Disposition.HOST_DELTA_AVAILABLE, assessment.disposition());
        assertEquals(1, assessment.size());
        assertFalse(assessment.authorizesMavenGoalSkipping());
    }

    @Test
    void missingOrIncompleteBaselinesAlwaysReconcile() throws Exception {
        byte[] bytes = packet("AUTO", true, "src/A.java");
        var first = IdeAssessMojo.assess(service, bytes, "2", true, HASHES, null, false, null);
        assertEquals(Disposition.MAVEN_RECONCILIATION, first.disposition());
        assertTrue(first.reasons().contains(Reason.INITIAL_BASELINE_MISSING));
        var incomplete = IdeAssessMojo.assess(service, bytes, "2", false, HASHES, "1", true, HASHES);
        assertTrue(incomplete.reasons().contains(Reason.INCOMPLETE_DELTA));
        var previousIncomplete = IdeAssessMojo.assess(service, bytes, "2", true, HASHES, "1", false, HASHES);
        assertEquals(Disposition.MAVEN_RECONCILIATION, previousIncomplete.disposition());
    }

    @Test
    void changedModelAndReorderedOrMalformedPacketCannotPass() throws Exception {
        byte[] bytes = packet("AUTO", true, "src/A.java");
        var drift = IdeAssessMojo.assess(service, bytes, "2", true, new String[] {B, A, A, A},
                "1", true, HASHES);
        assertEquals(Disposition.MAVEN_RECONCILIATION, drift.disposition());
        assertTrue(drift.reasons().contains(Reason.MODEL_OR_TOOLCHAIN_CHANGED));
        assertThrows(IOException.class, () -> IdeAssessMojo.assess(service,
                "not a packet".getBytes(StandardCharsets.UTF_8), "2", true, HASHES, "1", true, HASHES));
        assertThrows(IllegalArgumentException.class, () -> IdeAssessMojo.assess(service,
                bytes, "0", true, HASHES, "1", true, HASHES));
        assertThrows(IllegalArgumentException.class, () -> IdeAssessMojo.assess(service,
                bytes, "2", true, HASHES, null, true, HASHES));
        assertThrows(IllegalArgumentException.class, () -> IdeAssessMojo.assess(service,
                bytes, "2", true, new String[] {A, A, A}, "1", true, HASHES));
    }

    @Test
    void confinedFileReaderPreservesBytesAndRefusesOutsidePath() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path packet = project.resolve("packet.ide");
        byte[] encoded = packet("AUTO", false, "src/A.java");
        Files.write(packet, encoded);
        assertArrayEquals(encoded, IdeAssessMojo.loadPacket(project, Path.of("packet.ide")));
        assertArrayEquals(encoded, IdeAssessMojo.loadPacket(project, packet));
        assertThrows(IOException.class, () -> IdeAssessMojo.loadPacket(project, directory.resolve("foreign.ide")));
        assertThrows(IOException.class, () -> IdeAssessMojo.loadPacket(project, Path.of("../foreign.ide")));
        assertThrows(IOException.class, () -> IdeAssessMojo.loadPacket(project, project));
    }

    @Test
    void inputSymlinksRefusedWhereSupported() throws Exception {
        Path project = Files.createDirectory(directory.resolve("workspace"));
        Path packet = Files.writeString(project.resolve("actual.ide"), "safe");
        Path shortcut = project.resolve("shortcut.ide");
        try {
            Files.createSymbolicLink(shortcut, packet);
        } catch (UnsupportedOperationException | IOException | SecurityException noSymlinks) {
            return; // OS may deny symlinks; core path tests still run.
        }
        assertThrows(IOException.class, () -> IdeAssessMojo.loadPacket(project, shortcut));
    }

    @Test
    void actualMojoEntrypointReportsAdvisoryWithoutExecutingOtherGoals() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        byte[] original = packet("INCREMENTAL", true, "src/A.java");
        Path packet = Files.write(project.resolve("input.ide"), original);
        AtomicReference<String> message = new AtomicReference<>();
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, args) -> {
                    if ("getService".equals(method.getName())) {
                        return service;
                    }
                    throw new AssertionError("unexpected session method " + method.getName());
                });
        Log logger = (Log) Proxy.newProxyInstance(Log.class.getClassLoader(),
                new Class<?>[] {Log.class}, (proxy, method, args) -> {
                    if ("info".equals(method.getName()) && args.length == 1) {
                        message.set(args[0].toString());
                        return null;
                    }
                    throw new AssertionError("unexpected logger method " + method.getName());
                });
        IdeAssessMojo mojo = new IdeAssessMojo();
        field(mojo, "session", session);
        field(mojo, "log", logger);
        field(mojo, "projectDirectory", project.toFile());
        field(mojo, "packetFile", packet.toFile());
        field(mojo, "generation", "2");
        field(mojo, "modelSha256", A);
        field(mojo, "toolchainSha256", A);
        field(mojo, "dependenciesSha256", A);
        field(mojo, "sourceRootsSha256", A);
        field(mojo, "contextComplete", true);
        field(mojo, "previousGeneration", "1");
        field(mojo, "previousModelSha256", A);
        field(mojo, "previousToolchainSha256", A);
        field(mojo, "previousDependenciesSha256", A);
        field(mojo, "previousSourceRootsSha256", A);
        field(mojo, "previousComplete", true);
        mojo.execute();
        assertTrue(message.get().contains("HOST_DELTA_AVAILABLE"));
        assertTrue(message.get().contains("allowsMavenGoalSkip=false"));
        assertArrayEquals(original, Files.readAllBytes(packet));
    }

    private static void field(IdeAssessMojo mojo, String name, Object value) throws Exception {
        Field field = IdeAssessMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static byte[] packet(String kind, boolean complete, String path) {
        return M3ExternalBuildWire.encode(new M3ExternalBuildWire.Envelope(
                "eclipse.m2e", "project", kind, complete,
                List.of(new Change(path, ChangeKind.MODIFIED, null))));
    }
}
