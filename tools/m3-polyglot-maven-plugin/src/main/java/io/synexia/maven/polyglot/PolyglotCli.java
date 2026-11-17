/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;
import java.time.Duration;
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
        boolean approveRewriteApply = false;
        boolean incremental = false;
        boolean moduleDb = false;
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
                case "--m3db" -> {
                    if (moduleDb || !"run".equals(args[0])) throw usage();
                    moduleDb = true;
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
        if (moduleDb && !incremental) throw usage();
        TaskManifest manifest = TaskManifest.load(Path.of(args[2]));
        var done = TaskRunner.run(manifest, Path.of(args[1]), args[3], "plan".equals(args[0]),
                Duration.ofSeconds(timeoutSeconds), approveRewriteApply, incremental, moduleDb, output);
        output.accept(("plan".equals(args[0]) ? "PLANNED " : "EXECUTED ") + done.size() + " targets");
        return done;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("usage: providers | plan|run <workspace> <manifest.tsv> <target>"
                + " [--timeout-seconds 1..43200] [--incremental] [--m3db] [--approve-rewrite-apply]"
                + " (incremental/recipe flags require run; m3db also requires incremental)");
    }
}
