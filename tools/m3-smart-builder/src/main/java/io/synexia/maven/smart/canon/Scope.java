// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Mutation scope (M3-SCALE v3.0 sections 3, 4 and 7). Atom: one path that differs from the base. Pattern: guard;
 * a delta is allowed inside the targets only, never under excluded or disabled paths, and a build script only when
 * it is listed by name in {@code targets.files}.
 */
final class Scope {
    static final Pattern BUILD_SCRIPT = Pattern.compile("(^|/)(pom\\.xml|Makefile|[^/]*\\.gmk|[^/]*\\.gradle"
            + "|[^/]*\\.gradle\\.kts|configure|configure\\.ac|[^/]*\\.m4|mvnw|mvnw\\.cmd|m3-build\\.sh"
            + "|m3-build\\.ps1)$|^make/.*\\.sh$");

    private Scope() {
    }

    static void check(Packet packet, String path, List<String> violations) {
        if (packet.excluded.stream().anyMatch(d -> Packet.under(path, d))) violations.add("EXCLUDED_PATH " + path);
        else if (packet.disabled.stream().anyMatch(d -> Packet.under(path, d))) violations.add("DISABLED_PATH " + path);
        else if (!packet.inScope(path)) violations.add("OUT_OF_SCOPE " + path);
        else if (BUILD_SCRIPT.matcher(path).find() && !packet.targetFiles.contains(path)) {
            violations.add("BUILD_SCRIPT_LOCKED " + path);
        }
    }
}
