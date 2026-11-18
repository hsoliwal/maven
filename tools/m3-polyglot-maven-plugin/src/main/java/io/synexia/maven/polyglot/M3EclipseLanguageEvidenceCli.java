/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;

/**
 * Additive entry point for installed Eclipse language evidence providers.
 * Kept separate from the versioned JDT/CDT/Cargo/Tycho CLI modes to avoid
 * replacing or rebinding existing language tool/IDE contracts.
 */
public final class M3EclipseLanguageEvidenceCli {
    private M3EclipseLanguageEvidenceCli() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 3) {
            throw new IllegalArgumentException("usage: M3EclipseLanguageEvidenceCli "
                    + "<installed-provider-id> <source-owner-packet> <Eclipse-handoff-packet>");
        }
        System.out.println(M3EclipseLanguageEvidenceService.inspect(
                args[0], Path.of(args[1]), Path.of(args[2])).toJson());
    }
}
