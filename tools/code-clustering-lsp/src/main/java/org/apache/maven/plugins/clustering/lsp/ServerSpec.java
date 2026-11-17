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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One server of a profile, for example {@code jdt} or {@code wtp.html}. Its command comes from, in this order:
 * <ol>
 *   <li>the system property {@code clustering.lsp.<id>.command};</li>
 *   <li>the environment variable {@code CLUSTERING_LSP_<ID>_COMMAND} (the id in upper case, {@code .} as {@code _});</li>
 *   <li>detection: in the home directories ({@code clustering.lsp.<id>.home} or {@code <ID>_HOME}, then for a server
 *       of a several-server profile {@code clustering.lsp.<profile>.home} or {@code <PROFILE>_HOME}), then on the
 *       {@code PATH}.</li>
 * </ol>
 * A command setting is split by {@link CommandLine}, so quoted paths may hold spaces. Requests wait at most
 * {@code clustering.lsp.<id>.timeoutSeconds}, else {@code clustering.lsp.timeoutSeconds}, else the server's default.
 */
record ServerSpec(String profile, String id, Map<String, String> languageIds, Duration defaultTimeout,
        Detector detector) {
    /** Finds the server's command in the home directories or on the PATH. */
    @FunctionalInterface
    interface Detector {
        List<String> detect(Lookup lookup, List<Path> homes) throws Unresolved;
    }

    /** The server was not found; the message says where it was looked for. */
    static final class Unresolved extends Exception {
        private static final long serialVersionUID = 1L;

        Unresolved(String message) {
            super(message);
        }
    }

    LspServer resolve(Lookup lookup) throws Unresolved {
        return new LspServer(id, command(lookup), timeout(lookup), languageIds);
    }

    List<String> command(Lookup lookup) throws Unresolved {
        Optional<String> given = lookup.setting(property("command"), variable("CLUSTERING_LSP_", "_COMMAND"));
        if (given.isEmpty()) return detector.detect(lookup, homes(lookup));
        try {
            List<String> command = CommandLine.split(given.get());
            if (command.isEmpty()) throw new Unresolved("the command setting " + property("command") + " is empty");
            return command;
        } catch (IllegalArgumentException e) {
            throw new Unresolved(e.getMessage());
        }
    }

    List<Path> homes(Lookup lookup) {
        List<Path> homes = new ArrayList<>();
        lookup.path(property("home"), variable("", "_HOME")).ifPresent(homes::add);
        if (!id.equals(profile)) {
            lookup.path("clustering.lsp." + profile + ".home", profile.toUpperCase(Locale.ROOT) + "_HOME")
                    .ifPresent(homes::add);
        }
        return homes;
    }

    private Duration timeout(Lookup lookup) {
        return lookup.setting(property("timeoutSeconds"), variable("CLUSTERING_LSP_", "_TIMEOUT_SECONDS"))
                .or(() -> lookup.setting("clustering.lsp.timeoutSeconds", "CLUSTERING_LSP_TIMEOUT_SECONDS"))
                .map(s -> Duration.ofSeconds(Long.parseLong(s))).orElse(defaultTimeout);
    }

    private String property(String name) {
        return "clustering.lsp." + id + "." + name;
    }

    private String variable(String prefix, String suffix) {
        return prefix + id.toUpperCase(Locale.ROOT).replace('.', '_') + suffix;
    }
}
