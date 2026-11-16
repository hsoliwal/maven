// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discriminating checks for the canon gate: every case fails when the gate stops enforcing the law it names.
 * Needs {@code git} and {@code sh} on the path; uses a throw-away repository under the system temporary directory.
 */
public final class CanonGateTest {
    private static Path base;
    private static Path repo;
    private static String lastLog = "";
    private static String planeMode = "full";
    private static int runs;
    private static int checks;

    public static void main(String[] args) throws Exception {
        base = Files.createTempDirectory("canon-gate-test");
        repo = base.resolve("repo");
        fixture();
        packetRules();
        dryRun();
        scope();
        failures();
        resume();
        diagnostics();
        atoms();
        inventory();
        idempotence();
        System.out.println("CanonGateTest: " + checks + " checks passed");
    }

    private static void fixture() throws Exception {
        write("src/A.java", "public final class A { }\n");
        write("src/Dup1.txt", "same text\n");
        write("src/Dup2.txt", "same text\n");
        write("src/Near.txt", "same   text\n");
        write("other/C.txt", "c\n");
        write("pom.xml", "<project/>\n");
        write("spec.md", "# spec\n");
        write(".gitignore", "runs/\n");
        sh("git init -q && git add -A && " + commit("fixture"));
        Files.writeString(base.resolve("catalog.tsv"), "# id\tfamily\nVALUE_OBJECT\tstructural\nBUILD_DESCRIPTOR\tbuild\n",
                StandardCharsets.UTF_8);
        Files.writeString(base.resolve("bad-catalog.tsv"), "UNKNOWN\tnone\n", StandardCharsets.UTF_8);
    }

    private static void packetRules() throws Exception {
        expect(0, check(packet()), "valid packet");
        Map<String, String> p = packet();
        p.put("invariant_locks", "PUBLIC_API:LOCKED,EXTERNAL_BEHAVIOR:LOCKED,DISABLED_PATHS:FORBIDDEN,EXCLUDED_PATHS:FORBIDDEN");
        expect(2, check(p), "baseline lock missing");
        p = packet();
        p.put("verify_order", "atom_pattern,diff,lint,compile");
        expect(2, check(p), "verify order inverted");
        p = packet();
        p.put("verify_order", "diff,lint,compile");
        p.remove("verify.atom_pattern");
        expect(2, check(p), "atomization stage omitted");
        p = packet();
        p.put("output_contract", "STATUS.tsv,FINAL_REPORT.md,RUN_CONTEXT.tsv,PROVENANCE.tsv,VERIFY_CONTRACT.tsv,OUTPUT_CONTRACT.tsv");
        expect(2, check(p), "atom table missing from output contract");
        p = packet();
        p.remove("pattern_catalog");
        expect(2, check(p), "no admitted pattern vocabulary");
        p = packet();
        p.put("pattern_catalog", base.resolve("bad-catalog.tsv").toString());
        expect(2, check(p), "catalog that admits UNKNOWN");
        p = packet();
        p.put("colour", "red");
        expect(2, check(p), "unknown key");
        p = packet();
        p.put("run.shard_count", "2");
        expect(2, check(p), "distributed run without compute resources");
        p = packet();
        p.put("source_doc_sha256", "0".repeat(64));
        expect(2, check(p), "source doc pin mismatch");
        p = packet();
        p.put("run.kind", "inventory");
        p.put("run.mode", "apply");
        expect(2, check(p), "inventory run in apply mode");
        p = packet();
        p.put("verify.test", "true");
        expect(2, check(p), "declared stage outside verify order");
        p = packet();
        p.put("run.profile", "full");
        expect(2, check(p), "full profile without tests");
    }

    private static void dryRun() throws Exception {
        expect(0, run(packet(), "dry-pass"), "clean dry run");
        Path dir = base.resolve("runs/dry-pass");
        for (String artifact : Packet.MANDATORY) present(dir.resolve(artifact), artifact);
        for (String heading : List.of("## Files changed", "## Exact delta summary", "## Exact verification executed",
                "## Exact blockers/failures", "## Artifact paths")) {
            contains(dir.resolve("FINAL_REPORT.md"), heading, "report section");
        }
        Map<String, String> p = packet();
        p.put("verify.compile", "echo x >> src/A.java");
        expect(1, run(p, "dry-mutation"), "dry run that mutates");
        contains(base.resolve("runs/dry-mutation/FINAL_REPORT.md"), "DRY_RUN_MUTATION src/A.java", "dry-run violation");
        restore();
    }

    private static void scope() throws Exception {
        append("src/A.java", "// edit\n");
        expect(0, run(apply(atoms("VALUE_OBJECT", "src/A.java")), "apply-in-scope"), "in-scope change");
        restore();
        append("other/C.txt", "edit\n");
        expect(1, run(apply(atoms("VALUE_OBJECT", "other/C.txt")), "apply-out-of-scope"), "out-of-scope change");
        contains(base.resolve("runs/apply-out-of-scope/FINAL_REPORT.md"), "OUT_OF_SCOPE other/C.txt", "scope violation");
        restore();
        append("pom.xml", "<!-- edit -->\n");
        Map<String, String> p = apply(atoms("BUILD_DESCRIPTOR", "pom.xml"));
        p.put("targets.modules", ".");
        expect(1, run(p, "build-script-locked"), "build script outside targets.files");
        contains(base.resolve("runs/build-script-locked/FINAL_REPORT.md"), "BUILD_SCRIPT_LOCKED pom.xml", "build lock");
        p.put("targets.files", "pom.xml");
        expect(0, run(p, "build-script-named"), "build script named in targets.files");
        restore();
        write("src/gen/G.java", "final class G { }\n");
        p = apply(atoms("VALUE_OBJECT", "src/gen/G.java"));
        p.put("excluded_paths", "src/gen");
        expect(1, run(p, "excluded"), "change under an excluded path");
        contains(base.resolve("runs/excluded/FINAL_REPORT.md"), "EXCLUDED_PATH src/gen/G.java", "excluded path");
        restore();
    }

    private static void failures() throws Exception {
        Map<String, String> p = packet();
        p.put("verify.lint", "exit 3");
        expect(1, run(p, "lint-fails"), "failing lint");
        contains(base.resolve("runs/lint-fails/STATUS.tsv"), "\tlint\tFAIL\t3\t", "failed stage recorded");
        contains(base.resolve("runs/lint-fails/VERIFY_CONTRACT.tsv"), "stage\tcompile\ttrue\tNOT_RUN", "later stage not run");
        expect(1, CanonGate.execute(new String[] {"run", base.resolve("packets/lint-fails.tsv").toString(),
                base.resolve("runs/lint-fails").toString(), repo.toString()}, quiet()), "sealed run directory reused");
        p = packet();
        p.put("verify.compile", commit("moved") + " --allow-empty");
        expect(1, run(p, "head-moved"), "commit during the run");
        contains(base.resolve("runs/head-moved/FINAL_REPORT.md"), "HEAD_MOVED", "head moved");
        sh("git reset -q --hard HEAD~1");
        expect(2, runAt(packet(), "inside", repo.resolve("other/run")), "run directory inside the repository, not ignored");
        expect(0, runAt(packet(), "ignored", repo.resolve("runs/ok")), "run directory inside the repository, ignored");
    }

    private static void resume() throws Exception {
        Path packetFile = save(packet(), "resume");
        Path dir = base.resolve("runs/resume");
        expect(1, new CanonGate(new Packet(packetFile), dir, repo, quiet()).run(2), "interrupted after two stages");
        expect(0, new CanonGate(new Packet(packetFile), dir, repo, quiet()).run(), "resumed run");
        String status = Files.readString(dir.resolve("STATUS.tsv"));
        if (!status.contains("\tRESUME\tPASS\t") || status.split("\tdiff\tPASS\t", -1).length != 2) {
            throw new AssertionError("resume replayed or skipped a stage:\n" + status);
        }
        checks++;
    }

    private static void diagnostics() throws Exception {
        Map<String, String> p = packet();
        p.put("verify.lint", "printf 'src/A.java:3:5: error: boom\\n[WARNING] src/A.java:[4,2] careful\\n'"
                + " && test \"a b\" = \"a b\"");
        expect(0, run(p, "diagnostics"), "diagnostics in a passing stage");
        contains(base.resolve("runs/diagnostics/BUILD_LOG.tsv"), "lint\tsrc/A.java\t3\t5\terror\tboom", "javac diagnostic");
        contains(base.resolve("runs/diagnostics/BUILD_LOG.tsv"), "lint\tsrc/A.java\t4\t2\twarning\tcareful", "maven diagnostic");
    }

    private static void atoms() throws Exception {
        append("src/A.java", "// edit\n");
        expect(1, run(apply(atoms("UNKNOWN", "src/A.java")), "pattern-unknown"), "unknown pattern");
        contains(base.resolve("runs/pattern-unknown/FINAL_REPORT.md"), "PATTERN_UNKNOWN src/A.java", "unknown pattern refused");
        expect(1, run(apply(atoms("INVENTED_THING", "src/A.java")), "pattern-not-admitted"), "pattern outside the catalog");
        contains(base.resolve("runs/pattern-not-admitted/FINAL_REPORT.md"), "PATTERN_NOT_ADMITTED src/A.java", "unadmitted pattern refused");
        String stale = atoms("VALUE_OBJECT", "src/A.java");
        append("src/A.java", "// later edit\n");
        expect(1, run(apply(stale), "atom-stale"), "stale atom row");
        contains(base.resolve("runs/atom-stale/FINAL_REPORT.md"), "ATOM_STALE src/A.java", "stale row refused");
        expect(1, run(apply(atoms("VALUE_OBJECT")), "atom-uncovered"), "changed file without atom row");
        contains(base.resolve("runs/atom-uncovered/FINAL_REPORT.md"), "ATOM_UNCOVERED src/A.java", "uncovered file refused");
        expect(0, run(apply(atoms("RESIDUE:novel-io", "src/A.java")), "atom-residue"), "typed residue");
        contains(base.resolve("runs/atom-residue/PROVENANCE.tsv"), "1 typed residue", "residue counted");
        planeMode = "none";
        expect(1, run(apply(atoms("VALUE_OBJECT", "src/A.java")), "planes-missing"), "Java atom without hash planes");
        contains(base.resolve("runs/planes-missing/FINAL_REPORT.md"), "ATOM_PLANES_MISSING src/A.java", "hash planes required");
        planeMode = "bad";
        expect(1, run(apply(atoms("VALUE_OBJECT", "src/A.java")), "planes-malformed"), "malformed hash plane");
        contains(base.resolve("runs/planes-malformed/FINAL_REPORT.md"), "ATOM_PATTERN_SCHEMA line 2", "plane format checked");
        planeMode = "twin";
        expect(0, run(apply(atoms("VALUE_OBJECT", "src/A.java")), "planes-cluster"), "two atoms on one logic hash");
        contains(base.resolve("runs/planes-cluster/ATOM_CLUSTERS.tsv"), "LOGIC\t", "logic-plane cluster listed");
        contains(base.resolve("runs/planes-cluster/ATOM_CLUSTERS.tsv"), AtomPattern.SIMILAR, "cluster is a proposal");
        contains(base.resolve("runs/planes-cluster/PROVENANCE.tsv"), "2 with all hash planes", "planes counted");
        planeMode = "full";
        Map<String, String> p = apply("true");
        expect(1, run(p, "atom-missing"), "atom stage without table");
        contains(base.resolve("runs/atom-missing/FINAL_REPORT.md"), "OUTPUT_CONTRACT_MISSING ATOM_PATTERN.tsv", "missing table");
        restore();
    }

    private static void inventory() throws Exception {
        Map<String, String> p = packet();
        p.put("run.kind", "inventory");
        p.put("output_contract", String.join(",", Packet.MANDATORY) + "," + String.join(",", Packet.INVENTORY));
        expect(0, run(p, "inventory"), "inventory run");
        Path dir = base.resolve("runs/inventory");
        contains(dir.resolve("CLUSTERS_EXACT.tsv"), "src/Dup1.txt", "exact cluster member");
        contains(dir.resolve("CLUSTERS_EXACT.tsv"), "src/Dup2.txt", "exact cluster member");
        contains(dir.resolve("CLUSTERS_NORMALIZED.tsv"), "src/Near.txt", "normalized cluster member");
        contains(dir.resolve("ACTION_QUEUE.tsv"), "P1\tEXACT", "exact action first");
        contains(dir.resolve("ACTION_QUEUE.tsv"), Inventory.SIMILAR, "similarity note");
        contains(dir.resolve("INVENTORY.tsv"), "src/A.java\tsrc\tA\t", "symbol summary");
    }

    private static void idempotence() throws Exception {
        Map<String, String> first = packet();
        expect(0, run(first, "repeat-1"), "first run");
        Map<String, String> p = new LinkedHashMap<>(first);
        p.put("baseline_run", base.resolve("runs/repeat-1").toString());
        expect(0, run(p, "repeat-2"), "repeat run");
        contains(base.resolve("runs/repeat-2/PROVENANCE.tsv"), "idempotence\tREPEAT_MATCH", "repeat matches");
        Path provenance = base.resolve("runs/repeat-1/PROVENANCE.tsv");
        String text = Files.readString(provenance);
        String digest = text.replaceAll("(?s).*post_hash_digest\t([0-9a-f]{64}).*", "$1");
        Files.writeString(provenance, text.replace("post_hash_digest\t" + digest, "post_hash_digest\t" + "f".repeat(64)));
        expect(1, run(p, "repeat-3"), "repeat with a different outcome");
        contains(base.resolve("runs/repeat-3/FINAL_REPORT.md"), "NON_IDEMPOTENT", "non-idempotent run refused");
    }

    private static Map<String, String> packet() throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("task_packet", "active");
        p.put("source_doc", repo.resolve("spec.md").toString());
        p.put("invariant_id", "CANON-TEST");
        p.put("run.mode", "dry-run");
        p.put("run.profile", "fast");
        p.put("run.workers", "1");
        p.put("run.shard_count", "1");
        p.put("run.shard_index", "0");
        p.put("targets.modules", "src");
        p.put("invariant_locks", "PUBLIC_API:LOCKED,EXTERNAL_BEHAVIOR:LOCKED,DISABLED_PATHS:FORBIDDEN,"
                + "EXCLUDED_PATHS:FORBIDDEN,BUILD_SCRIPTS:LOCKED");
        p.put("allowed_delta", "none");
        p.put("forbidden", "public API drift");
        p.put("verify_order", "diff,atom_pattern,lint,compile");
        p.put("verify.diff", "git diff --check");
        p.put("verify.atom_pattern", atoms("VALUE_OBJECT"));
        p.put("verify.lint", "true");
        p.put("verify.compile", "true");
        p.put("output_contract", String.join(",", Packet.MANDATORY));
        p.put("stop_conditions", "first failing stage");
        p.put("pattern_catalog", base.resolve("catalog.tsv").toString());
        return p;
    }

    private static Map<String, String> apply(String atomCommand) throws IOException {
        Map<String, String> p = packet();
        p.put("run.mode", "apply");
        p.put("verify.atom_pattern", atomCommand);
        return p;
    }

    /** Writes an atom table for the given files as they are now and returns the stage command that publishes it. */
    private static String atoms(String pattern, String... paths) throws IOException {
        StringBuilder table = new StringBuilder(AtomPattern.HEADER + "\n");
        for (String path : paths) {
            String file = Ledger.sha256(repo.resolve(path));
            String logic = Ledger.sha256(("logic " + path).getBytes(StandardCharsets.UTF_8));
            String planes = !path.endsWith(".java") || planeMode.equals("none") ? "-\t-\t-\t-"
                    : file + "\t" + logic + "\t" + file + "\t" + (planeMode.equals("bad") ? "xyz" : file.substring(0, 16));
            table.append(path).append('\t').append(file).append("\tleaf-").append(path.hashCode()).append('\t').append(pattern)
                    .append("\tModel\t").append(planes).append('\n');
            if (planeMode.equals("twin")) {
                table.append(path).append('\t').append(file).append("\ttwin-").append(path.hashCode()).append('\t').append(pattern)
                        .append("\tModel\t").append(planes).append('\n');
            }
        }
        Path file = base.resolve("atoms/" + (runs++) + ".tsv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, table.toString(), StandardCharsets.UTF_8);
        return "cp '" + file.toString().replace('\\', '/') + "' \"$CANON_RUN_DIR/ATOM_PATTERN.tsv\"";
    }

    private static int check(Map<String, String> packet) throws IOException {
        return CanonGate.execute(new String[] {"check", save(packet, "check-" + (runs++)).toString()}, quiet());
    }

    private static int run(Map<String, String> packet, String name) throws IOException {
        return runAt(packet, name, base.resolve("runs/" + name));
    }

    private static int runAt(Map<String, String> packet, String name, Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            status = CanonGate.execute(new String[] {"run", save(packet, name).toString(), dir.toString(), repo.toString()}, out);
        }
        lastLog = bytes.toString(StandardCharsets.UTF_8);
        return status;
    }

    private static Path save(Map<String, String> packet, String name) throws IOException {
        Path file = base.resolve("packets/" + name + ".tsv");
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder("# canon gate test packet\n");
        packet.forEach((k, v) -> text.append(k).append('\t').append(v).append('\n'));
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static String commit(String message) {
        return "git -c user.name=canon -c user.email=canon@example.invalid -c commit.gpgsign=false commit -qm " + message;
    }

    private static void restore() throws Exception {
        sh("git checkout -q -- . && git clean -fdq");
    }

    private static void write(String path, String text) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void append(String path, String text) throws IOException {
        Files.writeString(repo.resolve(path), Files.readString(repo.resolve(path)) + text, StandardCharsets.UTF_8);
    }

    private static void sh(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command).directory(repo.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("fixture command failed: " + command + "\n" + out);
    }

    private static void expect(int expected, int actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected status " + expected + ", got " + actual + "\n" + lastLog);
        checks++;
    }

    private static void present(Path file, String what) {
        if (!Files.isRegularFile(file)) throw new AssertionError(what + " missing: " + file);
        checks++;
    }

    private static void contains(Path file, String text, String what) throws IOException {
        String body = Files.isRegularFile(file) ? Files.readString(file) : "<missing>";
        if (!body.contains(text)) throw new AssertionError(what + ": " + file + " lacks [" + text + "]\n" + body + "\n" + lastLog);
        checks++;
    }
}
