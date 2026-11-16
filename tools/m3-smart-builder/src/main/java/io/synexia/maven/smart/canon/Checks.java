// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in checks of the gate (coding-agent laws 9 and 10: a rule that was broken once becomes a mechanical
 * check). Atom: one check id. Pattern: registry; one id, one owner class, one violation prefix, one evidence table.
 *
 * <p>A packet names the checks it wants in the optional key {@code checks}; a packet without the key behaves as
 * before. The gate runs them in process right after the lint command passes, so a finding stops the run before
 * compile, and again at the seal, so a later stage cannot introduce what lint refused and every finding reaches the
 * final report under its own code. The same checks run on their own as
 * {@code <id> <root> <base-ref> <out-dir> [<name>...]}: exit status 0 no finding, 1 a finding, 2 a wrong invocation
 * or a git failure.
 */
final class Checks {
    /** Check id to violation prefix, in the order the checks run. */
    static final Map<String, String> PREFIXES = orderedPrefixes();
    static final String USAGE = " | <" + String.join("|", PREFIXES.keySet()) + "> <root> <base-ref> <out-dir> [<name>...]";
    private static final String NEEDS_NAMES = "text_refs";

    private Checks() {
    }

    private static Map<String, String> orderedPrefixes() {
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("stamp", StampCheck.PREFIX);
        prefixes.put("extension_points", ExtensionPoints.PREFIX);
        prefixes.put(NEEDS_NAMES, TextRefs.PREFIX);
        return Collections.unmodifiableMap(prefixes);
    }

    /** Why a packet's check list is refused, or empty when it is acceptable. */
    static String refusal(List<String> checks, List<String> renamed) {
        for (String id : checks) if (!PREFIXES.containsKey(id)) return "checks must name " + String.join(", ", PREFIXES.keySet()) + ": " + id;
        if (checks.stream().distinct().count() != checks.size()) return "checks must not repeat an id";
        boolean wanted = checks.contains(NEEDS_NAMES);
        if (wanted == renamed.isEmpty()) return "checks.renamed lists the retired names if and only if checks names " + NEEDS_NAMES;
        return renamed.stream().allMatch(name -> name.matches("[A-Za-z0-9_$.-]+")) ? "" : "checks.renamed entries are plain names";
    }

    /** Runs the packet's checks; appends their violations and returns their one-line summaries. */
    static String run(Packet packet, Git git, Path root, Path runDir, String base, List<String> violations)
            throws IOException, InterruptedException {
        List<String> summaries = new ArrayList<>();
        for (String id : PREFIXES.keySet()) {
            if (packet.checks.contains(id)) summaries.add(one(id, git, root, runDir, base, packet.renamed, violations));
        }
        return summaries.isEmpty() ? "none declared" : String.join("; ", summaries);
    }

    /** The stand-alone form: {@code <id> <root> <base-ref> <out-dir> [<name>...]}. */
    static int execute(String[] args, PrintStream log) throws InterruptedException {
        List<String> names = Arrays.asList(args).subList(Math.min(4, args.length), args.length);
        if (args.length < 4 || !refusal(List.of(args[0]), names).isEmpty() || !Files.isDirectory(Path.of(args[1]))) {
            log.println("usage: CanonGate" + USAGE.substring(2) + " (" + NEEDS_NAMES + " needs the retired names)");
            return 2;
        }
        List<String> violations = new ArrayList<>();
        try {
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Git git = new Git(root);
            Path out = Files.createDirectories(Path.of(args[3]));
            log.println("canon-gate: " + one(args[0], git, root, out, git.commit(args[2]), names, violations));
        } catch (IOException failure) {
            log.println("canon-gate: CHECK_FAILED " + failure.getMessage());
            return 2;
        }
        violations.forEach(v -> log.println("canon-gate: " + v));
        return violations.isEmpty() ? 0 : 1;
    }

    private static String one(String id, Git git, Path root, Path out, String base, List<String> names,
            List<String> violations) throws IOException, InterruptedException {
        return switch (id) {
            case "stamp" -> StampCheck.check(git, root, out, base, violations);
            case "extension_points" -> ExtensionPoints.check(git, root, out, base, violations);
            default -> TextRefs.check(git, root, out, names, violations);
        };
    }
}
