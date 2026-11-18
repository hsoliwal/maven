/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in Java compiler adapter. Uses the selected JDK/M3JDK's original
 * jdk.compiler module, with all compiler arguments including -Xplugin:M3AST
 * forwarded unchanged. It does not implement a second Java compiler.
 */
public final class JdkCompilerProvider implements M3ToolProvider {
    @Override
    public String id() {
        return "jdk";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.COMPILER);
    }

    @Override
    public Set<String> languages() {
        return Set.of("java");
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        if (capability != M3ToolCapability.COMPILER || !"java".equals(language)) {
            throw new IOException("JDK provider only supports Java compilation");
        }
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        var argv = new ArrayList<String>(task.arguments().size() + 3);
        argv.add(java.toString());
        argv.add("-m");
        argv.add("jdk.compiler/com.sun.tools.javac.Main");
        argv.addAll(task.arguments());
        return List.copyOf(argv);
    }

    /** Qualify only the bounded, explicitly configured javac subset documented by its owner. */
    @Override
    public Optional<M3ToolReuseInputs> reuseInputs(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        if (capability != M3ToolCapability.COMPILER || !"java".equals(language)) {
            return Optional.empty();
        }
        return JdkReuseInputs.inspect(task, workspace);
    }
}
