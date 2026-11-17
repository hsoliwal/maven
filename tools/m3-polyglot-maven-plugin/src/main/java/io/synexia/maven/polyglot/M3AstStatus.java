/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

/**
 * Where a provider's view of the source comes from, for the one-AST rule: analysis
 * belongs on the compiler AST inside the build; anything else is a second parse and is
 * reported as such. The default for an undeclared provider is {@link #SEPARATE_PARSE}.
 */
public enum M3AstStatus {
    /** The compiler itself; owns the AST. */
    COMPILER,
    /** A plug-in running on the compiler's AST during the same compilation (javac Plugin, TaskListener). */
    COMPILER_PLUGIN,
    /** The compiler's parser in a separate invocation: same grammar, second parse. */
    COMPILER_FRONT_END,
    /** The tool's own parser: second parse, external adapter only. */
    SEPARATE_PARSE,
    /** A language server's own parse, reached through LSP: second parse. */
    LANGUAGE_SERVER,
    /** Compiled output only (bytecode, objects); no source AST. */
    BYTECODE,
    /** Bytes, text lines or metadata; no syntax tree at all. */
    NO_AST
}
