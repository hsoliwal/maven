// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Task packet contract (M3-SCALE v3.0 section 4). Atom: one validated packet. Pattern: validator over a value
 * object. A packet is UTF-8 text of {@code key<TAB>value} lines; blank lines and {@code #} comments are ignored;
 * unknown keys, repeated keys, weakened baseline locks and out-of-order verification stages are refused.
 */
final class Packet {
    /** The fixed verification order; atomization and patternization come right after the diff. */
    static final List<String> ORDER = List.of("diff", "atom_pattern", "lint", "compile", "test", "runtime");
    static final List<String> ALWAYS = List.of("diff", "atom_pattern", "lint", "compile");
    static final List<String> MANDATORY = List.of("STATUS.tsv", "FINAL_REPORT.md", "RUN_CONTEXT.tsv", "PROVENANCE.tsv",
            "VERIFY_CONTRACT.tsv", "OUTPUT_CONTRACT.tsv", "ATOM_PATTERN.tsv");
    static final List<String> INVENTORY = List.of("INVENTORY.tsv", "CLUSTERS_EXACT.tsv", "CLUSTERS_NORMALIZED.tsv",
            "ACTION_QUEUE.tsv", "SUMMARY.tsv");
    static final Map<String, String> BASELINE_LOCKS = new TreeMap<>(Map.of("PUBLIC_API", "LOCKED", "EXTERNAL_BEHAVIOR",
            "LOCKED", "DISABLED_PATHS", "FORBIDDEN", "EXCLUDED_PATHS", "FORBIDDEN", "BUILD_SCRIPTS", "LOCKED"));
    private static final List<String> REQUIRED = List.of("source_doc", "invariant_id", "run.mode", "run.profile",
            "run.workers", "run.shard_count", "run.shard_index", "invariant_locks", "allowed_delta", "forbidden",
            "verify_order", "output_contract", "stop_conditions", "pattern_catalog");
    private static final Set<String> KEYS = Stream.concat(Stream.of("task_packet", "source_doc", "source_doc_sha256",
            "invariant_id", "run.mode", "run.profile", "run.kind", "run.workers", "run.shard_count", "run.shard_index",
            "targets.modules", "targets.files", "invariant_locks", "allowed_delta", "forbidden", "verify_order",
            "output_contract", "stop_conditions", "excluded_paths", "disabled_paths", "compute_resources", "baseline_run",
            "base_ref", "pattern_catalog"),
            ORDER.stream().map(stage -> "verify." + stage)).collect(Collectors.toUnmodifiableSet());

    final Path file;
    final byte[] bytes;
    final Map<String, String> values = new TreeMap<>();
    final String identity;
    final boolean dryRun;
    final boolean inventory;
    final Path sourceDoc;
    final Set<String> patterns;
    final List<String> verify;
    final List<String> artifacts;
    final List<String> targetFiles;
    final List<String> targetDirs;
    final List<String> excluded;
    final List<String> disabled;

    Packet(Path path) throws IOException, Invalid {
        file = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new Invalid("packet not found: " + file);
        bytes = Files.readAllBytes(file);
        parse(new String(bytes, StandardCharsets.UTF_8));
        identity = Ledger.sha256(values.entrySet().stream().filter(e -> !e.getKey().equals("baseline_run"))
                .map(e -> e.getKey() + "\t" + e.getValue() + "\n").collect(Collectors.joining())
                .getBytes(StandardCharsets.UTF_8));
        if (!"active".equals(values.get("task_packet"))) throw new Invalid("task_packet must be active");
        for (String key : REQUIRED) if (value(key).isEmpty()) throw new Invalid("missing " + key);
        dryRun = oneOf("run.mode", "dry-run", "apply").equals("dry-run");
        String profile = oneOf("run.profile", "fast", "full", "custom");
        String kind = values.getOrDefault("run.kind", "change");
        if (!kind.equals("change") && !kind.equals("inventory")) throw new Invalid("run.kind must be change or inventory");
        inventory = kind.equals("inventory");
        if (inventory && !dryRun) throw new Invalid("inventory runs are proposal-only: run.mode must be dry-run");
        shards();
        sourceDoc = existing("source_doc");
        String pinned = value("source_doc_sha256");
        if (!pinned.isEmpty() && !pinned.equalsIgnoreCase(Ledger.sha256(sourceDoc))) {
            throw new Invalid("source_doc does not match source_doc_sha256");
        }
        if (!value("compute_resources").isEmpty()) existing("compute_resources");
        patterns = catalog(existing("pattern_catalog"));
        locks();
        verify = list("verify_order");
        stages(profile);
        artifacts = list("output_contract");
        contract();
        targetFiles = paths("targets.files");
        targetDirs = paths("targets.modules");
        if (targetFiles.isEmpty() && targetDirs.isEmpty()) throw new Invalid("targets.modules or targets.files is required");
        excluded = paths("excluded_paths");
        disabled = paths("disabled_paths");
    }

    String value(String key) {
        return values.getOrDefault(key, "");
    }

    Path resolve(String value) {
        return file.getParent().resolve(value).normalize();
    }

    boolean inScope(String path) {
        return targetFiles.contains(path) || targetDirs.stream().anyMatch(dir -> under(path, dir));
    }

    static boolean under(String path, String dir) {
        return dir.equals(".") || path.equals(dir) || path.startsWith(dir + "/");
    }

    /** Deterministic shard plan (section 6.2): file {@code i} of the sorted manifest belongs to shard {@code i % count}. */
    List<String> shard(List<String> manifest) {
        int count = Integer.parseInt(value("run.shard_count"));
        int index = Integer.parseInt(value("run.shard_index"));
        return IntStream.range(0, manifest.size()).filter(i -> i % count == index).mapToObj(manifest::get)
                .collect(Collectors.toList());
    }

    private void parse(String text) throws Invalid {
        String[] lines = text.split("\r?\n", -1);
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1];
            if (line.isBlank() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) throw new Invalid("line " + number + ": expected key<TAB>value");
            String key = line.substring(0, tab).strip();
            if (!KEYS.contains(key)) throw new Invalid("line " + number + ": unknown key " + key);
            if (values.put(key, line.substring(tab + 1).strip()) != null) throw new Invalid("line " + number + ": repeated key " + key);
        }
    }

    private String oneOf(String key, String... allowed) throws Invalid {
        String v = value(key);
        if (!Arrays.asList(allowed).contains(v)) throw new Invalid(key + " must be one of " + String.join(", ", allowed));
        return v;
    }

    private int number(String key) throws Invalid {
        try {
            return Integer.parseInt(value(key));
        } catch (NumberFormatException bad) {
            throw new Invalid(key + " must be an integer");
        }
    }

    private void shards() throws Invalid {
        int workers = number("run.workers");
        int count = number("run.shard_count");
        int index = number("run.shard_index");
        if (workers < 1 || count < 1 || index < 0 || index >= count) {
            throw new Invalid("need run.workers >= 1, run.shard_count >= 1 and 0 <= run.shard_index < run.shard_count");
        }
        if ((workers > 1 || count > 1) && value("compute_resources").isEmpty()) {
            throw new Invalid("distributed runs (workers or shards > 1) must name compute_resources");
        }
    }

    private Path existing(String key) throws Invalid {
        Path p = resolve(value(key));
        if (!Files.isRegularFile(p) || !Files.isReadable(p)) throw new Invalid(key + " is not a readable file: " + p);
        return p;
    }

    /** Admitted pattern ids: column 1 of each non-comment line (coding-agent law 4). */
    private static Set<String> catalog(Path file) throws IOException, Invalid {
        Set<String> ids = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split("\t", 2)[0].strip()).filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (ids.isEmpty()) throw new Invalid("pattern_catalog admits no pattern: " + file);
        if (ids.stream().anyMatch(id -> id.equalsIgnoreCase("UNKNOWN") || id.startsWith("RESIDUE:"))) {
            throw new Invalid("pattern_catalog must not admit UNKNOWN or RESIDUE ids");
        }
        return ids;
    }

    private void locks() throws Invalid {
        Map<String, String> locks = new TreeMap<>();
        for (String entry : list("invariant_locks")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) throw new Invalid("invariant lock must be NAME:STATE: " + entry);
            if (locks.put(entry.substring(0, colon), entry.substring(colon + 1)) != null) throw new Invalid("repeated lock " + entry);
        }
        for (Map.Entry<String, String> base : BASELINE_LOCKS.entrySet()) {
            if (!base.getValue().equals(locks.get(base.getKey()))) {
                throw new Invalid("invariant lock " + base.getKey() + " must be " + base.getValue());
            }
        }
    }

    private void stages(String profile) throws Invalid {
        int last = -1;
        for (String stage : verify) {
            int at = ORDER.indexOf(stage);
            if (at < 0) throw new Invalid("unknown verify stage " + stage);
            if (at <= last) throw new Invalid("verify_order must follow " + String.join(", ", ORDER) + " without repeats");
            last = at;
            if (value("verify." + stage).isEmpty()) throw new Invalid("missing verify." + stage);
        }
        for (String stage : ALWAYS) if (!verify.contains(stage)) throw new Invalid("verify_order must include " + stage);
        if (profile.equals("full") && !verify.contains("test")) throw new Invalid("the full profile must include test");
        for (String stage : ORDER) {
            if (!verify.contains(stage) && !value("verify." + stage).isEmpty()) {
                throw new Invalid("verify." + stage + " is declared but not in verify_order");
            }
        }
    }

    private void contract() throws Invalid {
        for (String name : artifacts) {
            if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                throw new Invalid("output_contract names plain files in the run directory: " + name);
            }
        }
        List<String> required = new ArrayList<>(MANDATORY);
        if (inventory) required.addAll(INVENTORY);
        for (String name : required) if (!artifacts.contains(name)) throw new Invalid("output_contract must include " + name);
    }

    private List<String> list(String key) {
        return Arrays.stream(value(key).split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private List<String> paths(String key) throws Invalid {
        List<String> out = new ArrayList<>();
        for (String raw : list(key)) {
            String p = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
            List<String> parts = Arrays.asList(p.split("/", -1));
            boolean bad = p.isEmpty() || p.startsWith("/") || p.contains("\\") || p.matches("[A-Za-z]:.*")
                    || (!p.equals(".") && (parts.contains("..") || parts.contains(".") || parts.contains("")));
            if (bad) throw new Invalid(key + " entries must be repository-relative paths: " + raw);
            out.add(p);
        }
        return out;
    }

    /** An invalid task packet or gate invocation. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }
}
