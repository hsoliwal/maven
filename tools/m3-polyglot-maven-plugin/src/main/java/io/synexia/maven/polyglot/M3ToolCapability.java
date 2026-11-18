/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;

/** Additive, versioned operations supported by M3 polyglot tool providers. */
public enum M3ToolCapability {
    COMPILER("compiler"),
    INTERPRETER("interpreter"),
    TRANSPILER("transpiler"),
    ATOMIZER("atomizer"),
    PATTERNIZER("patternizer"),
    LINKER("linker"),
    OPTIMIZER("optimizer"),
    ASSEMBLER("assembler");

    private final String slug;

    M3ToolCapability(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static M3ToolCapability parse(String value) throws IOException {
        for (M3ToolCapability capability : values()) {
            if (capability.slug.equals(value)) {
                return capability;
            }
        }
        throw new IOException("unknown M3 provider capability: " + value);
    }
}
