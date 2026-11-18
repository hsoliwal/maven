/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Build-owner inventory for arbitrary Eclipse language plugins. Never invokes
 * an Eclipse builder or a compiler, never constructs a delta, and cannot
 * authorize Maven/Gradle task skipping or cache restoration.
 *
 * <p>The exact order of Eclipse buildSpec declarations is preserved. Unknown
 * builder and nature IDs remain visible as unbound: an IDE nature alone is not
 * evidence that a language build provider is installed or runnable.</p>
 */
public final class M3EclipseBuilderRegistry {
    private M3EclipseBuilderRegistry() { }

    public record Binding(int position, String builderId, String triggers, String providerId) { }

    /** A nature can be recognized by multiple language/editor adapters. */
    public record NatureBinding(int position, String natureId, List<String> providerIds) {
        public NatureBinding { providerIds = List.copyOf(providerIds); }
    }

    public record Report(String project, List<Binding> builders, List<NatureBinding> natures,
            List<String> unboundNatures,
            List<String> providers, Map<String, String> descriptorSha256) {
        public Report {
            builders = List.copyOf(builders);
            natures = List.copyOf(natures);
            unboundNatures = List.copyOf(unboundNatures);
            providers = List.copyOf(providers);
            descriptorSha256 = Collections.unmodifiableMap(new TreeMap<>(descriptorSha256));
        }

        /** Deterministic versioned JSON for Maven, Gradle and Eclipse proxies. */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-eclipse-builder-owners-v1\",\"project\":");
            quote(out, project);
            out.append(",\"builders\":[");
            for (int i = 0; i < builders.size(); i++) {
                if (i != 0) out.append(',');
                Binding binding = builders.get(i);
                out.append("{\"position\":").append(binding.position()).append(",\"builderId\":");
                quote(out, binding.builderId());
                out.append(",\"triggers\":");
                quote(out, binding.triggers());
                out.append(",\"providerId\":");
                if (binding.providerId() == null) out.append("null");
                else quote(out, binding.providerId());
                out.append('}');
            }
            out.append(']');
            out.append(",\"natures\":[");
            for (int i = 0; i < natures.size(); i++) {
                if (i != 0) out.append(',');
                NatureBinding binding = natures.get(i);
                out.append("{\"position\":").append(binding.position()).append(",\"natureId\":");
                quote(out, binding.natureId());
                strings(out, "providerIds", binding.providerIds());
                out.append('}');
            }
            out.append(']');
            strings(out, "unboundNatures", unboundNatures);
            strings(out, "providers", providers);
            out.append(",\"descriptorSha256\":{");
            int i = 0;
            for (Map.Entry<String, String> entry : descriptorSha256.entrySet()) {
                if (i++ != 0) out.append(',');
                quote(out, entry.getKey());
                out.append(':');
                quote(out, entry.getValue());
            }
            return out.append("}}").toString();
        }

        private static void strings(StringBuilder out, String key, List<String> values) {
            out.append(',');
            quote(out, key);
            out.append(":[");
            for (int i = 0; i < values.size(); i++) {
                if (i != 0) out.append(',');
                quote(out, values.get(i));
            }
            out.append(']');
        }

        private static void quote(StringBuilder out, String value) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append("\\u");
                            for (int shift = 12; shift >= 0; shift -= 4) {
                                out.append(Character.forDigit((c >>> shift) & 15, 16));
                            }
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    public static Report inspect(Path eclipseProject) throws IOException {
        Objects.requireNonNull(eclipseProject, "eclipseProject");
        return assess(EclipseProjectModel.inspect(eclipseProject), discover());
    }

    /** Package-visible for strict provider-contract fixtures; no build execution. */
    static Report assess(EclipseProjectModel.Snapshot snapshot,
            Iterable<M3EclipseBuilderProvider> installed) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(installed, "installed");
        Map<String, String> owners = new TreeMap<>();
        Map<String, Set<String>> natureProviders = new TreeMap<>();
        Set<String> providerIds = new TreeSet<>();
        try {
            for (M3EclipseBuilderProvider provider : installed) {
                if (provider == null || provider.apiVersion() != M3EclipseBuilderProvider.API_VERSION) {
                    throw new IOException("incompatible Eclipse builder-provider SPI version");
                }
                String id = provider.id();
                if (id == null || !id.matches("[a-z][a-z0-9.-]{0,63}")
                        || !providerIds.add(id)) {
                    throw new IOException("invalid or duplicated Eclipse adapter ID: " + id);
                }
                Set<String> builders = provider.builderIds();
                Set<String> natures = provider.natureIds();
                if (builders == null || natures == null || (builders.isEmpty() && natures.isEmpty())) {
                    throw new IOException("Eclipse adapter must declare builder IDs or nature IDs: " + id);
                }
                for (String builder : builders) {
                    checkExtensionId(builder);
                    String preceding = owners.putIfAbsent(builder, id);
                    if (preceding != null) {
                        throw new IOException("two Eclipse adapters claim builder " + builder + ": "
                                + preceding + ", " + id);
                    }
                }
                for (String nature : natures) {
                    checkExtensionId(nature);
                    natureProviders.computeIfAbsent(nature, ignored -> new TreeSet<>()).add(id);
                }
            }
        } catch (ServiceConfigurationError | RuntimeException failure) {
            throw new IOException("cannot inventory Eclipse builder providers", failure);
        }
        List<Binding> bound = new ArrayList<>();
        for (int i = 0; i < snapshot.buildCommands().size(); i++) {
            var command = snapshot.buildCommands().get(i);
            bound.add(new Binding(i, command.name(), command.triggers(), owners.get(command.name())));
        }
        List<NatureBinding> natureBindings = new ArrayList<>();
        for (int i = 0; i < snapshot.natureIds().size(); i++) {
            String nature = snapshot.natureIds().get(i);
            natureBindings.add(new NatureBinding(i, nature,
                    List.copyOf(natureProviders.getOrDefault(nature, Set.of()))));
        }
        List<String> unbound = natureBindings.stream()
                .filter(nature -> nature.providerIds().isEmpty()).map(NatureBinding::natureId).toList();
        return new Report(snapshot.name(), bound, natureBindings, unbound,
                List.copyOf(providerIds), snapshot.descriptorSha256());
    }

    private static void checkExtensionId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_.-]{0,254}")) {
            throw new IOException("invalid Eclipse builder/nature extension ID: " + id);
        }
    }

    private static Iterable<M3EclipseBuilderProvider> discover() {
        return ServiceLoader.load(M3EclipseBuilderProvider.class,
                M3EclipseBuilderRegistry.class.getClassLoader());
    }
}
