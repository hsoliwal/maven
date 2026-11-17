/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;

/** Portable read-only Eclipse descriptor entry point; requires neither Maven nor Eclipse runtime. */
public final class EclipseModelCli {
    private EclipseModelCli() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("usage: EclipseModelCli <existing-eclipse-project-directory>");
        }
        System.out.println(EclipseProjectModel.inspect(Path.of(args[0])).toJson());
    }
}
