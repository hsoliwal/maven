// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import io.synexia.maven.smart.canon.CanonGate;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven entry for the hard canon gate ({@code mvn m3:canon -Dm3.canon.packet=... -Dm3.canon.runDir=...}). The gate
 * runs the packet's stages in the fixed order diff, atom_pattern, lint, compile, test, runtime and fails the build on
 * any packet, scope, atomization, verification or output-contract violation; there is no warning mode.
 */
@Mojo(name = "canon", threadSafe = true, requiresProject = false)
public final class CanonMojo extends AbstractMojo {
    @Parameter(property = "m3.canon.packet", required = true)
    private File packet;
    @Parameter(property = "m3.canon.runDir", required = true)
    private File runDir;
    @Parameter(property = "m3.canon.root", defaultValue = "${session.executionRootDirectory}", required = true)
    private File root;

    @Override public void execute() throws MojoExecutionException {
        if (packet == null || runDir == null || root == null)
            throw new MojoExecutionException("canon requires m3.canon.packet, m3.canon.runDir and m3.canon.root");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            status = CanonGate.execute(new String[] {"run", packet.toString(), runDir.toString(), root.toString()}, out);
        }
        bytes.toString(StandardCharsets.UTF_8).lines().forEach(getLog()::info);
        if (status != 0)
            throw new MojoExecutionException("canon gate failed with status " + status + "; see " + new File(runDir, "FINAL_REPORT.md"));
    }
}
