/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.maven;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.eclipse.aether.DefaultRepositoryCache;
import org.slf4j.Logger;

/**
 * Native Maven execution loop over the installed SDK's original M3File state.
 * Each callback enters the original Maven execution path and returns after its
 * session has closed. No Maven session, file payload, AST or second ledger is
 * retained here. Shared M3AST receipts currently have PARTIAL coverage, so every
 * changed generation conservatively executes clean followed by the caller's whole
 * lifecycle scope. Merely rerunning a timestamp-based compiler Mojo would not
 * establish that same-time content edits compiled or removed-source outputs vanished.
 */
final class M3ProjectWatch {
    private M3ProjectWatch() {}

    static boolean enabled(MavenExecutionRequest request) {
        return "true".equalsIgnoreCase(property(request, "m3.watch", "false"));
    }

    static MavenExecutionResult execute(
            MavenExecutionRequest original,
            Function<MavenExecutionRequest, MavenExecutionResult> executeOnce,
            Logger logger) {
        MavenExecutionResult result = new DefaultMavenExecutionResult();
        try {
            Options options = Options.from(original);
            if (original.getProjectBuildingRequest().getProject() != null) {
                throw new IllegalArgumentException("M3 watch cannot reload a preset embedding MavenProject");
            }
            MavenExecutionRequest prototype = DefaultMavenExecutionRequest.copy(original);
            // A fresh request and repository session are created for every generation.
            // Never call MavenInvoker repeatedly with its already-closed EventSpy.
            try (SharedWatch watch = SharedWatch.open(options)) {
                watch.requestBuild(); // A new process must validate its outputs and context.
                int completed = 0;
                Object startupSnapshot = null;
                long idleStarted = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    Optional<?> next = watch.awaitChange(Duration.ofSeconds(1));
                    if (next.isEmpty()) {
                        if (options.idleSeconds > 0
                                && System.nanoTime() - idleStarted
                                        >= Duration.ofSeconds(options.idleSeconds)
                                                .toNanos()) {
                            return result;
                        }
                        continue;
                    }
                    Object snapshot = next.orElseThrow();
                    if (startupSnapshot == null) {
                        startupSnapshot = snapshot;
                    } else if (watch.startupInputChanged(startupSnapshot, snapshot, options.timestampChanges)) {
                        throw new IOException("M3 watch: .mvn startup configuration changed; restart Maven");
                    }
                    watch.report(snapshot, options, completed == 0, logger);
                    MavenExecutionRequest request = DefaultMavenExecutionRequest.copy(prototype);
                    List<String> goals = new ArrayList<>(request.getGoals());
                    if (!goals.contains("clean")) {
                        goals.add(0, "clean");
                    }
                    request.setGoals(goals);
                    request.setRepositoryCache(new DefaultRepositoryCache());
                    // DefaultGraphBuilder binds the new live session before reading projects.
                    request.getProjectBuildingRequest().setRepositorySession(null);
                    result = executeOnce.apply(request);
                    completed++;
                    options.checkProjectPaths(result);
                    if (result.hasExceptions()) {
                        logger.warn("M3 watch generation {} failed; last successful file state retained", completed);
                    } else if (!watch.commit(snapshot)) {
                        logger.info(
                                "M3 watch generation {} changed during execution; newer work remains pending",
                                completed);
                    } else {
                        logger.info("M3 watch generation {} committed", completed);
                    }
                    idleStarted = System.nanoTime();
                    if (options.maxBuilds > 0 && completed >= options.maxBuilds) {
                        return result;
                    }
                }
                throw new InterruptedException("M3 watch interrupted");
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            result.addException(failure);
            return result;
        }
    }

    private static String property(MavenExecutionRequest request, String name, String fallback) {
        String value = request.getUserProperties().getProperty(name);
        if (value == null) {
            value = request.getSystemProperties().getProperty(name);
        }
        return value == null ? fallback : value;
    }

    private record Options(
            Path root,
            List<Path> inputs,
            List<Path> exclusions,
            List<String> ignoredNames,
            String mode,
            int fileThreshold,
            double percentThreshold,
            int reconcileSeconds,
            int maxBuilds,
            int idleSeconds,
            boolean timestampChanges) {
        static Options from(MavenExecutionRequest request) {
            Path root;
            try {
                root = request.getRootDirectory();
            } catch (IllegalStateException unavailable) {
                root = request.getTopDirectory();
            }
            if (root == null && request.getBaseDirectory() != null) {
                root = Path.of(request.getBaseDirectory());
            }
            if (root == null) {
                throw new IllegalArgumentException("M3 watch requires a project directory");
            }
            root = root.toAbsolutePath().normalize();
            if (request.getGoals().isEmpty()) {
                throw new IllegalArgumentException("M3 watch requires explicit goals, for example verify");
            }
            String mode = property(request, "m3.watch.mode", "AUTO").toUpperCase(Locale.ROOT);
            if (!List.of("AUTO", "FULL", "INCREMENTAL").contains(mode)) {
                throw new IllegalArgumentException("m3.watch.mode must be AUTO, FULL or INCREMENTAL");
            }
            int fileThreshold = integer(request, "m3.watch.fullFileCount", -1, -1, 1_000_000);
            double percent = Double.parseDouble(property(request, "m3.watch.fullPercent", "-1"));
            if (!Double.isFinite(percent) || (percent != -1 && (percent < 0 || percent > 100))) {
                throw new IllegalArgumentException("m3.watch.fullPercent must be -1 or within [0,100]");
            }
            List<Path> excluded = new ArrayList<>(paths(property(request, "m3.watch.excludes", "")));
            if (request.getLocalRepositoryPath() != null) {
                Path local = request.getLocalRepositoryPath()
                        .toPath()
                        .toAbsolutePath()
                        .normalize();
                if (local.startsWith(root)) {
                    excluded.add(local);
                }
            }
            List<String> ignored = names(property(request, "m3.watch.ignoredDirectoryNames", "target,.git"));
            for (String name : ignored) {
                if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
                    throw new IllegalArgumentException("ignored directory names must be single path components");
                }
            }
            String timestamps = property(request, "m3.watch.timestampChanges", "true");
            if (!timestamps.equalsIgnoreCase("true") && !timestamps.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("m3.watch.timestampChanges must be true or false");
            }
            List<Path> inputs = paths(property(request, "m3.watch.inputs", "."));
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("m3.watch.inputs must contain at least one path");
            }
            return new Options(
                    root,
                    inputs,
                    List.copyOf(excluded),
                    ignored,
                    mode,
                    fileThreshold,
                    percent,
                    integer(request, "m3.watch.reconcileSeconds", 30, 1, 86400),
                    integer(request, "m3.watch.maxBuilds", 0, 0, 1_000_000),
                    integer(request, "m3.watch.idleSeconds", 0, 0, 86400),
                    Boolean.parseBoolean(timestamps));
        }

        private static int integer(MavenExecutionRequest request, String name, int value, int min, int max) {
            int result = Integer.parseInt(property(request, name, Integer.toString(value)));
            if (result < min || result > max) {
                throw new IllegalArgumentException(name + " outside [" + min + "," + max + "]");
            }
            return result;
        }

        private static List<String> names(String value) {
            return Arrays.stream(value.split(",", -1))
                    .map(String::strip)
                    .filter(item -> !item.isEmpty())
                    .toList();
        }

        private static List<Path> paths(String value) {
            return names(value).stream().map(Path::of).toList();
        }

        private boolean excludes(Path path) {
            for (Path excluded : exclusions) {
                Path resolved = (excluded.isAbsolute() ? excluded : root.resolve(excluded)).normalize();
                if (path.startsWith(resolved)) {
                    return true;
                }
            }
            for (Path component : root.relativize(path)) {
                if (component.toString().equals(".m3-watch") || ignoredNames.contains(component.toString())) {
                    return true;
                }
            }
            return false;
        }

        private void checkProjectPaths(MavenExecutionResult result) throws IOException {
            if (result.getTopologicallySortedProjects() == null) {
                return;
            }
            for (var project : result.getTopologicallySortedProjects()) {
                List<String> sources = new ArrayList<>(project.getCompileSourceRoots());
                sources.addAll(project.getTestCompileSourceRoots());
                for (String source : sources) {
                    if (!Path.of(source).toAbsolutePath().normalize().startsWith(root)) {
                        throw new IOException("M3 watch source is outside its root: " + source);
                    }
                }
                if (project.getBuild() != null) {
                    for (String output : Arrays.asList(
                            project.getBuild().getDirectory(),
                            project.getBuild().getOutputDirectory(),
                            project.getBuild().getTestOutputDirectory())) {
                        if (output != null) {
                            Path path = Path.of(output).toAbsolutePath().normalize();
                            if (path.startsWith(root) && !excludes(path)) {
                                throw new IOException("M3 watch output requires m3.watch.excludes: " + path);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Optional SDK linkage; every state and decision operation delegates to its original owner. */
    private static final class SharedWatch implements AutoCloseable {
        private final Object watcher;
        private final Class<?> snapshotType;
        private final Class<?> planType;
        private final Class<?> modeType;
        private final Class<?> watcherType;

        private SharedWatch(Object watcher, Class<?> watcherType, Module module) throws ClassNotFoundException {
            this.watcher = watcher;
            this.watcherType = watcherType;
            snapshotType = sdkClass(module, "com.m3.file.M3FileDeltaLedger$Snapshot");
            planType = sdkClass(module, "com.m3.file.M3BuildImpactPlan");
            modeType = sdkClass(module, "com.m3.file.M3BuildImpactPlan$Mode");
        }

        static SharedWatch open(Options options) throws Exception {
            Module module = ModuleLayer.boot()
                    .findModule("com.m3.file")
                    .orElseThrow(() -> new IllegalStateException(
                            "m3.watch requires the installed M3JDK21 com.m3.file module (--add-modules com.m3.file)"));
            Class<?> type = sdkClass(module, "com.m3.file.M3FileWatcher");
            Predicate<Path> excluded = path -> {
                Path relative = options.root.relativize(path);
                for (Path component : relative) {
                    String name = component.toString();
                    if (name.equals(".m3-watch") || options.ignoredNames.contains(name)) {
                        return true;
                    }
                }
                return false;
            };
            Method open = type.getMethod(
                    "open",
                    Path.class,
                    Collection.class,
                    Collection.class,
                    Predicate.class,
                    Path.class,
                    Duration.class,
                    boolean.class);
            Object instance = invoke(
                    open,
                    null,
                    options.root,
                    options.inputs,
                    options.exclusions,
                    excluded,
                    options.root.resolve(".m3-watch/state.bin"),
                    Duration.ofSeconds(options.reconcileSeconds),
                    options.timestampChanges);
            try {
                return new SharedWatch(instance, type, module);
            } catch (Exception failure) {
                try {
                    invoke(type.getMethod("close"), instance);
                } catch (Exception close) {
                    failure.addSuppressed(close);
                }
                throw failure;
            }
        }

        void requestBuild() throws Exception {
            invoke(watcherType.getMethod("requestBuild"), watcher);
        }

        Optional<?> awaitChange(Duration wait) throws Exception {
            return (Optional<?>) invoke(watcherType.getMethod("awaitChange", Duration.class), watcher, wait);
        }

        boolean commit(Object snapshot) throws Exception {
            return (boolean) invoke(watcherType.getMethod("commit", snapshotType), watcher, snapshot);
        }

        boolean startupInputChanged(Object startup, Object current, boolean timestamps) throws Exception {
            return !sameStartupFiles(startup, current, timestamps) || !sameStartupFiles(current, startup, timestamps);
        }

        private boolean sameStartupFiles(Object left, Object right, boolean timestamps) throws Exception {
            int slots = (int) invoke(snapshotType.getMethod("slots"), left);
            Method live = snapshotType.getMethod("liveAt", int.class);
            Method pathAt = snapshotType.getMethod("pathAt", int.class);
            Method physicalSlot = snapshotType.getMethod("physicalSlot", Path.class);
            Method digest = snapshotType.getMethod("digestAt", int.class);
            Method modified = snapshotType.getMethod("lastModifiedAt", int.class);
            for (int slot = 0; slot < slots; slot++) {
                if (!(boolean) invoke(live, left, slot)) {
                    continue;
                }
                String path = (String) invoke(pathAt, left, slot);
                if (!path.startsWith(".mvn/")) {
                    continue;
                }
                int matching = (int) invoke(physicalSlot, right, Path.of(path));
                if (matching < 0
                        || !(boolean) invoke(live, right, matching)
                        || !Arrays.equals((byte[]) invoke(digest, left, slot), (byte[]) invoke(digest, right, matching))
                        || (timestamps && !invoke(modified, left, slot).equals(invoke(modified, right, matching)))) {
                    return false;
                }
            }
            return true;
        }

        void report(Object snapshot, Options options, boolean initial, Logger logger) throws Exception {
            // PARTIAL AST facts cannot certify processor, negative-lookup or output closure.
            Object plan = invoke(
                    planType.getMethod("compute", snapshotType, boolean.class, int[].class, int[].class, byte[].class),
                    null,
                    snapshot,
                    false,
                    null,
                    null,
                    null);
            String requested = initial ? "FULL" : options.mode;
            Object mode = Arrays.stream(modeType.getEnumConstants())
                    .filter(value -> value.toString().equals(requested))
                    .findFirst()
                    .orElseThrow();
            Object selected = invoke(
                    planType.getMethod("select", snapshotType, modeType, int.class, double.class),
                    plan,
                    snapshot,
                    mode,
                    options.fileThreshold,
                    options.percentThreshold);
            Class<?> selection = selected.getClass();
            logger.info(
                    "M3 watch: {} ({}) files={} sources={} changed={} affected={} selected={}; clean + lifecycle"
                            + " fallback",
                    invoke(selection.getMethod("decision"), selected),
                    invoke(selection.getMethod("reason"), selected),
                    invoke(selection.getMethod("totalLiveFiles"), selected),
                    invoke(selection.getMethod("totalLiveSources"), selected),
                    invoke(selection.getMethod("directChangedFiles"), selected),
                    invoke(selection.getMethod("affectedSourceCount"), selected),
                    invoke(selection.getMethod("selectedSourceCount"), selected));
        }

        @Override
        public void close() throws IOException {
            try {
                invoke(watcherType.getMethod("close"), watcher);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Could not close M3 file watcher", failure);
            }
        }

        private static Class<?> sdkClass(Module module, String name) throws ClassNotFoundException {
            Class<?> type = Class.forName(module, name);
            if (type == null) {
                throw new ClassNotFoundException(name + " is unavailable in the installed com.m3.file module");
            }
            return type;
        }

        private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
            try {
                return method.invoke(receiver, args);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }
    }
}
