/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code @m3:cargo:interpreter:rust}: cargo test with surefire reports. The task's arguments are cargo's and the
 * first must be {@code test}. The planned command is the {@code libtest} format of {@link SurefireReportMain}, the
 * same as {@code @m3:surefire:interpreter:libtest}: a JVM of the running JDK runs the original cargo
 * ({@link CargoTestReport}) and writes {@code target/surefire-reports/TEST-*.xml} under the workspace (cargo's own
 * target folder, next to target/debug). The v1 SPI has no test kind, so the provider registers as an interpreter:
 * it runs the test programs cargo builds.
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
        List<String> args = new ArrayList<>(List.of(workspace.resolve(REPORTS).toString(), "cargo"));
        args.addAll(task.arguments());
        return SurefireReportProvider.launch(SurefireReportMain.LIBTEST, args);
    }
}
