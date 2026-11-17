/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

/**
 * What a planned provider task does to the workspace. Only declared outputs may be
 * written by a {@link #READS_SOURCES} task; a {@link #WRITES_SOURCES} task (such as an
 * approved patch apply or rollback) is refused unless the caller separately approves
 * source mutation.
 */
public enum M3ToolEffect {
    READS_SOURCES,
    WRITES_SOURCES
}
