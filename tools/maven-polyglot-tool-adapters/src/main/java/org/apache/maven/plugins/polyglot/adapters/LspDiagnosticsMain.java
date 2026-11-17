/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.polyglot.adapters;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;

/**
 * The linter process: starts the language server (no shell, its stderr discarded; never a command interpreter, and a
 * .cmd/.bat server never receives a cmd.exe metacharacter), opens every readable file once at version 1, waits until
 * the session is quiet ({@link LspDiagnosticsClient}) or the deadline passes, and writes the diagnostics as SARIF
 * 2.1.0 and build-events lines (unpaired UTF-16 surrogates in server text become U+FFFD). Usage:
 * {@code LspDiagnosticsMain <root> <out.jsonl> <out.sarif> <deadline-ms> <settle-ms> <language-id> <server>
 * [server-arg]... -- <file>...}; the files follow the last "--". Both reports are written on every outcome. Exit 0:
 * complete. Exit {@link #FAILED_EXIT}: the invocation failed (the server could not start, exited, or did not answer
 * initialize; or the reports could not be written). Exit {@link #PARTIAL_EXIT}: files not analysed or not answered,
 * the server still busy at the deadline, or diagnostics published after the session became quiet; the reports name
 * them and mark the invocation unsuccessful.
 * <p>Placed here until code-clustering-lsp's session keeps diagnostics (its client discards them); the argv contract
 * of {@link LspLinterProvider} stays when the collector moves there.
 */
public final class LspDiagnosticsMain {
    static final int VERSION = 1;
    static final int FAILED_EXIT = 2;
    static final int PARTIAL_EXIT = 4;
    static final String USAGE = "usage: LspDiagnosticsMain <root> <out.jsonl> <out.sarif> <deadline-ms> <settle-ms>"
            + " <language-id> <server> [server-arg]... -- <file>...";
    private static final System.Logger LOG = System.getLogger(LspDiagnosticsMain.class.getName());

    /** The parsed command line; the files follow the last "--" (they are absolute paths), so a server may take "--". */
    record Request(Path root, Path jsonl, Path sarif, Duration deadline, Duration settle, String languageId,
            List<String> server, List<Path> files) {
        static Request parse(List<String> args) {
            int split = args.lastIndexOf("--");
            if (split < 7 || split == args.size() - 1) {
                throw new IllegalArgumentException(USAGE);
            }
            List<Path> files = args.subList(split + 1, args.size()).stream()
                    .map(f -> Path.of(f).toAbsolutePath().normalize()).distinct().toList();
            return new Request(Path.of(args.get(0)).toAbsolutePath().normalize(), Path.of(args.get(1)),
                    Path.of(args.get(2)), Duration.ofMillis(Long.parseLong(args.get(3))),
                    Duration.ofMillis(Long.parseLong(args.get(4))), args.get(5), List.copyOf(args.subList(6, split)), files);
        }
    }

    private LspDiagnosticsMain() { }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run(List.of(args)));
    }

    static int run(List<String> args) throws InterruptedException {
        Request request = Request.parse(args);
        LspSession.Outcome outcome = LspSession.collect(request);
        LOG.log(System.Logger.Level.INFO, "LSP linter: files={0}, findings={1}, failed={2}, notes={3}",
                request.files().size(), outcome.findings().size(), outcome.failed(), outcome.notes());
        try {
            FindingReports.write(request.jsonl(), request.sarif(), outcome.tool(), request.root(), outcome.findings(),
                    outcome.notes());
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "LSP linter: cannot write the reports: {0}", failure.toString());
            return FAILED_EXIT;
        }
        return outcome.failed() ? FAILED_EXIT : outcome.notes().isEmpty() ? 0 : PARTIAL_EXIT;
    }

    /** 1-based line and column (UTF-16 code units, as LSP and SARIF count by default); rule = code, else source. */
    static Finding finding(Path root, Path file, Diagnostic d) {
        Optional<Position> start = Optional.ofNullable(d.getRange()).map(r -> r.getStart());
        String rule = d.getCode() != null ? (d.getCode().isLeft() ? d.getCode().getLeft() : String.valueOf(d.getCode().getRight()))
                : Optional.ofNullable(d.getSource()).orElse("lsp");
        return new Finding(FindingReports.relative(root, file), start.map(p -> p.getLine() + 1L).orElse(0L),
                start.map(p -> p.getCharacter() + 1L).orElse(0L), level(d.getSeverity()), rule, d.getMessage());
    }

    static Finding.Level level(DiagnosticSeverity severity) {
        return severity == DiagnosticSeverity.Error ? Finding.Level.ERROR
                : severity == DiagnosticSeverity.Warning || severity == null ? Finding.Level.WARNING : Finding.Level.NOTE;
    }
}
