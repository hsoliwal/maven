/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    /** One provider instance supplies the original command and any explicitly requested reuse assertion. */
    public record Plan(List<String> command, Optional<M3ToolReuseInputs> reuseInputs) {
        public Plan {
            command = List.copyOf(command);
            Objects.requireNonNull(reuseInputs, "reuseInputs");
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
        return plan(task, workspace, false).command();
    }

    /** Existing callers request only argv; reuse qualification is an independent explicit opt-in. */
    public static Plan plan(TaskManifest.Task task, Path workspace, boolean includeReuse) throws IOException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        String[] fields = task.tool().split(":", -1);
        if (fields.length != 4 || !"@m3".equals(fields[0])) {
            throw new IOException("M3 tool must be @m3:provider:capability:language");
        }
        String id = checkId(fields[1]);
        M3ToolCapability capability = M3ToolCapability.parse(fields[2]);
        String language = checkLanguage(fields[3]);
        M3ToolProvider provider = discover().get(id);
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
        Optional<M3ToolReuseInputs> reuse = includeReuse
                ? reuseInputs(provider, capability, language, task, workspace.toAbsolutePath().normalize())
                : Optional.empty();
        return new Plan(argv, reuse);
    }

    static Optional<M3ToolReuseInputs> reuseInputs(M3ToolProvider provider, M3ToolCapability capability,
            String language, TaskManifest.Task task, Path workspace) throws IOException {
        final Optional<M3ToolReuseInputs> requested;
        try {
            requested = provider.reuseInputs(capability, language, task, workspace);
        } catch (RuntimeException invalid) {
            throw new IOException("M3 provider reuse contract failed: " + provider.id(), invalid);
        }
        if (requested == null) throw new IOException("M3 provider returned a null reuse Optional");
        if (requested.isEmpty()) return Optional.empty();
        M3ToolReuseInputs contract = requested.get();
        for (String clean : contract.cleanOutputs()) {
            if (!task.outputs().contains(clean) || InputStamps.statePath(clean)) {
                throw new IOException("M3 provider clean output is not a declared owned output: " + clean);
            }
            for (String input : task.inputs()) {
                if (clean.equals(input) || clean.startsWith(input + "/") || input.startsWith(clean + "/")) {
                    throw new IOException("M3 provider clean output overlaps an input: " + clean);
                }
            }
        }
        var inputs = new ArrayList<>(contract.additionalInputs());
        Path implementation = implementationSource(provider);
        if (!inputs.contains(implementation)) inputs.add(implementation);
        for (String output : task.outputs()) {
            TaskManifest.relativePath(output, false);
            Path directory = workspace.resolve(output).toAbsolutePath().normalize();
            for (Path input : inputs) {
                if (directory.startsWith(input) || input.startsWith(directory)) {
                    throw new IOException("M3 provider output overlaps an additional input: " + output);
                }
            }
        }
        try {
            return Optional.of(new M3ToolReuseInputs(contract.contractId(), inputs,
                    contract.cleanOutputs(), contract.relocatable()));
        } catch (RuntimeException invalid) {
            throw new IOException("invalid bound M3 provider reuse contract: " + provider.id(), invalid);
        }
    }

    private static Path implementationSource(M3ToolProvider provider) throws IOException {
        try {
            var domain = provider.getClass().getProtectionDomain();
            var source = domain == null ? null : domain.getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equals(source.getLocation().getProtocol())) {
                throw new IOException("M3 provider reuse needs a local implementation code source");
            }
            Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("M3 provider implementation code source is unavailable");
            }
            return path;
        } catch (URISyntaxException | RuntimeException invalid) {
            throw new IOException("cannot bind M3 provider implementation code source", invalid);
        }
    }

    private static Map<String, M3ToolProvider> discover() throws IOException {
        var selected = new TreeMap<String, M3ToolProvider>();
        try {
            for (M3ToolProvider provider : ServiceLoader.load(M3ToolProvider.class,
                    M3ToolProviders.class.getClassLoader())) {
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
