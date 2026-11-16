// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Hard canon gate for one change or inventory run (M3-SCALE v3.0 sections 4 to 7, 10, 13 and 15, and coding-agent
 * laws 3, 4, 9, 10 and 15). Atom: one run. Pattern: fixed stage pipeline with checkpoint and serial seal.
 *
 * <p>The gate validates a task packet, then runs the declared stages itself, in the fixed order diff, atom_pattern,
 * lint, compile, test, runtime. It stops at the first failing stage, refuses every working-tree change outside the
 * declared scope (and every change in dry-run mode), requires every changed file to be atomized and patternized,
 * extracts diagnostics from each stage log, and writes the mandatory run artifacts. There is no warning mode: every
 * violation fails the run, and the final report is rendered from recorded evidence only.
 *
 * <p>Usage: {@code check <packet.tsv>} or {@code run <packet.tsv> <run-dir> <root>}. Exit status 0 is a pass, 1 a
 * stage, scope or contract failure, 2 an invalid packet or invocation. Each stage command is written to
 * {@code logs/<n>-<stage>.sh} and run by {@code sh} (or the shell named by {@code CANON_SHELL}) in the repository
 * root, so quoting survives on every platform, with {@code CANON_RUN_DIR}, {@code CANON_ROOT},
 * {@code CANON_STAGE}, {@code CANON_MODE}, {@code CANON_WORKERS}, {@code CANON_SHARD_INDEX},
 * {@code CANON_SHARD_COUNT} and {@code CANON_SHARD_MANIFEST} set.
 */
public final class CanonGate {
    static final String VERSION = "canon-gate/1";

    private final Packet packet;
    private final Path runDir;
    private final Path root;
    private final Git git;
    private final PrintStream log;

    CanonGate(Packet packet, Path runDir, Path root, PrintStream log) {
        this.packet = packet;
        this.runDir = runDir.toAbsolutePath().normalize();
        this.root = root.toAbsolutePath().normalize();
        this.git = new Git(this.root);
        this.log = log;
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.err));
    }

    /**
     * Runs one gate command.
     *
     * @param args {@code check <packet>} or {@code run <packet> <run-dir> <root>}
     * @param log destination for violations and usage errors
     * @return 0 pass, 1 run failure, 2 invalid packet or invocation
     */
    public static int execute(String[] args, PrintStream log) {
        try {
            if (args.length == 2 && args[0].equals("check")) {
                new Packet(Path.of(args[1]));
                log.println("canon-gate: packet valid");
                return 0;
            }
            if (args.length == 4 && args[0].equals("run")) {
                return new CanonGate(new Packet(Path.of(args[1])), Path.of(args[2]), Path.of(args[3]), log).run();
            }
            log.println("usage: CanonGate check <packet.tsv> | run <packet.tsv> <run-dir> <root>");
            return 2;
        } catch (Packet.Invalid invalid) {
            log.println("canon-gate: PACKET_INVALID " + invalid.getMessage());
            return 2;
        } catch (IOException failure) {
            log.println("canon-gate: RUN_FAILED " + failure.getMessage());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.println("canon-gate: RUN_FAILED interrupted");
            return 1;
        }
    }

    int run() throws IOException, InterruptedException, Packet.Invalid {
        return run(Integer.MAX_VALUE);
    }

    /** Runs at most {@code stageLimit} new stages; a run stopped by the limit stays unsealed and resumable. */
    int run(int stageLimit) throws IOException, InterruptedException, Packet.Invalid {
        if (!Files.isDirectory(root)) throw new Packet.Invalid("root is not a directory: " + root);
        if (runDir.equals(root)) throw new Packet.Invalid("run directory must not be the repository root");
        if (runDir.startsWith(root) && !git.ignored(root.relativize(runDir).toString().replace('\\', '/'))) {
            throw new Packet.Invalid("run directory inside the repository must be git-ignored: " + runDir);
        }
        Files.createDirectories(runDir);
        Path lock = runDir.resolve(".lock");
        try {
            Files.createFile(lock);
        } catch (FileAlreadyExistsException busy) {
            throw new IOException("run directory is locked by a live or crashed runner: " + lock);
        }
        try {
            return locked(stageLimit);
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    private int locked(int stageLimit) throws IOException, InterruptedException {
        Path status = runDir.resolve("STATUS.tsv");
        RunState state = Files.exists(status) ? resume(status) : begin();
        int remaining = stageLimit;
        for (String stage : packet.verify) {
            if (state.failed) break;
            if (state.passed.contains(stage)) continue;
            if (remaining-- <= 0) return 1;
            stage(state, stage);
        }
        return seal(state) ? 0 : 1;
    }

    private RunState begin() throws IOException, InterruptedException {
        RunState state = new RunState();
        state.head = git.head();
        state.base = git.commit(packet.value("base_ref").isEmpty() ? "HEAD" : packet.value("base_ref"));
        state.preDirty = git.dirty();
        state.shard = packet.shard(git.files(packet.targetFiles, packet.targetDirs));
        Map<String, String> pre = Ledger.hashes(root, state.shard);
        state.preDigest = Ledger.digest(pre);
        state.signature = Ledger.sha256((VERSION + "\n" + packet.identity + "\n" + state.head + "\n" + state.base + "\n"
                + state.preDigest + "\n"
                + Ledger.digest(state.preDirty)).getBytes(StandardCharsets.UTF_8));
        Ledger.write(runDir, "PRE_HASH.tsv", Ledger.ledger(pre));
        Ledger.write(runDir, "PRE_DIRTY.tsv", Ledger.ledger(state.preDirty));
        Ledger.write(runDir, "SHARD_MANIFEST.txt", state.shard.stream().map(p -> p + "\n").collect(Collectors.joining()));
        Ledger.write(runDir, "RUN_CONTEXT.tsv", Report.runContext(packet, state, root, runDir));
        Ledger.write(runDir, "STATUS.tsv", "seq\tstage\tstate\texit\tdetail\tutc\n");
        status(state, "BEGIN", "PASS", 0, state.signature);
        return state;
    }

    private RunState resume(Path status) throws IOException, InterruptedException {
        List<String> rows = Files.readAllLines(status, StandardCharsets.UTF_8);
        RunState state = new RunState();
        for (String row : rows.subList(1, rows.size())) {
            String[] cell = row.split("\t", -1);
            if (cell[1].equals("SEAL")) throw new IOException("sealed run directory is immutable; use a new run directory");
            if (!Packet.ORDER.contains(cell[1])) continue;
            state.stages.add(new RunState.StageRun(cell[1], cell[2], Integer.parseInt(cell[3]), cell[4]));
            if (cell[2].equals("PASS")) state.passed.add(cell[1]);
            else state.failed = true;
        }
        Map<String, String> context = Ledger.table(runDir.resolve("RUN_CONTEXT.tsv"));
        if (!packet.identity.equals(context.get("canon.packet_identity"))) {
            throw new IOException("packet changed since the interrupted run started");
        }
        state.head = context.getOrDefault("canon.head", "");
        if (!state.head.equals(git.head())) throw new IOException("HEAD moved since the interrupted run started");
        state.base = context.getOrDefault("canon.base", "");
        state.signature = context.getOrDefault("canon.run_signature", "");
        state.preDirty = Ledger.table(runDir.resolve("PRE_DIRTY.tsv"));
        state.preDigest = Ledger.digest(Ledger.table(runDir.resolve("PRE_HASH.tsv")));
        state.shard = Files.readAllLines(runDir.resolve("SHARD_MANIFEST.txt"), StandardCharsets.UTF_8);
        state.seq = rows.size() - 1;
        status(state, "RESUME", "PASS", 0, "completed " + String.join(",", state.passed));
        return state;
    }

    private void stage(RunState state, String stage) throws IOException, InterruptedException {
        Files.createDirectories(runDir.resolve("logs"));
        String name = "logs/" + (Packet.ORDER.indexOf(stage) + 1) + "-" + stage;
        String logName = name + ".log";
        Path script = runDir.resolve(name + ".sh");
        Ledger.write(runDir, name + ".sh", packet.value("verify." + stage) + "\n");
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("CANON_SHELL", "sh"), slashes(script))
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(runDir.resolve(logName).toFile());
        Map<String, String> env = builder.environment();
        env.put("CANON_RUN_DIR", slashes(runDir));
        env.put("CANON_ROOT", slashes(root));
        env.put("CANON_STAGE", stage);
        env.put("CANON_MODE", packet.value("run.mode"));
        env.put("CANON_WORKERS", packet.value("run.workers"));
        env.put("CANON_SHARD_INDEX", packet.value("run.shard_index"));
        env.put("CANON_SHARD_COUNT", packet.value("run.shard_count"));
        env.put("CANON_SHARD_MANIFEST", slashes(runDir.resolve("SHARD_MANIFEST.txt")));
        Process process = builder.start();
        process.getOutputStream().close();
        int exit = process.waitFor();
        if (exit == 0 && stage.equals("atom_pattern")) {
            // Reject missing or malformed atom evidence before lint/compile/test.
            // Seal validates it again after later stages to catch source drift.
            List<String> evidenceFailures = new ArrayList<>();
            AtomPattern.check(root, runDir, atomCoverage(state, git.delta(state.base)),
                    packet.patterns, evidenceFailures);
            if (!evidenceFailures.isEmpty()) {
                exit = 1;
                evidenceFailures.forEach(v -> log.println("canon-gate: " + v));
            }
        }
        String result = exit == 0 ? "PASS" : "FAIL";
        state.stages.add(new RunState.StageRun(stage, result, exit, logName));
        if (exit == 0) state.passed.add(stage);
        else state.failed = true;
        status(state, stage, result, exit, logName);
    }

    /** Serial ratification: the only step that turns stage evidence into a verdict, written once per run. */
    private boolean seal(RunState state) throws IOException, InterruptedException {
        List<String> violations = new ArrayList<>();
        state.stages.stream().filter(s -> s.result().equals("FAIL"))
                .forEach(s -> violations.add("STAGE_FAILED " + s.stage() + " exit " + s.exit()));
        String headNow = git.head();
        if (!headNow.equals(state.head)) violations.add("HEAD_MOVED " + state.head + " -> " + headNow);
        Map<String, String> postDirty = git.dirty();
        TreeSet<String> mutated = new TreeSet<>(state.preDirty.keySet());
        mutated.addAll(postDirty.keySet());
        mutated.removeIf(p -> Objects.equals(state.preDirty.get(p), postDirty.get(p)));
        if (packet.dryRun) mutated.forEach(p -> violations.add("DRY_RUN_MUTATION " + p));
        Set<String> delta = git.delta(state.base);
        delta.forEach(p -> Scope.check(packet, p, violations));
        state.atoms = AtomPattern.check(root, runDir, atomCoverage(state, delta), packet.patterns, violations);
        Map<String, String> post = Ledger.hashes(root, state.shard);
        String postDigest = Ledger.digest(post);
        Ledger.write(runDir, "POST_HASH.tsv", Ledger.ledger(post));
        Ledger.write(runDir, "POST_DIRTY.tsv", Ledger.ledger(postDirty));
        String idempotence = Idempotence.check(packet, state, postDigest, violations);
        Map<String, int[]> counts = Diagnostics.extract(runDir, state.stages);
        if (packet.inventory) Inventory.write(root, runDir, state.shard);
        Ledger.write(runDir, "PROVENANCE.tsv", Report.provenance(packet, state, root, headNow, postDigest, idempotence));
        packet.artifacts.stream().filter(a -> !Report.GATE_WRITTEN.contains(a) && !Files.isRegularFile(runDir.resolve(a)))
                .forEach(a -> violations.add("OUTPUT_CONTRACT_MISSING " + a));
        Ledger.write(runDir, "VERIFY_CONTRACT.tsv", Report.verifyContract(packet, state, counts, violations));
        Ledger.write(runDir, "FINAL_REPORT.md", Report.finalReport(packet, state, runDir, delta, mutated, counts, violations));
        Ledger.write(runDir, "OUTPUT_CONTRACT.tsv", Report.outputContract(packet, runDir));
        status(state, "SEAL", violations.isEmpty() ? "PASS" : "FAIL", violations.size(), "violations " + violations.size());
        violations.forEach(v -> log.println("canon-gate: " + v));
        return violations.isEmpty();
    }

    /** Changed files are always mandatory; selected mode adds all tracked files in this shard. */
    private Set<String> atomCoverage(RunState state, Set<String> changed) {
        Set<String> required = new TreeSet<>(changed);
        if (packet.selectedAtomCoverage) required.addAll(state.shard);
        return required;
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void status(RunState state, String stage, String result, int exit, String detail) throws IOException {
        Ledger.append(runDir, "STATUS.tsv", ++state.seq + "\t" + stage + "\t" + result + "\t" + exit + "\t"
                + Ledger.clean(detail) + "\t" + Instant.now() + "\n");
    }
}
