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
package org.apache.maven.plugins.clustering.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Atomizes files with language servers, so each language is split by its own language core. Examples: clangd (the
 * server behind Eclipse CDT's LSP support), rust-analyzer (Eclipse Corrosion), Eclipse JDT LS, LemMinX (Eclipse
 * Wild Web Developer), pyright or pylsp, gopls. Configure with the system property or environment variable
 * {@code clustering.lsp.servers} / {@code CLUSTERING_LSP_SERVERS}:
 * <pre>c,h,cc,cpp,hpp=clangd;rs=rust-analyzer;py=pyright-langserver --stdio</pre>
 * Extensions before {@code =}, the server command after it, split on spaces and run without a shell. On Windows,
 * prefix {@code cmd /c} for a {@code .cmd} launcher. One server process runs per command and source root. If a
 * server cannot start or answer, its files go to the lexical front end instead, with a warning, so a missing server
 * never loses atoms. {@code clustering.lsp.timeoutSeconds} (default 60) bounds every request.
 */
public final class LspFrontEnd implements AtomFrontEnd {
    /** Opens a session for a server command in a source root. */
    @FunctionalInterface
    interface Starter {
        LspSession start(List<String> command, Path root, Duration timeout) throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(LspFrontEnd.class.getName());
    private static final Map<String, String> LANGUAGE_IDS = Map.ofEntries(Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cc", "cpp"), Map.entry("cpp", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"),
            Map.entry("hh", "cpp"), Map.entry("rs", "rust"), Map.entry("py", "python"), Map.entry("java", "java"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascriptreact"), Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescriptreact"), Map.entry("go", "go"), Map.entry("kt", "kotlin"),
            Map.entry("cs", "csharp"), Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("css", "css"));

    private final Map<String, List<String>> commands;
    private final Duration timeout;
    private final Starter starter;

    /** The service constructor: configuration from the system property or environment. */
    public LspFrontEnd() {
        this(parse(setting("clustering.lsp.servers", "CLUSTERING_LSP_SERVERS")),
                Duration.ofSeconds(Long.parseLong(setting("clustering.lsp.timeoutSeconds", "CLUSTERING_LSP_TIMEOUT_SECONDS", "60"))),
                LspSession::start);
    }

    LspFrontEnd(Map<String, List<String>> commands, Duration timeout, Starter starter) {
        this.commands = commands;
        this.timeout = timeout;
        this.starter = starter;
    }

    @Override public String name() {
        return "lsp";
    }

    @Override public boolean accepts(Path file) {
        return commands.containsKey(extension(file));
    }

    @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
        Map<List<String>, List<Path>> byServer = new LinkedHashMap<>();
        files.forEach(f -> byServer.computeIfAbsent(commands.get(extension(f)), k -> new ArrayList<>()).add(f));
        List<Atom> atoms = new ArrayList<>();
        for (Map.Entry<List<String>, List<Path>> e : byServer.entrySet()) {
            try (LspSession session = starter.start(e.getKey(), root, timeout)) {
                for (Path file : e.getValue()) atoms.addAll(atomsOf(session, root, file));
            } catch (IOException | RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "language server " + e.getKey() + " failed (" + failure
                        + "); its " + e.getValue().size() + " files go to the lexical front end");
                AtomFrontEnd lexical = new AtomFrontEnd.LexicalFrontEnd();
                List<Path> accepted = e.getValue().stream().filter(lexical::accepts).collect(Collectors.toList());
                atoms.removeIf(a -> e.getValue().stream().anyMatch(f -> relative(root, f).equals(a.path())));
                if (!accepted.isEmpty()) atoms.addAll(lexical.atoms(root, accepted));
            }
        }
        return atoms;
    }

    private List<Atom> atomsOf(LspSession session, Path root, Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String ext = extension(file);
        return SymbolAtoms.atoms(session.symbols(file, text, LANGUAGE_IDS.getOrDefault(ext, ext)), relative(root, file),
                text, ext.equals("py"));
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** {@code c,h=clangd;rs=rust-analyzer} to extension -> command. */
    static Map<String, List<String>> parse(String setting) {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        if (setting == null) return commands;
        for (String entry : setting.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            List<String> command = List.of(entry.substring(eq + 1).trim().split("\\s+"));
            for (String ext : entry.substring(0, eq).split(",")) {
                if (!ext.isBlank()) commands.put(ext.strip().toLowerCase(Locale.ROOT), command);
            }
        }
        return commands;
    }

    private static String setting(String property, String env) {
        return setting(property, env, null);
    }

    private static String setting(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(env);
        return value == null ? fallback : value;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
