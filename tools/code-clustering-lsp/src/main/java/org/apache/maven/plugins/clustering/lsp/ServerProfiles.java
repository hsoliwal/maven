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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The named server profiles, one per Eclipse language core, selected with {@code clustering.lsp.profiles}
 * ({@code CLUSTERING_LSP_PROFILES}), for example {@code jdt,cdt} or {@code all}:
 * <table>
 *   <caption>Profiles</caption>
 *   <tr><th>Profile</th><th>Aliases</th><th>Servers (ids)</th><th>Extensions</th></tr>
 *   <tr><td>jdt</td><td>java</td><td>Eclipse JDT LS ({@code jdt})</td><td>java</td></tr>
 *   <tr><td>cdt</td><td>c, cpp</td><td>clangd ({@code cdt})</td><td>c, h, cc, cpp, cxx, hpp, hh, hxx</td></tr>
 *   <tr><td>pydev</td><td>python</td><td>pylsp, else pyright ({@code pydev})</td><td>py, pyi</td></tr>
 *   <tr><td>wtp</td><td>xml, html, css, json, web</td><td>LemMinX ({@code wtp.xml}), the vscode-langservers-extracted
 *       HTML, CSS and JSON servers ({@code wtp.html}, {@code wtp.css}, {@code wtp.json})</td>
 *       <td>xml, xsd, xsl; html, htm; css, scss, less; json, jsonc</td></tr>
 *   <tr><td>rust</td><td>rustdt, corrosion</td><td>rust-analyzer ({@code rust})</td><td>rs</td></tr>
 *   <tr><td>go</td><td></td><td>gopls ({@code go})</td><td>go</td></tr>
 *   <tr><td>typescript</td><td>ts, js, javascript</td><td>typescript-language-server ({@code typescript})</td>
 *       <td>ts, tsx, mts, cts, js, jsx, mjs, cjs</td></tr>
 *   <tr><td>csharp</td><td>cs</td><td>csharp-ls ({@code csharp})</td><td>cs, csx</td></tr>
 *   <tr><td>swift</td><td></td><td>sourcekit-lsp ({@code swift})</td><td>swift</td></tr>
 * </table>
 * See {@link ServerSpec} for how each server's command is found.
 */
final class ServerProfiles {
    static final List<String> NAMES = List.of("jdt", "cdt", "pydev", "wtp", "rust", "go", "typescript", "csharp", "swift");
    static final List<ServerSpec> SERVERS = List.of(
            new ServerSpec("jdt", "jdt", Map.of("java", "java"), Duration.ofSeconds(180), JdtlsCommand::detect),
            new ServerSpec("cdt", "cdt", Map.of("c", "c", "h", "c", "cc", "cpp", "cpp", "cpp", "cxx", "cpp", "hpp", "cpp",
                    "hh", "cpp", "hxx", "cpp"), Duration.ofSeconds(60), (l, h) -> ServerDetection.executable(l, h, "clangd")),
            new ServerSpec("pydev", "pydev", Map.of("py", "python", "pyi", "python"), Duration.ofSeconds(60),
                    ServerDetection::python),
            new ServerSpec("wtp", "wtp.xml", Map.of("xml", "xml", "xsd", "xml", "xsl", "xml"), Duration.ofSeconds(60),
                    ServerDetection::lemminx),
            web("html", Map.of("html", "html", "htm", "html")),
            web("css", Map.of("css", "css", "scss", "scss", "less", "less")),
            web("json", Map.of("json", "json", "jsonc", "jsonc")),
            new ServerSpec("rust", "rust", Map.of("rs", "rust"), Duration.ofSeconds(60),
                    (l, h) -> ServerDetection.executable(l, h, "rust-analyzer")),
            new ServerSpec("go", "go", Map.of("go", "go"), Duration.ofSeconds(60), ServerDetection::gopls),
            new ServerSpec("typescript", "typescript", Map.of("ts", "typescript", "tsx", "typescriptreact", "mts",
                    "typescript", "cts", "typescript", "js", "javascript", "jsx", "javascriptreact", "mjs", "javascript",
                    "cjs", "javascript"), Duration.ofSeconds(60), (l, h) -> ServerDetection.npm(l, h,
                    "typescript-language-server", "typescript-language-server", "lib/cli.mjs")),
            new ServerSpec("csharp", "csharp", Map.of("cs", "csharp", "csx", "csharp"), Duration.ofSeconds(120),
                    ServerDetection::csharpLs),
            new ServerSpec("swift", "swift", Map.of("swift", "swift"), Duration.ofSeconds(60),
                    (l, h) -> ServerDetection.executable(l, h, "sourcekit-lsp")));
    private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("java", "jdt"), Map.entry("c", "cdt"),
            Map.entry("cpp", "cdt"), Map.entry("python", "pydev"), Map.entry("xml", "wtp"), Map.entry("html", "wtp"),
            Map.entry("css", "wtp"), Map.entry("json", "wtp"), Map.entry("web", "wtp"), Map.entry("rustdt", "rust"),
            Map.entry("corrosion", "rust"), Map.entry("ts", "typescript"), Map.entry("js", "typescript"),
            Map.entry("javascript", "typescript"), Map.entry("cs", "csharp"));

    private ServerProfiles() { }

    /** Profile names from a setting such as {@code java, C ,web}; {@code all} selects every profile. */
    static List<String> names(String setting, Consumer<String> warn) {
        Set<String> chosen = new LinkedHashSet<>();
        for (String word : setting.split("[,\\s]+")) {
            String name = ALIASES.getOrDefault(word.toLowerCase(Locale.ROOT), word.toLowerCase(Locale.ROOT));
            if (name.equals("all")) chosen.addAll(NAMES);
            else if (NAMES.contains(name)) chosen.add(name);
            else if (!name.isEmpty()) warn.accept("unknown language server profile '" + word + "' (known: "
                    + String.join(", ", NAMES) + ", all; aliases: " + String.join(", ", ALIASES.keySet().stream().sorted().toList()) + ")");
        }
        return NAMES.stream().filter(chosen::contains).toList();
    }

    /** The servers of the selected profiles, in catalogue order. */
    static List<ServerSpec> select(String setting, Consumer<String> warn) {
        List<String> names = names(setting, warn);
        return SERVERS.stream().filter(s -> names.contains(s.profile())).toList();
    }

    static ServerSpec server(String id) {
        return SERVERS.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no language server " + id));
    }

    /** A vscode-langservers-extracted server, run with node. */
    private static ServerSpec web(String language, Map<String, String> languageIds) {
        String bin = "vscode-" + language + "-language-server";
        return new ServerSpec("wtp", "wtp." + language, languageIds, Duration.ofSeconds(60),
                (l, h) -> ServerDetection.npm(l, h, bin, "vscode-langservers-extracted", "bin/" + bin));
    }
}
