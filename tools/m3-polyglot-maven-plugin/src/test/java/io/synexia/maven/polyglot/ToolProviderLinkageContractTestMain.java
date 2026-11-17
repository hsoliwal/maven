/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A provider built against a newer engine than the one loading it fails with a LinkageError (NoSuchFieldError for a
 * capability constant the engine lacks, AbstractMethodError, NoClassDefFoundError); a broken service file fails with
 * a ServiceConfigurationError. Registry, planning and command planning must turn every one into an IOException that
 * names the provider, never let it escape as an uncaught Error.
 */
public final class ToolProviderLinkageContractTestMain {
    private static int passed;

    private ToolProviderLinkageContractTestMain() {}

    /** A provider whose chosen method fails the way a version-mismatched class does. */
    static final class FailingProvider extends M3ToolKindsContractTestMain.LegacyProvider {
        private final String id;
        private final String failing;
        private final Supplier<Error> error;
        private final AtomicInteger languageCalls = new AtomicInteger();

        FailingProvider(String id, String failing, Supplier<Error> error) {
            this.id = id;
            this.failing = failing;
            this.error = error;
        }

        @Override public String id() { return id; }
        @Override public Set<M3ToolCapability> capabilities() { return fail("capabilities", super.capabilities()); }
        @Override public Set<String> languages() {
            return languageCalls.incrementAndGet() > 1 ? fail("languages-later", super.languages()) : super.languages();
        }
        @Override public List<String> command(M3ToolCapability role, String language, TaskManifest.Task task,
                Path workspace) {
            return fail("command", super.command(role, language, task, workspace));
        }
        @Override public M3ToolEffect effect(M3ToolCapability role, String language, TaskManifest.Task task) {
            return fail("effect", M3ToolEffect.READS_SOURCES);
        }
        @Override public Set<Integer> successExitCodes(M3ToolCapability role, String language, TaskManifest.Task task) {
            return fail("exits", Set.of(0));
        }

        private <T> T fail(String method, T value) {
            if (failing.equals(method)) throw error.get();
            return value;
        }
    }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("tool-provider-linkage-");
        serviceFileErrorIsRefused();
        capabilityLinkageIsRefused();
        planningLinkageIsRefused(root);
        laterDescriptionLinkageIsRefused(root);
        System.out.println("PASS: " + passed + " tool provider linkage cases");
        Files.delete(root);
    }

    private static void serviceFileErrorIsRefused() throws Exception {
        Iterable<M3ToolProvider> broken = () -> new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public M3ToolProvider next() { throw new ServiceConfigurationError("bad provider entry"); }
        };
        refuses(() -> M3ToolProviders.registry(broken), "a ServiceConfigurationError while loading", "providers");
        passed++;
    }

    private static void capabilityLinkageIsRefused() throws Exception {
        FailingProvider newer = new FailingProvider("newer", "capabilities",
                () -> new NoSuchFieldError("M3ToolCapability does not have member field DIFFER"));
        refuses(() -> M3ToolProviders.registry(List.of(newer)), "NoSuchFieldError in capabilities()",
                FailingProvider.class.getName());
        passed++;
    }

    private static void planningLinkageIsRefused(Path root) throws Exception {
        Map<String, M3ToolProvider> registry = M3ToolProviders.registry(List.of(
                new FailingProvider("cmd", "command", () -> new NoClassDefFoundError("org/example/Gone")),
                new FailingProvider("eff", "effect", () -> new AbstractMethodError("effect")),
                new FailingProvider("exits", "exits", () -> new IncompatibleClassChangeError("exits"))));
        planRefused(root, registry, "cmd");
        planRefused(root, registry, "eff");
        planRefused(root, registry, "exits");
    }

    private static void planRefused(Path root, Map<String, M3ToolProvider> registry, String id) throws Exception {
        refuses(() -> M3ToolProviders.plan(task("@m3:" + id + ":differ:text"), root, false, registry),
                "LinkageError while planning " + id, id);
        passed++;
    }

    private static void laterDescriptionLinkageIsRefused(Path root) throws Exception {
        Map<String, M3ToolProvider> registry = M3ToolProviders.registry(List.of(new FailingProvider("late",
                "languages-later", () -> new IncompatibleClassChangeError("languages"))));
        refuses(() -> M3ToolProviders.command(task("@m3:late:differ:text"), root, registry),
                "LinkageError while matching the request", "late");
        passed++;
    }

    private static TaskManifest.Task task(String tool) {
        return new TaskManifest.Task("t", tool, ".", List.of(), List.of(), List.of("a.txt"), List.of("out/a.patch"));
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    /** Must end in an IOException whose message names {@code named}; an Error escaping is a failure. */
    private static void refuses(Attempt attempt, String label, String named) throws Exception {
        try {
            attempt.run();
        } catch (IOException expected) {
            if (!String.valueOf(expected.getMessage()).contains(named)) {
                throw new AssertionError(label + ": the refusal must name " + named + ": " + expected.getMessage());
            }
            return;
        }
        throw new AssertionError("must refuse " + label);
    }
}
