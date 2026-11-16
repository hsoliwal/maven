/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.nio.file.Path;
import java.time.Duration;

/** No-dependency command-line entry for validating the same graph outside Maven. */
public final class PolyglotCli {
    private PolyglotCli() {}

    public static void main(String[] args) throws Exception {
        if ((args.length != 4 && args.length != 5) || !("plan".equals(args[0]) || "run".equals(args[0]))) {
            throw new IllegalArgumentException(
                    "usage: plan|run <workspace> <manifest.tsv> <target> [--approve-rewrite-apply]");
        }
        if (args.length == 5 && (!"run".equals(args[0]) || !"--approve-rewrite-apply".equals(args[4]))) {
            throw new IllegalArgumentException("only run accepts --approve-rewrite-apply");
        }
        TaskManifest manifest = TaskManifest.load(Path.of(args[2]));
        var done = TaskRunner.run(manifest, Path.of(args[1]), args[3], "plan".equals(args[0]),
                Duration.ofMinutes(15), args.length == 5, System.out::println);
        System.out.println(("plan".equals(args[0]) ? "PLANNED " : "EXECUTED ") + done.size() + " targets");
    }
}
