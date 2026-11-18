/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Immutable provider assertion consumed only by explicit provider-reuse mode.
 * Additional inputs are absolute, normalized files or complete directory trees;
 * they supplement rather than replace the task's declared inputs. The runner
 * content-hashes them on each verification and refuses symlinks/special files.
 * A listed absent input or an incomplete dependency closure cannot grant reuse.
 *
 * <p>The provider asserts that outputs depend only on the captured file contents,
 * path structure and execution context. Fingerprints do not bind timestamps or
 * establish that a tool ignores undeclared network/process-global state. A
 * metadata-dependent invocation, such as timestamp-based source/class selection,
 * must remain unqualified unless that behavior is explicitly removed. The
 * provider declares all transitive library, toolchain and configuration inputs;
 * automatic implementation-code binding does not establish that closure.</p>
 *
 * <p>Clean outputs are exact project-relative declared output directories whose
 * contents the provider owns in full. The runner revokes earlier receipts before
 * replacing those directories for a new execution. Relocatability is a separate
 * assertion; false still permits verified reuse in the original workspace.</p>
 *
 * @param contractId versioned identity of this provider's qualification rules
 * @param additionalInputs complete additional input files and directory trees
 * @param cleanOutputs declared output directories to replace before execution
 * @param relocatable whether qualified outputs can be restored in another workspace
 */
public record M3ToolReuseInputs(String contractId, List<Path> additionalInputs,
        List<String> cleanOutputs, boolean relocatable) {
    private static final int MAX_PATHS = 256;

    public M3ToolReuseInputs {
        if (contractId == null || !contractId.matches("[a-z][a-z0-9.:-]{0,127}")) {
            throw new IllegalArgumentException("invalid versioned M3 provider reuse contract identity");
        }
        Objects.requireNonNull(additionalInputs, "additionalInputs");
        Objects.requireNonNull(cleanOutputs, "cleanOutputs");
        if (additionalInputs.size() > MAX_PATHS || cleanOutputs.size() > MAX_PATHS) {
            throw new IllegalArgumentException("M3 provider reuse input/output path budget exceeded");
        }
        var paths = new TreeSet<Path>();
        for (Path path : additionalInputs) {
            if (path == null || !path.isAbsolute() || !path.equals(path.normalize())
                    || path.getNameCount() == 0 || path.toString().length() > 4096
                    || path.toString().chars().anyMatch(Character::isISOControl)
                    || !paths.add(path)) {
                throw new IllegalArgumentException("invalid or duplicate absolute M3 provider reuse input");
            }
        }
        additionalInputs = List.copyOf(paths);
        var outputs = new TreeSet<String>();
        for (String output : cleanOutputs) {
            try {
                TaskManifest.relativePath(output, false);
            } catch (IOException invalid) {
                throw new IllegalArgumentException("invalid M3 provider clean output", invalid);
            }
            if (!outputs.add(output)) {
                throw new IllegalArgumentException("duplicate M3 provider clean output");
            }
        }
        var ordered = new ArrayList<>(outputs);
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                if (ordered.get(j).startsWith(ordered.get(i) + "/")) {
                    throw new IllegalArgumentException("overlapping M3 provider clean outputs");
                }
            }
        }
        cleanOutputs = List.copyOf(ordered);
    }
}
