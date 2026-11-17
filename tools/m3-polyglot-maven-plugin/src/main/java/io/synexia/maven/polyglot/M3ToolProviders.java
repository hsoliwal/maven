/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic registry for the public, versioned M3 tool SPI. The reserved
 * manifest tool has the form {@code @m3:provider:capability:language}. Unknown,
 * ambiguous and malformed requests fail closed, including during a dry run.
 */
public final class M3ToolProviders {
    private M3ToolProviders() {}

    /** Stable, sorted description suitable for Maven, Gradle and Eclipse proxies. */
    public record Descriptor(String id, List<String> capabilities, List<String> languages) {
        public Descriptor {
            capabilities = List.copyOf(capabilities);
            languages = List.copyOf(languages);
        }
    }

    public static List<Descriptor> available() throws IOException {
        var descriptors = new ArrayList<Descriptor>();
        for (Map.Entry<String, M3ToolProvider> entry : discover().entrySet()) {
            var roles = entry.getValue().capabilities().stream().map(M3ToolCapability::slug).sorted().toList();
            var languages = entry.getValue().languages().stream().sorted().toList();
            descriptors.add(new Descriptor(entry.getKey(), roles, languages));
        }
        return List.copyOf(descriptors);
    }

    public static List<String> command(TaskManifest.Task task, Path workspace) throws IOException {
        return command(task, workspace, discover());
    }

    /** The argv plus the provider-declared run contract: success exit codes, stdout file, effect, reports. */
    public record Planned(List<String> argv, Set<Integer> successExitCodes, Optional<String> stdout,
            M3ToolEffect effect, List<M3Report> reports) {
        public Planned {
            argv = List.copyOf(argv);
            successExitCodes = Set.copyOf(successExitCodes);
            reports = List.copyOf(reports);
        }
    }

    /** Plans one provider task; a WRITES_SOURCES task is refused unless source mutation is approved. */
    public static Planned plan(TaskManifest.Task task, Path workspace, boolean approveSourceMutation)
            throws IOException {
        return plan(task, workspace, approveSourceMutation, discover());
    }

    static Planned plan(TaskManifest.Task task, Path workspace, boolean approveSourceMutation,
            Map<String, M3ToolProvider> registry) throws IOException {
        List<String> argv = command(task, workspace, registry);
        String[] fields = task.tool().split(":", -1);
        M3ToolCapability capability = M3ToolCapability.parse(fields[2]);
        try {
            return contract(registry.get(fields[1]), capability, fields[3], task, argv, approveSourceMutation);
        } catch (RuntimeException error) {
            throw new IOException("M3 provider contract planning failed: " + fields[1], error);
        }
    }

    private static Planned contract(M3ToolProvider provider, M3ToolCapability capability, String language,
            TaskManifest.Task task, List<String> argv, boolean approved) throws IOException {
        M3ToolEffect effect = Objects.requireNonNull(provider.effect(capability, language, task), "effect");
        if (effect == M3ToolEffect.WRITES_SOURCES && !approved) {
            throw new IOException("M3 " + capability.slug() + " writes sources and needs explicit approval: " + task.name());
        }
        Set<Integer> codes = exitCodes(provider.successExitCodes(capability, language, task));
        Optional<String> stdout = Objects.requireNonNull(provider.stdoutOutput(capability, language, task), "stdout");
        if (stdout.isPresent() && !task.outputs().contains(stdout.get())) {
            throw new IOException("M3 provider stdout file is not a declared output: " + stdout.get());
        }
        return new Planned(argv, codes, stdout, effect, reports(provider, capability, language, task));
    }

    private static Set<Integer> exitCodes(Set<Integer> codes) throws IOException {
        if (codes == null || !codes.contains(0) || codes.size() > 8
                || codes.stream().anyMatch(code -> code == null || code < 0 || code > 255)) {
            throw new IOException("M3 provider success exit codes must include 0 and stay within 0..255");
        }
        return codes;
    }

    private static List<M3Report> reports(M3ToolProvider provider, M3ToolCapability capability, String language,
            TaskManifest.Task task) throws IOException {
        List<M3Report> reports = Objects.requireNonNull(provider.reports(capability, language, task), "reports");
        for (M3Report report : reports) {
            if (report == null || !task.outputs().contains(report.path())) {
                throw new IOException("M3 provider report is not a declared output: " + report);
            }
        }
        return reports;
    }

    static List<String> command(TaskManifest.Task task, Path workspace, Map<String, M3ToolProvider> registry)
            throws IOException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        String[] fields = task.tool().split(":", -1);
        if (fields.length != 4 || !"@m3".equals(fields[0])) {
            throw new IOException("M3 tool must be @m3:provider:capability:language");
        }
        String id = checkId(fields[1]);
        M3ToolCapability capability = M3ToolCapability.parse(fields[2]);
        String language = checkLanguage(fields[3]);
        M3ToolProvider provider = registry.get(id);
        if (provider == null) {
            throw new IOException("M3 provider is not installed: " + id);
        }
        if (!provider.capabilities().contains(capability) || !provider.languages().contains(language)) {
            throw new IOException("M3 provider does not support " + capability.slug() + " for " + language);
        }
        // An atom/pattern/transpile/compile result must have separately declared
        // inputs and outputs; source paths must never also be claimed as outputs.
        if (capability != M3ToolCapability.INTERPRETER) {
            if (task.inputs().isEmpty() || task.outputs().isEmpty()) {
                throw new IOException("M3 " + capability.slug() + " needs declared inputs and outputs");
            }
            for (String input : task.inputs()) {
                for (String output : task.outputs()) {
                    if (input.equals(output) || output.startsWith(input + "/") || input.startsWith(output + "/")) {
                        throw new IOException("M3 provider output overlaps source input: " + output);
                    }
                }
            }
        }
        final List<String> argv;
        try {
            argv = provider.command(capability, language, task, workspace.toAbsolutePath().normalize());
        } catch (RuntimeException error) {
            throw new IOException("M3 provider command planning failed: " + id, error);
        }
        if (argv == null || argv.isEmpty() || argv.size() > 1024) {
            throw new IOException("M3 provider returned no command or exceeded argument limit: " + id);
        }
        for (String argument : argv) {
            if (argument == null || argument.isBlank() || argument.length() > 4096
                    || argument.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("M3 provider returned an invalid argv element: " + id);
            }
        }
        return List.copyOf(argv);
    }

    private static Map<String, M3ToolProvider> discover() throws IOException {
        return registry(ServiceLoader.load(M3ToolProvider.class, M3ToolProviders.class.getClassLoader()));
    }

    /** Validated, sorted registry; package-private so contract tests can supply providers directly. */
    static Map<String, M3ToolProvider> registry(Iterable<M3ToolProvider> providers) throws IOException {
        var selected = new TreeMap<String, M3ToolProvider>();
        try {
            for (M3ToolProvider provider : providers) {
                if (provider == null || provider.apiVersion() != M3ToolProvider.API_VERSION) {
                    throw new IOException("M3 provider SPI version mismatch");
                }
                String id = checkId(provider.id());
                Set<M3ToolCapability> roles = provider.capabilities();
                Set<String> languages = provider.languages();
                if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)
                        || languages == null || languages.isEmpty() || languages.stream().anyMatch(Objects::isNull)) {
                    throw new IOException("M3 provider must declare valid roles and languages: " + id);
                }
                for (String language : languages) checkLanguage(language);
                if (selected.putIfAbsent(id, provider) != null) {
                    throw new IOException("ambiguous duplicate M3 provider identifier: " + id);
                }
            }
        } catch (ServiceConfigurationError | RuntimeException failure) {
            throw new IOException("cannot load M3 tool providers", failure);
        }
        return selected;
    }

    private static String checkId(String id) throws IOException {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IOException("invalid M3 provider identifier: " + id);
        }
        return id;
    }

    private static String checkLanguage(String language) throws IOException {
        if (language == null || !language.matches("[a-z][a-z0-9+._-]{0,63}")) {
            throw new IOException("invalid M3 language identifier: " + language);
        }
        return language;
    }
}
