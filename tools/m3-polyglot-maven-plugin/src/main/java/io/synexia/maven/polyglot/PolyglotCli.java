/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** No-dependency command-line entry for validating the same graph outside Maven. */
public final class PolyglotCli {
    private PolyglotCli() {}

    public static void main(String[] args) throws Exception {
        execute(args, System.out::println);
    }

    /**
     * The portable invocation contract shared with non-Maven hosts such as Gradle.
     * New flags are opt-in; the original four/five argument forms remain valid.
     */
    static List<String> execute(String[] args, Consumer<String> output) throws Exception {
        if (args != null && args.length == 1 && "providers".equals(args[0])) {
            output.accept("M3-POLYGLOT-PROVIDERS-V1");
            var providers = M3ToolProviders.available();
            for (M3ToolProviders.Descriptor provider : providers) {
                output.accept("provider\t" + provider.id() + "\t"
                        + String.join(",", provider.capabilities()) + "\t"
                        + String.join(",", provider.languages()));
            }
            return providers.stream().map(M3ToolProviders.Descriptor::id).toList();
        }
        if (args == null || args.length < 4 || !("plan".equals(args[0]) || "run".equals(args[0]))) {
            throw usage();
        }
        Path buildCache = buildCache(args);
        args = withoutBuildCache(args);
        boolean approveRewriteApply = false;
        boolean incremental = false;
        String stateBackend = "legacy";
        boolean stateBackendSupplied = false;
        boolean timeoutSupplied = false;
        int timeoutSeconds = 900;
        for (int index = 4; index < args.length; index++) {
            switch (args[index]) {
                case "--approve-rewrite-apply" -> {
                    if (approveRewriteApply || !"run".equals(args[0])) throw usage();
                    approveRewriteApply = true;
                }
                case "--incremental" -> {
                    if (incremental || !"run".equals(args[0])) throw usage();
                    incremental = true;
                }
                case "--state-backend" -> {
                    if (stateBackendSupplied || !"run".equals(args[0]) || ++index == args.length) throw usage();
                    stateBackendSupplied = true;
                    stateBackend = args[index];
                    if (!("legacy".equals(stateBackend) || "file".equals(stateBackend)
                            || "m3db".equals(stateBackend) || "sqlite".equals(stateBackend)
                            || "h2".equals(stateBackend) || stateBackend.matches("ext-[a-z0-9][a-z0-9-]{0,47}"))) {
                        throw usage();
                    }
                }
                case "--timeout-seconds" -> {
                    if (timeoutSupplied || ++index == args.length) throw usage();
                    timeoutSupplied = true;
                    try {
                        timeoutSeconds = Integer.parseInt(args[index]);
                    } catch (NumberFormatException invalid) {
                        throw usage();
                    }
                    if (timeoutSeconds < 1 || timeoutSeconds > 43_200) throw usage();
                }
                default -> throw usage();
            }
        }
        if (stateBackendSupplied && !incremental) throw usage();
        TaskManifest manifest = TaskManifest.load(Path.of(args[2]));
        var done = TaskRunner.run(manifest, Path.of(args[1]), args[3], "plan".equals(args[0]),
                Duration.ofSeconds(timeoutSeconds), approveRewriteApply, incremental, stateBackend, buildCache, output);
        output.accept(("plan".equals(args[0]) ? "PLANNED " : "EXECUTED ") + done.size() + " targets");
        return done;
    }

    /** The folder of the single optional {@code --build-cache <dir>} after the four positional arguments, or null. */
    private static Path buildCache(String[] args) {
        List<String> all = List.of(args);
        int index = all.indexOf("--build-cache");
        if (index < 0) return null;
        if (index < 4 || index + 1 >= args.length || args[index + 1].startsWith("--")
                || all.lastIndexOf("--build-cache") != index || !"run".equals(args[0])) {
            throw usage();
        }
        return Path.of(args[index + 1]);
    }

    private static String[] withoutBuildCache(String[] args) {
        int index = List.of(args).indexOf("--build-cache");
        if (index < 0) return args;
        List<String> rest = new ArrayList<>(List.of(args));
        rest.subList(index, index + 2).clear();
        return rest.toArray(String[]::new);
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("usage: providers | plan|run <workspace> <manifest.tsv> <target>"
                + " [--timeout-seconds 1..43200] [--incremental] [--state-backend legacy|file|m3db|sqlite|h2|ext-id]"
                + " [--build-cache <dir>] [--approve-rewrite-apply]"
                + " (--state-backend needs --incremental; it, --build-cache and the last flag require run)");
    }
}
