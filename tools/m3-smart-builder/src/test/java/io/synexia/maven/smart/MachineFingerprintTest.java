// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;

/** Source-bound AST, compiler and may-flow facts; deterministic fan-in, never equivalence authority. */
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
        require("auto".equals(object(receipt.get("moduleDatabase")).get("mode")),
                "existing callers select automatic module database mode");
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
                "--machine-evidence", "target/m3-smart/machine-evidence.tsv",
                "--require-machine-evidence", "true");
        require(CodeAgentMain.run(nomination.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 0,
                "strict coding agent admits a freshly generated, source-bound Java 21 AST ledger");
        Map<?, ?> nominationReport = object(TinyJson.parse(Files.readString(
                original.base.resolve("target/m3-smart/code-agent.json"))));
        require(checked.inputSha256().equals(object(nominationReport.get("machineEvidenceFacts")).get("inputSha256"))
                        && MachineRulebook.load().sha256().equals(nominationReport.get("machineRulebookSha256")),
                "generated AST facts and law packet are bound into the model candidate evidence");
        List<String> missingLedger = new ArrayList<>(nomination);
        int ledgerOption = missingLedger.indexOf("--machine-evidence");
        missingLedger.remove(ledgerOption + 1);
        missingLedger.remove(ledgerOption);
        require(CodeAgentMain.run(missingLedger.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 2,
                "strict nomination refuses to proceed when the machine ledger is omitted");
        require("refused".equals(object(TinyJson.parse(Files.readString(
                original.base.resolve("target/m3-smart/code-agent.json")))).get("status")),
                "refusal replaces any previous successful nomination receipt");
        require(CodeAgentMain.run(nomination.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 0,
                "reinstating the unchanged ledger deterministically recovers the strict nomination");

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

        Fixture attributed = fixture("class Example { int f(int n) { return Math.abs(n) + 1; } }\n",
                "Example.java", false);
        require(attributed.run(2, true) == 0, "opt-in Java21 compiler symbol attribution succeeds for a resolved fixture");
        require(attributed.tsv().startsWith(MachineEvidence.ATTRIBUTED_HEADER + "\n"),
                "compiler-resolved rows extend the existing syntax-ledger schema additively");
        Map<?, ?> compiled = object(TinyJson.parse(MachineEvidence.inspect(attributed.base, attributed.source,
                attributed.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        Map<?, ?> calls = object(compiled.get("attributionFacts"));
        require(Boolean.TRUE.equals(calls.get("available"))
                && number(calls.get("resolvedCallSites")) == 1
                && number(calls.get("unresolvedCallSites")) == 0,
                "compiler-resolved method calls are counted without inventing unresolved targets");
        require(Boolean.FALSE.equals(calls.get("dynamicDispatchProven"))
                && Boolean.FALSE.equals(calls.get("effectProofAuthenticated"))
                && Boolean.TRUE.equals(attributed.receipt().get("typeAttributionExecuted"))
                && "statically-attributed".equals(attributed.receipt().get("status")),
                "type attribution cannot imply dynamic dispatch or effect proof");
        String attributedFirst = attributed.tsv();
        require(attributed.run(1, true) == 0 && attributedFirst.equals(attributed.tsv()),
                "compiler attribution remains deterministic when AST worker count changes");
        Files.writeString(attributed.base.resolve("recipes.tsv"), RecipeCatalog.HEADER
                + "\niteration-loop\tITERATION\tFILE\tcom.synexia.rewrite.IterationLoop\t" + "a".repeat(64) + "\n");
        List<String> attributedNomination = List.of("--project-dir", attributed.base.toString(),
                "--build-dir", "target", "--source-root", "src",
                "--preparation-report", "receipts/preparation.json",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv", "--recipe-catalog", "recipes.tsv",
                "--machine-evidence", "target/m3-smart/machine-evidence.tsv");
        require(CodeAgentMain.run(attributedNomination.toArray(String[]::new),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 0,
                "compiler-resolved evidence reaches the existing Maven coding agent without model inference");
        Map<?, ?> attributedReport = object(TinyJson.parse(Files.readString(
                attributed.base.resolve("target/m3-smart/code-agent.json"))));
        require(Boolean.TRUE.equals(object(object(attributedReport.get("machineEvidenceFacts"))
                .get("attributionFacts")).get("available")),
                "type and method-call evidence is bound to the code-agent nomination receipt");
        Fixture crossFile = fixture("class Example { int f(int n) { return new Extra().f(n); } }\n",
                "Example.java", true);
        require(crossFile.run(4, true) == 0, "attribution resolves symbols across the selected Java source files");
        Map<?, ?> crossSummary = object(TinyJson.parse(MachineEvidence.inspect(crossFile.base, crossFile.source,
                crossFile.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        require(number(object(crossSummary.get("attributionFacts")).get("resolvedCallSites")) == 1,
                "compiler resolves a same-project method call without an external classpath");
        String[] attributedRows = attributed.tsv().split("\n", -1);
        String[] forgedCalls = attributedRows[1].split("\t", -1);
        forgedCalls[9] = "99999";
        attributedRows[1] = String.join("\t", forgedCalls);
        Files.writeString(attributed.base.resolve("target/m3-smart/machine-evidence.tsv"),
                String.join("\n", attributedRows));
        boolean rejectedCalls = false;
        try {
            MachineEvidence.inspect(attributed.base, attributed.source,
                    attributed.base.resolve("target/m3-smart/machine-evidence.tsv"));
        } catch (java.io.IOException expected) { rejectedCalls = true; }
        require(rejectedCalls, "out-of-budget compiler call counts fail closed");
        Fixture flow = fixture("class Example { int f(int n) { int x=0; "
                + "if(n>0) { x=n; } else { x=1; } "
                + "while(x<10) { x++; } return x; } }\n", "Example.java", false);
        require(flow.run(1, false, true) == 2
                && "refused".equals(flow.receipt().get("status")),
                "flow facts refuse without compiler type attribution");
        require(flow.run(1, true, true) == 0
                && flow.tsv().startsWith(MachineEvidence.FLOW_HEADER + "\n"),
                "compiler-symbol flow analysis extends attributed rows additively");
        Map<?, ?> flowSummary = object(TinyJson.parse(MachineEvidence.inspect(flow.base, flow.source,
                flow.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        Map<?, ?> flowFacts = object(flowSummary.get("flowFacts"));
        require(Boolean.TRUE.equals(flowFacts.get("available"))
                && number(flowFacts.get("cfgNodes")) >= 8
                && number(flowFacts.get("cfgEdges")) >= 9,
                "branch and loop graph contains more than syntax-only bit cues");
        require(number(flowFacts.get("localReadSites")) >= 4
                && number(flowFacts.get("localWriteSites")) >= 4
                && number(flowFacts.get("mayReachingDefinitionLinks")) >= 4,
                "compiler-resolved local parameters and assignments reach branch/loop reads");
        require(Boolean.FALSE.equals(flowFacts.get("cfgIsComplete"))
                && Boolean.FALSE.equals(flowFacts.get("reachingDefinitionsAreProof"))
                && Boolean.FALSE.equals(flow.receipt().get("completeCompilerCfgProven"))
                && "may-flow-analyzed".equals(flow.receipt().get("status")),
                "may-flow CFG is never represented as compiler semantic equivalence");
        String flowFirst = flow.tsv();
        require(flow.run(4, true, true) == 0 && flowFirst.equals(flow.tsv()),
                "flow hashes and reaching-definition links are worker-order independent");
        Files.writeString(flow.base.resolve("recipes.tsv"), RecipeCatalog.HEADER
                + "\niteration-loop\tITERATION\tFILE\tcom.synexia.rewrite.IterationLoop\t" + "a".repeat(64) + "\n");
        require(CodeAgentMain.run(new String[] {"--project-dir", flow.base.toString(),
                "--build-dir", "target", "--source-root", "src",
                "--preparation-report", "receipts/preparation.json",
                "--atom-pattern-manifest", "synthetic-checkpoints.tsv", "--recipe-catalog", "recipes.tsv",
                "--machine-evidence", "target/m3-smart/machine-evidence.tsv",
                "--require-machine-evidence", "true"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())) == 0,
                "the existing strict coding agent accepts fresh compiler-flow evidence");
        Map<?, ?> flowAgent = object(TinyJson.parse(Files.readString(
                flow.base.resolve("target/m3-smart/code-agent.json"))));
        require(Boolean.TRUE.equals(object(object(flowAgent.get("machineEvidenceFacts"))
                .get("flowFacts")).get("available")),
                "flow analysis is bound to the model's source-and-policy receipt");
        Fixture diamond = fixture("class Example { int f(int n) { int x; "
                + "if(n>0) { x=1; } else { x=2; } return x; } }\n", "Example.java", false);
        require(diamond.run(1, true, true) == 0,
                "diamond branch has compiler-resolved local symbols");
        Map<?, ?> diamondFacts = object(object(TinyJson.parse(MachineEvidence.inspect(diamond.base,
                diamond.source, diamond.base.resolve("target/m3-smart/machine-evidence.tsv")).json()))
                .get("flowFacts"));
        require(number(diamondFacts.get("mayReachingDefinitionLinks")) == 3,
                "branch join carries two possible writes to x plus one parameter read");
        Fixture loopBack = fixture("class Example { int f(int n) { int x=0; "
                + "while(x<n) { x++; } return x; } }\n", "Example.java", false);
        require(loopBack.run(1, true, true) == 0,
                "loop backedge reaches its own local increment");
        Map<?, ?> loopFacts = object(object(TinyJson.parse(MachineEvidence.inspect(loopBack.base,
                loopBack.source, loopBack.base.resolve("target/m3-smart/machine-evidence.tsv")).json()))
                .get("flowFacts"));
        require(number(loopFacts.get("mayReachingDefinitionLinks")) == 7,
                "fixed point retains both initial and loop-carried definitions of x");
        Fixture flowPatterns = fixture("class Example { int first(int n) { return n+1; } "
                + "int second(int n) { return n+1; } }\n", "Example.java", false);
        require(flowPatterns.run(2, true, true) == 0,
                "repeated Java methods can be ranked with opt-in CFG and local-access facts");
        Map<?, ?> flowPairs = object(TinyJson.parse(MachineEvidence.inspect(flowPatterns.base,
                flowPatterns.source, flowPatterns.base.resolve("target/m3-smart/machine-evidence.tsv")).json()));
        Map<?, ?> matchingFlow = object(list(flowPairs.get("patternCandidates")).getFirst());
        require(Boolean.TRUE.equals(matchingFlow.get("mayFlowCompared"))
                && Boolean.TRUE.equals(matchingFlow.get("mayFlowFingerprintMatches"))
                && Boolean.TRUE.equals(matchingFlow.get("localDefUseFingerprintMatches"))
                && Boolean.FALSE.equals(matchingFlow.get("contractSignatureMatches"))
                && "REVIEW_ONLY".equals(matchingFlow.get("authority")),
                "matching advisory flow fingerprints do not override different public signatures");
        Fixture partial = fixture("class Example { int f(int n) { try { return n+1; } "
                + "finally { System.out.println(n); } } }\n", "Example.java", false);
        require(partial.run(2, true, true) == 0, "supported compiler input with finally still emits advisory flow");
        Map<?, ?> partialFacts = object(object(TinyJson.parse(MachineEvidence.inspect(partial.base,
                partial.source, partial.base.resolve("target/m3-smart/machine-evidence.tsv")).json()))
                .get("flowFacts"));
        require(number(partialFacts.get("incompleteAtoms")) > 0,
                "finally/exception effects remain explicitly unmodelled typed residue");
        String[] forgedFlowRows = flow.tsv().split("\n", -1);
        String[] forgedFlow = forgedFlowRows[1].split("\t", -1);
        forgedFlow[20] = "ffff";
        forgedFlowRows[1] = String.join("\t", forgedFlow);
        Files.writeString(flow.base.resolve("target/m3-smart/machine-evidence.tsv"),
                String.join("\n", forgedFlowRows));
        boolean rejectedFlow = false;
        try {
            MachineEvidence.inspect(flow.base, flow.source,
                    flow.base.resolve("target/m3-smart/machine-evidence.tsv"));
        } catch (java.io.IOException expected) { rejectedFlow = true; }
        require(rejectedFlow, "reserved unproven flow-mask bits refuse ledger admission");
        forgedFlowRows = flowFirst.split("\n", -1);
        forgedFlow = forgedFlowRows[1].split("\t", -1);
        forgedFlow[13] = "999";
        forgedFlowRows[1] = String.join("\t", forgedFlow);
        Files.writeString(flow.base.resolve("target/m3-smart/machine-evidence.tsv"),
                String.join("\n", forgedFlowRows));
        boolean rejectedBudget = false;
        try {
            MachineEvidence.inspect(flow.base, flow.source,
                    flow.base.resolve("target/m3-smart/machine-evidence.tsv"));
        } catch (java.io.IOException expected) { rejectedBudget = true; }
        require(rejectedBudget, "fabricated excessive graph sizes refuse evidence admission");
        Fixture unresolved = fixture("class Example { MissingType f(MissingType x) { return x; } }\n",
                "Example.java", false);
        require(unresolved.run(1) == 0 && unresolved.run(1, true) == 2
                && "refused".equals(unresolved.receipt().get("status"))
                && unresolved.tsv().equals("# m3.machine-evidence.refused\n"),
                "unknown dependencies allow syntax guidance but refuse an attributed evidence claim");

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
        immutableCompilerSnapshot();
        moduleDatabaseModes(baseline);
        mojoModuleDatabaseModes(baseline);
        System.out.println("MACHINE FINGERPRINT TESTS PASS: " + checks + " (no semantic proof)");
    }

    /** Exercise the actual parser with retained bytes while the original path changes A -> B -> A. */
    private static void immutableCompilerSnapshot() throws Exception {
        String original = "class SnapshotExample { int f(int x) { return x + 1; } }\n";
        String changed = original.replace("+ 1", "+ 2");
        Fixture fixture = fixture(original, "SnapshotExample.java", false);
        require(fixture.run(1, false, false, "off") == 0, "snapshot regression has original compiler rows");
        List<String> expected = fixture.tsv().lines().skip(1).toList();
        SourceInventory.FileFact fact = SourceInventory.inspectSelected(fixture.base, fixture.source).files().getFirst();
        Path path = fixture.source.resolve(fact.path());
        Method capture = MachineFingerprintMain.class.getDeclaredMethod(
                "snapshotFile", Path.class, SourceInventory.FileFact.class);
        Method parse = MachineFingerprintMain.class.getDeclaredMethod(
                "fingerprintSnapshot", JavaFileObject.class, SourceInventory.FileFact.class);
        capture.setAccessible(true);
        parse.setAccessible(true);
        JavaFileObject snapshot = (JavaFileObject) capture.invoke(null, path, fact);
        require(snapshot.toUri().equals(path.toUri()) && snapshot.getCharContent(false).toString().equals(original),
                "compiler snapshot preserves exact admitted text and original source URI");
        try {
            Files.writeString(path, changed);
            require(Files.size(path) == fact.bytes() && snapshot.getCharContent(false).toString().equals(original),
                    "same-size live-path mutation cannot change captured compiler text");
            require(expected.equals(parse.invoke(null, snapshot, fact)),
                    "actual javac fingerprint rows remain bound to A while the live source contains B");
            snapshotRefused(capture, path, fact, "mismatched source hash refuses capture before javac");
        } finally {
            Files.writeString(path, original);
        }
        require(Hashes.sha256(path).equals(fact.sha256()) && expected.equals(parse.invoke(null, snapshot, fact)),
                "restoring live A cannot cause intermediate B rows to be published under A's hash");
        snapshotRefused(capture, path, new SourceInventory.FileFact(fact.path(), fact.sha256(), fact.bytes() + 1),
                "mismatched source byte count refuses capture before javac");
    }

    private static void snapshotRefused(Method capture, Path path, SourceInventory.FileFact fact, String message)
            throws Exception {
        try {
            capture.invoke(null, path, fact);
            throw new AssertionError(message);
        } catch (InvocationTargetException refused) {
            require(refused.getCause() instanceof IOException, message);
        }
    }

    private static void moduleDatabaseModes(String code) throws Exception {
        Fixture fixture = fixture(code, "Example.java", true);
        require(fixture.run(2, false, false, "off") == 0,
                "explicit database off mode executes the original compiler path");
        String expected = fixture.tsv();
        Map<?, ?> disabled = object(fixture.receipt().get("moduleDatabase"));
        require("off".equals(disabled.get("mode")) && "off".equals(disabled.get("status"))
                && number(disabled.get("hits")) == 0 && number(disabled.get("filesComputed")) == 2,
                "off mode reports two real computations and no reuse");
        require(fixture.run(4, false, false, "off") == 0 && expected.equals(fixture.tsv()),
                "disabled database keeps serial and parallel output byte-identical");
        for (String mode : List.of("", "AUTO", "false", "memory")) {
            require(fixture.run(1, false, false, mode) == 2
                    && "refused".equals(fixture.receipt().get("status"))
                    && fixture.tsv().equals("# m3.machine-evidence.refused\n"),
                    "invalid database mode refuses and replaces previous successful evidence: " + mode);
        }
        boolean providerPresent;
        try {
            Class.forName("com.synexia.mindex.db.MIndexDB", false, MachineFingerprintTest.class.getClassLoader());
            providerPresent = true;
        } catch (ClassNotFoundException absent) {
            providerPresent = false;
        }
        if (!providerPresent) {
            require(fixture.run(1, false, false, "required") == 2
                    && "refused".equals(fixture.receipt().get("status")),
                    "required database mode refuses an unavailable MIndexDB provider");
        }
        require(fixture.run(1, false, false, "auto") == 0 && expected.equals(fixture.tsv()),
                "automatic mode preserves exact syntax results after a refused invocation");
        require("auto".equals(object(fixture.receipt().get("moduleDatabase")).get("mode")),
                "explicit auto mode reaches the database controller");
        try {
            Thread.currentThread().interrupt();
            require(fixture.run(1, false, false, "off") == 2 && Thread.currentThread().isInterrupted(),
                    "interrupted generation refuses publication and preserves interruption");
        } finally {
            Thread.interrupted();
        }
        require(fixture.run(1, false, false, "off") == 0 && expected.equals(fixture.tsv()),
                "a fresh invocation recovers after cancellation without changing syntax output");
    }

    /** Run with the real Maven API or the existing adapter harness, without adding a core-test dependency. */
    private static void mojoModuleDatabaseModes(String code) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("io.synexia.maven.smart.MachineFingerprintMojo");
        } catch (ClassNotFoundException missing) {
            if (Boolean.getBoolean("m3.smart.requireMojoTests")) throw missing;
            System.out.println("MACHINE FINGERPRINT MOJO TESTS SKIPPED: Maven adapter absent from JDK-only classpath");
            return;
        }
        Fixture fixture = fixture(code, "Example.java", false);
        Object mojo = type.getDeclaredConstructor().newInstance();
        set(mojo, "baseDir", fixture.base.toFile());
        set(mojo, "buildDir", fixture.base.resolve("target").toFile());
        set(mojo, "sourceRoot", fixture.source.toFile());
        set(mojo, "preparationReport", fixture.base.resolve("receipts/preparation.json").toFile());
        set(mojo, "atomPatternManifest", fixture.base.resolve("synthetic-checkpoints.tsv").toFile());
        type.getMethod("execute").invoke(mojo);
        require("auto".equals(object(fixture.receipt().get("moduleDatabase")).get("mode")),
                "Maven Mojo defaults to automatic database reuse");
        String expected = fixture.tsv();
        set(mojo, "moduleDb", "off");
        type.getMethod("execute").invoke(mojo);
        require("off".equals(object(fixture.receipt().get("moduleDatabase")).get("mode"))
                && expected.equals(fixture.tsv()),
                "Maven moduleDb parameter forwards off while preserving the syntax output");
        set(mojo, "moduleDb", "invalid");
        try {
            type.getMethod("execute").invoke(mojo);
            throw new AssertionError("invalid Maven moduleDb parameter was accepted");
        } catch (java.lang.reflect.InvocationTargetException refused) {
            require("org.apache.maven.plugin.MojoExecutionException".equals(refused.getCause().getClass().getName())
                    && "refused".equals(fixture.receipt().get("status")),
                    "invalid Maven database mode surfaces the goal failure and refusal receipt");
        }
    }

    private static void set(Object object, String fieldName, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static String column(Fixture fixture, int index) throws Exception {
        for (String row : fixture.tsv().split("\n"))
            if (row.startsWith("Example.java\t")) return row.split("\t", -1)[index];
        throw new AssertionError("Example.java atom not found");
    }

    static Fixture fixture(String code, String file, boolean extra) throws Exception {
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

    record Fixture(Path base, Path source) {
        int run(int workers) {
            return run(workers, false);
        }
        int run(int workers, boolean attributeTypes) {
            return run(workers, attributeTypes, false);
        }
        int run(int workers, boolean attributeTypes, boolean analyzeFlow) {
            return run(workers, attributeTypes, analyzeFlow, null);
        }
        int run(int workers, boolean attributeTypes, boolean analyzeFlow, String databaseMode) {
            List<String> args = new ArrayList<>(List.of("--project-dir", base.toString(),
                    "--build-dir", "target", "--source-root", "src",
                    "--preparation-report", "receipts/preparation.json",
                    "--atom-pattern-manifest", "synthetic-checkpoints.tsv",
                    "--workers", Integer.toString(workers),
                    "--attribute-types", Boolean.toString(attributeTypes),
                    "--analyze-flow", Boolean.toString(analyzeFlow)));
            if (databaseMode != null) args.addAll(List.of("--module-db", databaseMode));
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
