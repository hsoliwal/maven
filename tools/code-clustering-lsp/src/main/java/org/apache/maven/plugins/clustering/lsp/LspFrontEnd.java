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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Atomizes files with language servers, so each language is split by its own language core: Eclipse JDT LS, clangd
 * (the server behind Eclipse CDT's LSP support), pylsp or pyright (for PyDev users), LemMinX and the HTML, CSS and
 * JSON servers (Eclipse Wild Web Developer / WTP), rust-analyzer (Eclipse Corrosion), or any other server. Select
 * servers with one switch, {@code clustering.lsp.profiles=jdt,cdt,pydev,wtp,rust} (or {@code all}; see
 * {@link ServerProfiles}), or name commands per extension with {@code clustering.lsp.servers}
 * ({@code c,h=clangd;rs=rust-analyzer}); explicit entries win (see {@link LspConfiguration}). Commands run without a
 * shell. One server process runs per server and source root. If a server cannot start or answer, its files go to the
 * built-in front ends (javac for Java, the lexical front end for the brace and indentation languages) with a warning,
 * so a broken server never loses atoms that the plugin can find on its own. Each server's requests wait at most its
 * timeout ({@code clustering.lsp.timeoutSeconds}, default 60; 180 for JDT LS).
 */
public final class LspFrontEnd implements AtomFrontEnd {
    /** Opens a session for a server command in a source root. */
    @FunctionalInterface
    interface Starter {
        LspSession start(List<String> command, Path root, Duration timeout) throws IOException;
    }

    /** What one server did with its files in one source root; {@code failure} is empty when it answered. */
    record Outcome(LspServer server, int files, int atoms, String failure) {
        boolean ok() {
            return failure.isEmpty();
        }
    }

    private static final System.Logger LOG = System.getLogger(LspFrontEnd.class.getName());

    private final Map<String, LspServer> servers;
    private final Starter starter;
    private final Consumer<Outcome> outcomes;

    /** The service constructor: configuration from system properties, the environment and the PATH. */
    public LspFrontEnd() {
        this(LspConfiguration.configured(Lookup.system(), m -> LOG.log(System.Logger.Level.WARNING, m)),
                LspSession::start, LspFrontEnd::report);
    }

    LspFrontEnd(Map<String, List<String>> commands, Duration timeout, Starter starter) {
        this(LspConfiguration.explicit(commands, timeout), starter, LspFrontEnd::report);
    }

    LspFrontEnd(Map<String, LspServer> servers, Starter starter, Consumer<Outcome> outcomes) {
        this.servers = Map.copyOf(servers);
        this.starter = starter;
        this.outcomes = outcomes;
    }

    @Override public String name() {
        return "lsp";
    }

    @Override public boolean accepts(Path file) {
        return servers.containsKey(extension(file));
    }

    @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
        Map<LspServer, List<Path>> byServer = new LinkedHashMap<>();
        files.forEach(f -> byServer.computeIfAbsent(servers.get(extension(f)), k -> new ArrayList<>()).add(f));
        List<Atom> atoms = new ArrayList<>();
        for (Map.Entry<LspServer, List<Path>> e : byServer.entrySet()) atoms.addAll(serve(root, e.getKey(), e.getValue()));
        return atoms;
    }

    private List<Atom> serve(Path root, LspServer server, List<Path> files) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        try (LspSession session = starter.start(server.command(), root, server.timeout())) {
            for (Path file : files) atoms.addAll(atomsOf(session, server, root, file));
        } catch (IOException | RuntimeException failure) {
            outcomes.accept(new Outcome(server, files.size(), 0, failure.toString()));
            return builtIn(root, files);
        }
        outcomes.accept(new Outcome(server, files.size(), atoms.size(), ""));
        return atoms;
    }

    private List<Atom> atomsOf(LspSession session, LspServer server, Path root, Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String languageId = server.languageId(extension(file));
        return SymbolAtoms.atoms(session.symbols(file, text, languageId), relative(root, file), text,
                languageId.equals("python"), SymbolMapping.forLanguage(languageId));
    }

    /** Each file to the first built-in front end that accepts it: javac, then lexical. */
    private static List<Atom> builtIn(Path root, List<Path> files) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        List<Path> left = new ArrayList<>(files);
        for (AtomFrontEnd builtIn : List.of(new AtomFrontEnd.JavacFrontEnd(), new AtomFrontEnd.LexicalFrontEnd())) {
            List<Path> accepted = left.stream().filter(builtIn::accepts).collect(Collectors.toList());
            left.removeAll(accepted);
            if (!accepted.isEmpty()) atoms.addAll(builtIn.atoms(root, accepted));
        }
        return atoms;
    }

    private static void report(Outcome outcome) {
        LspServer server = outcome.server();
        if (outcome.ok()) {
            LOG.log(System.Logger.Level.INFO, "language server " + server.name() + " " + server.command() + ": "
                    + outcome.files() + " files, " + outcome.atoms() + " atoms");
        } else {
            LOG.log(System.Logger.Level.WARNING, "language server " + server.name() + " " + server.command()
                    + " failed (" + outcome.failure() + "); its " + outcome.files() + " files go to the built-in front ends");
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
