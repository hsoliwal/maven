/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

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
        Map<String, M3ToolProvider> registry = discover();
        return guarded("cannot describe M3 tool providers " + registry.keySet(),
                () -> registry.entrySet().stream().map(M3ToolProviders::describe).toList());
    }

    private static Descriptor describe(Map.Entry<String, M3ToolProvider> entry) {
        var roles = entry.getValue().capabilities().stream().map(M3ToolCapability::slug).sorted().toList();
        var languages = entry.getValue().languages().stream().sorted().toList();
        return new Descriptor(entry.getKey(), roles, languages);
    }

    /** A call into provider code, which may throw anything a provider jar built for another engine can. */
    @FunctionalInterface
    interface ProviderCall<T> {
        T call() throws IOException;
    }

    /**
     * Runs provider code; a RuntimeException, a LinkageError (a provider built against a newer engine:
     * NoSuchFieldError, AbstractMethodError, NoClassDefFoundError) or a ServiceConfigurationError becomes an
     * IOException with {@code what} as its message, so no provider can end the caller with an uncaught Error.
     */
    static <T> T guarded(String what, ProviderCall<T> call) throws IOException {
        try {
            return call.call();
        } catch (RuntimeException | LinkageError | ServiceConfigurationError failure) {
            throw new IOException(what, failure);
        }
    }

    /**
     * The first v1 entry point: the planned argv, with the run contract checked as {@link #plan} checks it and source
     * mutation never approved, so a host calling it directly (Gradle, m2e) cannot obtain a source-writing argv.
     */
    public static List<String> command(TaskManifest.Task task, Path workspace) throws IOException {
        return plan(task, workspace, false).argv();
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
        return guarded("M3 provider contract planning failed: " + fields[1],
                () -> contract(registry.get(fields[1]), capability, fields[3], task, argv, approveSourceMutation));
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
        Optional<String> undeclared = reports.stream().filter(r -> r == null || !task.outputs().contains(r.path()))
                .map(String::valueOf).findFirst();
        if (undeclared.isPresent()) {
            throw new IOException("M3 provider report is not a declared output: " + undeclared.get());
        }
        return reports;
    }

    static List<String> command(TaskManifest.Task task, Path workspace, Map<String, M3ToolProvider> registry)
            throws IOException {
        String[] fields = fields(Objects.requireNonNull(task, "task"));
        Objects.requireNonNull(workspace, "workspace");
        String id = checkId(fields[1]);
        M3ToolCapability capability = M3ToolCapability.parse(fields[2]);
        String language = checkLanguage(fields[3]);
        M3ToolProvider provider = supporting(registry, id, capability, language);
        checkDeclarations(task, capability);
        List<String> argv = guarded("M3 provider command planning failed: " + id,
                () -> provider.command(capability, language, task, workspace.toAbsolutePath().normalize()));
        return checkArgv(id, argv);
    }

    private static String[] fields(TaskManifest.Task task) throws IOException {
        String[] fields = task.tool().split(":", -1);
        if (fields.length != 4 || !"@m3".equals(fields[0])) {
            throw new IOException("M3 tool must be @m3:provider:capability:language");
        }
        return fields;
    }

    private static M3ToolProvider supporting(Map<String, M3ToolProvider> registry, String id,
            M3ToolCapability capability, String language) throws IOException {
        M3ToolProvider provider = registry.get(id);
        if (provider == null) {
            throw new IOException("M3 provider is not installed: " + id);
        }
        boolean supported = guarded("M3 provider description failed: " + id,
                () -> provider.capabilities().contains(capability) && provider.languages().contains(language));
        if (!supported) {
            throw new IOException("M3 provider does not support " + capability.slug() + " for " + language);
        }
        return provider;
    }

    /**
     * An atom/pattern/transpile/compile result must have separately declared
     * inputs and outputs; source paths must never also be claimed as outputs.
     */
    private static void checkDeclarations(TaskManifest.Task task, M3ToolCapability capability) throws IOException {
        if (capability == M3ToolCapability.INTERPRETER) {
            return;
        }
        if (task.inputs().isEmpty() || task.outputs().isEmpty()) {
            throw new IOException("M3 " + capability.slug() + " needs declared inputs and outputs");
        }
        Optional<String> overlap = task.inputs().stream()
                .flatMap(input -> task.outputs().stream().filter(output -> overlaps(input, output))).findFirst();
        if (overlap.isPresent()) {
            throw new IOException("M3 provider output overlaps source input: " + overlap.get());
        }
    }

    private static boolean overlaps(String input, String output) {
        return input.equals(output) || output.startsWith(input + "/") || input.startsWith(output + "/");
    }

    private static List<String> checkArgv(String id, List<String> argv) throws IOException {
        if (argv == null || argv.isEmpty() || argv.size() > 1024) {
            throw new IOException("M3 provider returned no command or exceeded argument limit: " + id);
        }
        if (argv.stream().anyMatch(M3ToolProviders::invalidArgument)) {
            throw new IOException("M3 provider returned an invalid argv element: " + id);
        }
        return List.copyOf(argv);
    }

    private static boolean invalidArgument(String argument) {
        return argument == null || argument.isBlank() || argument.length() > 4096
                || argument.chars().anyMatch(Character::isISOControl);
    }

    private static Map<String, M3ToolProvider> discover() throws IOException {
        return registry(ServiceLoader.load(M3ToolProvider.class, M3ToolProviders.class.getClassLoader()));
    }

    /** Validated, sorted registry; package-private so contract tests can supply providers directly. */
    static Map<String, M3ToolProvider> registry(Iterable<M3ToolProvider> providers) throws IOException {
        List<M3ToolProvider> loaded = guarded("cannot load M3 tool providers",
                () -> StreamSupport.stream(providers.spliterator(), false).toList());
        var selected = new TreeMap<String, M3ToolProvider>();
        Optional<IOException> refused = loaded.stream().map(provider -> refusal(() -> admit(selected, provider)))
                .flatMap(Optional::stream).findFirst();
        if (refused.isPresent()) {
            throw refused.get();
        }
        return selected;
    }

    /** One provider into the registry; every call into it is guarded and named after its class. */
    private static Void admit(Map<String, M3ToolProvider> selected, M3ToolProvider provider) throws IOException {
        String what = "cannot load M3 tool provider " + (provider == null ? "null" : provider.getClass().getName());
        if (provider == null || guarded(what, provider::apiVersion) != M3ToolProvider.API_VERSION) {
            throw new IOException("M3 provider SPI version mismatch");
        }
        String id = checkId(guarded(what, provider::id));
        Set<M3ToolCapability> roles = guarded(what, provider::capabilities);
        Set<String> languages = guarded(what, provider::languages);
        checkRoles(id, roles, languages);
        return putOnce(selected, id, provider);
    }

    private static void checkRoles(String id, Set<M3ToolCapability> roles, Set<String> languages)
            throws IOException {
        if (!valid(roles) || !valid(languages)) {
            throw new IOException("M3 provider must declare valid roles and languages: " + id);
        }
        Optional<IOException> invalid = languages.stream().map(language -> refusal(() -> checkLanguage(language)))
                .flatMap(Optional::stream).findFirst();
        if (invalid.isPresent()) {
            throw invalid.get();
        }
    }

    private static boolean valid(Set<?> values) {
        return values != null && !values.isEmpty() && values.stream().noneMatch(Objects::isNull);
    }

    private static Void putOnce(Map<String, M3ToolProvider> selected, String id, M3ToolProvider provider)
            throws IOException {
        if (selected.putIfAbsent(id, provider) != null) {
            throw new IOException("ambiguous duplicate M3 provider identifier: " + id);
        }
        return null;
    }

    /** The IOException a check throws, if any, so checks can run inside streams. */
    private static Optional<IOException> refusal(ProviderCall<?> check) {
        try {
            check.call();
            return Optional.empty();
        } catch (IOException refused) {
            return Optional.of(refused);
        }
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
