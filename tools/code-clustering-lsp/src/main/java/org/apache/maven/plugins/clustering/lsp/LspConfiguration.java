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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which server serves each file extension. Entries of {@code clustering.lsp.servers} ({@code CLUSTERING_LSP_SERVERS})
 * come first: {@code c,h=clangd;rs=rust-analyzer;cpp="C:/Program Files/LLVM/bin/clangd.exe" --log=error}, extensions
 * before {@code =}, the command after it, split by {@link CommandLine}; a {@code ;} inside quotes does not end an
 * entry. Then the servers of the {@link ServerProfiles profiles} in {@code clustering.lsp.profiles} fill the
 * extensions that no entry names; a profile server whose extensions are all named is not looked for, and one that
 * cannot be found is skipped with one warning.
 */
final class LspConfiguration {
    static final String SERVERS = "clustering.lsp.servers";
    static final String PROFILES = "clustering.lsp.profiles";
    private static final Map<String, String> LANGUAGE_IDS = Map.ofEntries(Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cc", "cpp"), Map.entry("cpp", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"),
            Map.entry("hh", "cpp"), Map.entry("rs", "rust"), Map.entry("py", "python"), Map.entry("java", "java"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascriptreact"), Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescriptreact"), Map.entry("go", "go"), Map.entry("kt", "kotlin"),
            Map.entry("cs", "csharp"), Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("htm", "html"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("less", "less"), Map.entry("json", "json"));

    private LspConfiguration() { }

    /** The servers from the settings: explicit entries first, then the selected profiles. */
    static Map<String, LspServer> configured(Lookup lookup, Consumer<String> warn) {
        Duration timeout = Duration.ofSeconds(Long.parseLong(lookup.setting("clustering.lsp.timeoutSeconds",
                "CLUSTERING_LSP_TIMEOUT_SECONDS").orElse("60")));
        Map<String, LspServer> table = explicit(parse(lookup.setting(SERVERS, "CLUSTERING_LSP_SERVERS").orElse(""), warn),
                timeout);
        for (ServerSpec spec : ServerProfiles.select(lookup.setting(PROFILES, "CLUSTERING_LSP_PROFILES").orElse(""), warn)) {
            if (table.keySet().containsAll(spec.languageIds().keySet())) continue;
            try {
                LspServer server = spec.resolve(lookup);
                server.languageIds().keySet().forEach(ext -> table.putIfAbsent(ext, server));
            } catch (ServerSpec.Unresolved e) {
                warn.accept("language server profile " + spec.profile() + ": " + spec.id() + " skipped: " + e.getMessage());
            }
        }
        return table;
    }

    /** {@code c,h=clangd;rs=rust-analyzer} to extension -> command; an entry with an unclosed quote is skipped. */
    static Map<String, List<String>> parse(String setting, Consumer<String> warn) {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        for (String entry : CommandLine.entries(setting == null ? "" : setting)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                List<String> command = CommandLine.split(entry.substring(eq + 1));
                for (String ext : entry.substring(0, eq).split(",")) {
                    if (!ext.isBlank() && !command.isEmpty()) commands.put(ext.strip().toLowerCase(Locale.ROOT), command);
                }
            } catch (IllegalArgumentException e) {
                warn.accept(SERVERS + " entry skipped: " + e.getMessage());
            }
        }
        return commands;
    }

    /** One server per distinct command, so extensions that share a command share its process. */
    static Map<String, LspServer> explicit(Map<String, List<String>> commands, Duration timeout) {
        Map<List<String>, Map<String, String>> byCommand = new LinkedHashMap<>();
        commands.forEach((ext, command) -> byCommand.computeIfAbsent(command, k -> new LinkedHashMap<>())
                .put(ext, LANGUAGE_IDS.getOrDefault(ext, ext)));
        Map<String, LspServer> table = new LinkedHashMap<>();
        byCommand.forEach((command, ids) -> {
            LspServer server = new LspServer(String.join(" ", command), command, timeout, ids);
            ids.keySet().forEach(ext -> table.put(ext, server));
        });
        return table;
    }
}
