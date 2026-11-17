// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Syntax-only fingerprints: source-bound AST cues, deterministic fan-in, and no equivalence authority. */
public final class MachineFingerprintTest {
    private static int checks;
    private MachineFingerprintTest() { }

    public static void main(String[] args) throws Exception {
        String baseline = "class Example { int f(int x) { String s=\"if(return) /*bogus*/\"; "
                + "/* if x == 0 */ return x + 1; } }\n";
        Fixture original = fixture(baseline, "Example.java", true);
        require(!CanonicalPreparationEvidence.inspect(original.base, original.source,
                original.base.resolve("receipts/preparation.json")).isEmpty(),
                "canonical preparation covers the two-file input");
        require(AtomPatternEvidence.inspect(original.base, original.source,
                original.base.resolve("synthetic-checkpoints.tsv")).javaFiles() == 2,
                "atom checkpoint producer covers the two-file input");
        require(original.run(1) == 0, "JDK21 AST producer consumes canonical Java evidence");
        Map<?, ?> receipt = original.receipt();
        require("syntax-only".equals(receipt.get("status")), "producer reports syntax-only authority");
        require(Boolean.FALSE.equals(receipt.get("physicalAtomizationExecuted"))
                && Boolean.FALSE.equals(receipt.get("semanticProofAuthenticated")),
                "fingerprints do not claim extraction or behavioral proof");
        require("JDK21_JAVAC_AST".equals(receipt.get("implementation")), "provenance identifies javac AST API");
        MachineEvidence.Summary checked = MachineEvidence.inspect(original.base, original.source,
                original.base.resolve("target/m3-smart/machine-evidence.tsv"));
        require(checked.files() == 2 && checked.atoms() == 2 && checked.json().contains("\"similarityIsProof\":false"),
                "both selected Java files have AST atoms bound to current source");
        Map<?, ?> originalCues = object(object(TinyJson.parse(checked.json())).get("staticCues"));
        require(Boolean.TRUE.equals(originalCues.get("available"))
                && Boolean.FALSE.equals(originalCues.get("typeAttributionExecuted"))
                && Boolean.FALSE.equals(originalCues.get("effectProofAuthenticated")),
                "control/effect masks report unattributed syntax-only authority");
        String first = original.tsv();
        require(first.startsWith(MachineEvidence.EXTENDED_HEADER + "\n") && first.contains("Example.java\t")
                && first.contains("Extra.java\t"), "source names and SHA-bound atoms have a stable sorted TSV");
        require(original.run(4) == 0 && first.equals(original.tsv()),
                "parallel file partition and deterministic fan-in produce byte-identical fingerprints");
        require(!original.receipt().toString().contains("bogus"), "raw string and comments stay outside JSON receipts");
        Files.writeString(original.base.resolve("recipes.tsv"), RecipeCatalog.HEADER
                + "\niteration-loop\tITERATION\tFILE\tcom.synexia.rewrite.IterationLoop\t" + "a".repeat(64) + "\n");
        List<String> nomination = List.of("--project-dir", original.base.toString(), "--build-dir", "target",
                "--source-root", "src", "--preparation-report", "receipts/preparation.json",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv", "--recipe-catalog", "recipes.tsv",
                "--machine-evidence", "target/m3-smart/machine-evidence.tsv");
        require(CodeAgentMain.run(nomination.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 0,
                "real JDK AST fingerprint ledger feeds the existing machine-oriented Maven coding agent");
        Map<?, ?> nominationReport = object(TinyJson.parse(Files.readString(
                original.base.resolve("target/m3-smart/code-agent.json"))));
        require(checked.inputSha256().equals(object(nominationReport.get("machineEvidenceFacts")).get("inputSha256"))
                        && MachineRulebook.load().sha256().equals(nominationReport.get("machineRulebookSha256")),
                "generated AST facts and law packet are bound into the model candidate evidence");

        Fixture patterns = fixture("class Example { int first(int n) { return n + 1; } "
                + "int second(int n) { return n + 1; } int third(int n) { return n + 1; } }\n",
                "Example.java", false);
        require(patterns.run(1) == 0, "atom patternization consumes real AST fingerprints");
        Map<?, ?> patternSummary = object(TinyJson.parse(MachineEvidence.inspect(patterns.base, patterns.source,
                patterns.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        List<?> candidates = list(patternSummary.get("patternCandidates"));
        require(candidates.size() == 3 && number(patternSummary.get("patternCandidatePairs")) == 3,
                "three repeated method bodies produce three bounded, ordered pairs");
        Map<?, ?> top = object(candidates.getFirst());
        require("LOGIC_EXACT".equals(top.get("kind")) && "a000001".equals(top.get("leftAtom"))
                && "a000002".equals(top.get("rightAtom")) && Boolean.FALSE.equals(top.get("contractSignatureMatches"))
                && "REVIEW_ONLY".equals(top.get("authority")),
                "exact logic match is only a review candidate when method contracts differ");
        require(!Boolean.TRUE.equals(patternSummary.get("patternCandidatesTruncated"))
                && Boolean.FALSE.equals(patternSummary.get("semanticProofAuthenticated")),
                "patternization never authenticates equivalence or promotion");
        String patternsFirst = patterns.tsv();
        require(patterns.run(4) == 0 && patternsFirst.equals(patterns.tsv()),
                "patternization stays stable when producer worker count changes");
        StringBuilder manyBodies = new StringBuilder("class Example { ");
        for (int i = 0; i < 9; i++)
            manyBodies.append("int m").append(i).append("(int n) { return n + 1; } ");
        Fixture crowded = fixture(manyBodies.append("}\n").toString(), "Example.java", false);
        require(crowded.run(4) == 0, "large exact-match patterns fit the bounded producer");
        Map<?, ?> crowdedSummary = object(TinyJson.parse(MachineEvidence.inspect(crowded.base, crowded.source,
                crowded.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        require(number(crowdedSummary.get("patternCandidatePairs")) == 36
                && list(crowdedSummary.get("patternCandidates")).size() == 24
                && Boolean.TRUE.equals(crowdedSummary.get("patternCandidatesTruncated")),
                "model receives at most 24 ranked review candidates with an honest truncation count");

        Fixture effects = fixture("class Example { int f(int n) { int sum=0; "
                + "for(int i=0;i<n;i++) { if(i%2==0) sum+=i; } "
                + "try { return Math.abs(sum); } catch(RuntimeException ex) { throw ex; } } "
                + "Object g() { synchronized(this) { return new Object(); } } "
                + "synchronized void h() {} }\n", "Example.java", false);
        require(effects.run(2) == 0, "JDK parser captures control-flow and effect-category cues");
        Map<?, ?> effectSummary = object(TinyJson.parse(MachineEvidence.inspect(effects.base, effects.source,
                effects.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        Map<?, ?> effectCues = object(effectSummary.get("staticCues"));
        require(number(effectCues.get("branchAtoms")) == 1
                && number(effectCues.get("loopAtoms")) == 1
                && number(effectCues.get("exceptionAtoms")) == 1
                && number(effectCues.get("unresolvedCallAtoms")) == 1,
                "branch, loop, try/throw and unresolved calls are categorized per atom");
        require(number(effectCues.get("potentialWriteAtoms")) == 1
                && number(effectCues.get("allocationAtoms")) == 1
                && number(effectCues.get("synchronizedAtoms")) == 2,
                "potential writes, allocations, synchronized blocks and synchronized methods stay advisory");
        String[] malformed = effects.tsv().split("\n", -1);
        String[] mask = malformed[1].split("\t", -1);
        mask[7] = "ffff";
        malformed[1] = String.join("\t", mask);
        Files.writeString(effects.base.resolve("target/m3-smart/machine-evidence.tsv"), String.join("\n", malformed));
        boolean rejectedMask = false;
        try {
            MachineEvidence.inspect(effects.base, effects.source,
                    effects.base.resolve("target/m3-smart/machine-evidence.tsv"));
        } catch (java.io.IOException expected) { rejectedMask = true; }
        require(rejectedMask, "reserved or forged static-cue bits fail closed");

        Fixture comment = fixture(baseline.replace("/* if x == 0 */", "/* return false; class Phantom {} */"),
                "Example.java", false);
        require(comment.run(1) == 0, "fake code in comments does not break structural fingerprinting");
        require(column(original, 3).equals(column(comment, 3)) && column(original, 4).equals(column(comment, 4)),
                "comment edits do not change AST structural or logic candidate hashes");
        Fixture literal = fixture(baseline.replace("if(return) /*bogus*/", "while(true) //fake"),
                "Example.java", false);
        require(literal.run(1) == 0, "fake code inside literals is parsed as a literal");
        require(column(original, 3).equals(column(literal, 3)) && !column(original, 4).equals(column(literal, 4))
                && column(original, 5).equals(column(literal, 5)),
                "literal value changes logic cue, not AST shape or kind-only SimHash");
        Fixture operator = fixture(baseline.replace("x + 1", "x - 1"), "Example.java", false);
        require(operator.run(1) == 0, "different operator parses as a different AST node");
        require(!column(original, 3).equals(column(operator, 3))
                && !column(original, 4).equals(column(operator, 4)),
                "operator changes alter both structural and logic hashes");
        Fixture rename = fixture(baseline.replace("f(int x)", "renamed(int x)"), "Example.java", false);
        require(rename.run(1) == 0, "method renaming remains a separate contract concern");
        require(column(original, 3).equals(column(rename, 3)) && column(original, 4).equals(column(rename, 4))
                && !column(original, 6).equals(column(rename, 6)),
                "renaming does not assert equivalence: method signature contract hash changes");
        Fixture unicode = fixture(baseline, "தமிழ்.java", false);
        require(unicode.run(2) == 0 && unicode.tsv().contains("தமிழ்.java\t"),
                "Unicode Java source filenames are supported without source-path guessing");

        Files.writeString(original.source.resolve("Example.java"), baseline + "// source changed\n");
        require(original.run(1) == 2 && "refused".equals(original.receipt().get("status")),
                "changed source refuses stale canonical preparation and atom manifest");
        require(original.tsv().equals("# m3.machine-evidence.refused\n"),
                "a refused regeneration invalidates its previous successful fingerprint ledger");
        Fixture invalid = fixture("class Example { int f( { return 1; } }\n", "Example.java", false);
        require(invalid.run(1) == 2 && "refused".equals(invalid.receipt().get("status")),
                "Java parser diagnostics refuse a misleading partial AST ledger");
        System.out.println("MACHINE FINGERPRINT TESTS PASS: " + checks + " (syntax-only)");
    }

    private static String column(Fixture fixture, int index) throws Exception {
        for (String row : fixture.tsv().split("\n"))
            if (row.startsWith("Example.java\t")) return row.split("\t", -1)[index];
        throw new AssertionError("Example.java atom not found");
    }

    private static Fixture fixture(String code, String file, boolean extra) throws Exception {
        Path project = Files.createTempDirectory("m3-ast-fingerprints-");
        Path source = Files.createDirectory(project.resolve("src"));
        Files.writeString(source.resolve(file), code);
        if (extra) Files.writeString(source.resolve("Extra.java"), "class Extra { int f(int n) { return n + 1; } }\n");
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        AtomPatternEvidenceTest.writeActionFixture(project, Path.of("src"));
        Path receipts = Files.createDirectory(project.resolve("receipts"));
        Files.writeString(receipts.resolve("preparation.json"),
                CanonicalPreparationEvidenceTestMain.report(SourceInventory.inspectSelected(project, source)));
        return new Fixture(project, source);
    }

    private record Fixture(Path base, Path source) {
        int run(int workers) {
            List<String> args = new ArrayList<>(List.of("--project-dir", base.toString(),
                    "--build-dir", "target", "--source-root", "src",
                    "--preparation-report", "receipts/preparation.json",
                    "--atom-pattern-manifest", "synthetic-checkpoints.tsv",
                    "--workers", Integer.toString(workers)));
            return MachineFingerprintMain.run(args.toArray(String[]::new),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        }
        String tsv() throws Exception { return Files.readString(base.resolve("target/m3-smart/machine-evidence.tsv")); }
        Map<?, ?> receipt() throws Exception {
            Object parsed = TinyJson.parse(Files.readString(base.resolve("target/m3-smart/machine-fingerprints.json")));
            if (!(parsed instanceof Map<?, ?> map)) throw new AssertionError("JSON receipt expected");
            return map;
        }
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Map<?, ?> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new AssertionError("JSON object expected");
        return map;
    }
    private static List<?> list(Object raw) {
        if (!(raw instanceof List<?> values)) throw new AssertionError("JSON array expected");
        return values;
    }
    private static int number(Object raw) { return Integer.parseInt(String.valueOf(raw)); }
}
