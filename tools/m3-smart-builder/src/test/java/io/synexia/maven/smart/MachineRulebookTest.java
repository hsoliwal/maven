// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Packaged M3 law packet remains strict, complete, deterministic and source-bound in nomination replay. */
public final class MachineRulebookTest {
    private static int checks;
    private MachineRulebookTest() { }

    public static void main(String[] args) throws Exception {
        MachineRulebook.Policy rules = MachineRulebook.load();
        require(rules.rules().size() == 30, "18 mandatory M3 laws and twelve additive reviewed or mechanically enforced laws present");
        require(rules.sha256().matches("[0-9a-f]{64}"), "policy has a strong content digest");
        Map<?, ?> object = object(TinyJson.parse(rules.json()));
        require("m3.machine.rules.v1".equals(object.get("schema")), "policy is machine readable");
        require(rules.sha256().equals(object.get("sha256")), "policy digest serialized");
        require(rules.rules().stream().filter(rule -> rule.authority().equals("MECHANICAL")).count() == 5,
                "mechanical authority reserved for actually checked admission boundaries");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("simhash")
                        && rule.authority().equals("REVIEW_ONLY")), "SimHash stays advisory");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("logic-hash")
                        && rule.authority().equals("PRODUCER_REPORTED")), "logic hashes do not imply semantic proof");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("masked-regex")
                        && rule.authority().equals("EXTERNAL_GATE")), "masked lexical checks belong to their proof owner");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("callgraph-residue")
                        && rule.authority().equals("REVIEW_ONLY")),
                "unattributed call sites are typed residue rather than effect proof");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("control-flow-cues")
                        && rule.authority().equals("PRODUCER_REPORTED")),
                "syntax-based flow/effect flags cannot claim CFG authority");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("compiler-bindings")
                        && rule.authority().equals("PRODUCER_REPORTED")),
                "resolved compiler symbols do not assert runtime dispatch or effects");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("cfg-defuse")
                        && rule.authority().equals("PRODUCER_REPORTED")),
                "local may-flow definitions are bounded cues, not full CFG proof");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("match-priority")
                        && rule.authority().equals("REVIEW_ONLY")),
                "similarity ranking is a bounded model hint");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("operator-catalog")
                        && rule.authority().equals("REVIEW_ONLY")), "operator recognition is not proof");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("semantic-residue")
                        && rule.authority().equals("REVIEW_ONLY")), "unrecognized atoms remain typed residue");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("contract-envelope")
                        && rule.authority().equals("PRODUCER_REPORTED")),
                "syntactic contract envelope remains unverified producer evidence");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("signed-proof-attestation")
                        && rule.authority().equals("REVIEW_ONLY")),
                "authenticated provenance never grants automatic promotion");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("source-proof-scope")
                        && rule.authority().equals("REVIEW_ONLY")),
                "legacy signatures never claim source-specific behavioral proof");
        require(rules.rules().stream().anyMatch(rule -> rule.id().equals("stage-evidence-binding")
                        && rule.authority().equals("MECHANICAL")),
                "source-specific signed stage binding is an actually executed admission gate");
        String original = MachineRulebook.VERSION + "\n" + MachineRulebook.HEADER + "\n";
        for (MachineRulebook.Rule rule : rules.rules())
            original += rule.id() + "\t" + rule.stage() + "\t" + rule.authority() + "\t" + rule.directive() + "\n";
        require(rules.equals(MachineRulebook.parse(original.getBytes(StandardCharsets.UTF_8))),
                "byte-identical machine law replay");
        String mutation = original.replace("SimHash Hamming-near atoms", "SimHash near atoms");
        MachineRulebook.Policy changed = MachineRulebook.parse(mutation.getBytes(StandardCharsets.UTF_8));
        require(!rules.sha256().equals(changed.sha256()), "editing guidance changes evidence binding");
        MachineRulebook.Policy additive = MachineRulebook.parse((original
                + "z-future-rule\tRESIDUE\tREVIEW_ONLY\tNew mechanical-first guidance with no implied proof.\n")
                .getBytes(StandardCharsets.UTF_8));
        require(additive.rules().size() == rules.rules().size() + 1 && !additive.sha256().equals(rules.sha256()),
                "future guidance extends the rulebook additively while retaining all mandatory canon laws");
        refused(original.replace(MachineRulebook.HEADER, "broken\theader"), "schema must match exactly");
        refused(original.replace("logic-hash\t", "file-digest\t"), "duplicate rule refused");
        refused(original.replaceFirst("(?m)^stage-evidence-binding[^\\n]*\\n", ""),
                "required mechanically enforced stage rule cannot be dropped");
        refused(original.replace("MECHANICAL", "AUTO_EXECUTE"), "unknown authority refused");
        refused(original + "z-unproven-rule\tRESIDUE\tMECHANICAL\tAn unimplemented rule must not claim authority.\n",
                "additional rules cannot falsely claim mechanical enforcement");
        refused(original.substring(0, original.length() - 1), "trailing LF mandatory");
        refused(original.replace("simhash\tSIMILARITY", "simhash\tINVENTORY\nextra"), "injected row refused");
        refused(original.repeat(8), "oversized or repeated rulebook refused");
        System.out.println("MACHINE RULEBOOK TESTS PASS: " + checks);
    }

    private static void refused(String input, String explanation) {
        boolean refused = false;
        try { MachineRulebook.parse(input.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException expected) { refused = true; }
        require(refused, explanation);
    }
    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new AssertionError("JSON object expected");
        return map;
    }
    private static void require(boolean condition, String explanation) {
        checks++;
        if (!condition) throw new AssertionError(explanation);
    }
}
