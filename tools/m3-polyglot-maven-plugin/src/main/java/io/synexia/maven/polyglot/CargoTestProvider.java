/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code @m3:cargo:interpreter:rust}: cargo test with surefire reports. The task's arguments are cargo's and the
 * first must be {@code test}. The planned command starts {@link CargoTestReport} in a JVM of the running JDK; it runs
 * the original cargo and writes {@code target/surefire-reports/TEST-*.xml} under the workspace (cargo's own target
 * folder, next to target/debug). The v1 SPI has no test kind, so the provider registers as an interpreter: it runs
 * the test programs cargo builds.
 */
public final class CargoTestProvider implements M3ToolProvider {
    /** The manifest tool name. */
    public static final String TOOL = "@m3:cargo:interpreter:rust";
    /** The reports folder, relative to the workspace. */
    public static final String REPORTS = "target/surefire-reports";

    @Override
    public String id() {
        return "cargo";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.INTERPRETER);
    }

    @Override
    public Set<String> languages() {
        return Set.of("rust");
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        if (capability != M3ToolCapability.INTERPRETER || !"rust".equals(language)) {
            throw new IOException("cargo provider only runs Rust tests");
        }
        if (task.arguments().isEmpty() || !"test".equals(task.arguments().get(0))) {
            throw new IOException("cargo provider runs cargo test: the first argument must be test");
        }
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        List<String> argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classes(), CargoTestReport.class.getName(), workspace.resolve(REPORTS).toString(), "cargo"));
        argv.addAll(task.arguments());
        return List.copyOf(argv);
    }

    /** The jar or folder holding this plugin's classes, for the separate JVM. */
    private static String classes() throws IOException {
        CodeSource source = CargoTestReport.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("cannot locate the polyglot plugin classes for the cargo test runner");
        }
        try {
            return Path.of(source.getLocation().toURI()).toString();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("cannot locate the polyglot plugin classes for the cargo test runner", failure);
        }
    }
}
