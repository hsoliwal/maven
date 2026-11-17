/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.util.Objects;

/** A declared task output and the format it holds; the path must be one of the task's outputs. */
public record M3Report(String path, M3ReportFormat format) {
    public M3Report {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
    }
}
