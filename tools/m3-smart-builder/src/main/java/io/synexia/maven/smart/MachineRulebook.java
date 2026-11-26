// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Packaged M3 law packet: model guidance is versioned, auditable and bound into nominations. */
final class MachineRulebook {
    static final String VERSION = "# m3.machine.rules.v1";
    static final String HEADER = "rule_id\tstage\tauthority\tdirective";
    private static final String RESOURCE = "/io/synexia/maven/smart/mop-rules.tsv";
    private static final Set<String> REQUIRED = Set.of("api-lock", "atom-inventory", "atom-leaf",
            "canon-proof", "contract-hash", "deterministic-first", "doc-memory", "effect-contract",
            "file-digest", "inference-boundary", "logic-hash", "masked-regex", "pattern-order",
            "recipe-first", "simhash", "structural-hash", "typed-residue", "verified-promotion");
    private static final Set<String> STAGES = Set.of("INVENTORY", "LEXICAL", "ATOM", "PATTERN",
            "SIMILARITY", "REWRITE", "VERIFY", "PROMOTION", "RESIDUE");
    private static final Set<String> AUTHORITIES = Set.of("MECHANICAL", "PRODUCER_REPORTED", "REVIEW_ONLY", "EXTERNAL_GATE");
    private static final Set<String> ENFORCED_IDS = Set.of("atom-inventory", "file-digest",
            "inference-boundary", "recipe-first", "stage-evidence-binding");

    record Rule(String id, String stage, String authority, String directive) {
        String json() {
            return "{\"ruleId\":" + TinyJson.quote(id) + ",\"stage\":" + TinyJson.quote(stage)
                    + ",\"authority\":" + TinyJson.quote(authority) + ",\"directive\":" + TinyJson.quote(directive) + "}";
        }
    }
    record Policy(String sha256, List<Rule> rules) {
        String json() {
            StringBuilder json = new StringBuilder("{\"schema\":\"m3.machine.rules.v1\",\"sha256\":")
                    .append(TinyJson.quote(sha256)).append(",\"rules\":[");
            for (int i = 0; i < rules.size(); i++) json.append(i == 0 ? "" : ",").append(rules.get(i).json());
            return json.append("]}").toString();
        }
    }

    private MachineRulebook() { }

    static Policy load() throws IOException {
        try (InputStream source = MachineRulebook.class.getResourceAsStream(RESOURCE)) {
            if (source == null) throw new IOException("missing packaged machine rules");
            return parse(source.readNBytes(16_385));
        }
    }

    static Policy parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 16_384)
            throw new IOException("unbounded machine rules");
        String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (content.indexOf('\r') >= 0 || content.indexOf('\0') >= 0 || !content.endsWith("\n"))
            throw new IOException("invalid machine rules encoding");
        String[] rows = content.substring(0, content.length() - 1).split("\n", -1);
        if (rows.length < REQUIRED.size() + 2 || rows.length > 66
                || !VERSION.equals(rows[0]) || !HEADER.equals(rows[1]))
            throw new IOException("invalid machine rulebook schema or coverage");
        List<Rule> rules = new ArrayList<>();
        String previous = "";
        for (int i = 2; i < rows.length; i++) {
            String[] fields = rows[i].split("\t", -1);
            if (fields.length != 4 || !fields[0].matches("[a-z][a-z0-9-]{0,47}")
                    || fields[0].compareTo(previous) <= 0
                    || !STAGES.contains(fields[1]) || !AUTHORITIES.contains(fields[2])
                    || (fields[2].equals("MECHANICAL") != ENFORCED_IDS.contains(fields[0]))
                    || fields[3].isBlank() || fields[3].length() > 250 || fields[3].chars().anyMatch(c -> c < 32 || c > 126))
                throw new IOException("unapproved or unsorted machine rule");
            previous = fields[0];
            rules.add(new Rule(fields[0], fields[1], fields[2], fields[3]));
        }
        Set<String> covered = Set.copyOf(rules.stream().map(Rule::id).toList());
        if (!covered.containsAll(REQUIRED) || !covered.containsAll(ENFORCED_IDS))
            throw new IOException("missing mandatory or mechanically enforced M3 rule");
        return new Policy(Hashes.sha256(bytes), List.copyOf(rules));
    }

    static String bind(String preparation, AtomPatternEvidence.Summary atoms, RecipeCatalog catalog,
                       MachineEvidence.Summary machine, Policy rules) {
        return bind(preparation, atoms, catalog, machine, rules, null);
    }

    static String bind(String preparation, AtomPatternEvidence.Summary atoms, RecipeCatalog catalog,
                       MachineEvidence.Summary machine, Policy rules, MachineOperatorCatalog.Summary semantic) {
        return bind(preparation, atoms, catalog, machine, rules, semantic, null);
    }

    static String bind(String preparation, AtomPatternEvidence.Summary atoms, RecipeCatalog catalog,
                       MachineEvidence.Summary machine, Policy rules, MachineOperatorCatalog.Summary semantic,
                       MachineOperatorProofRegistry.Summary proof) {
        return Hashes.sha256((preparation + "\n" + atoms.json() + "\n" + catalog.digest()
                + (machine == null ? "" : "\n" + machine.json()) + "\n" + rules.sha256()
                + (semantic == null ? "" : "\n" + semantic.json())
                + (proof == null ? "" : "\n" + proof.json()))
                .getBytes(StandardCharsets.UTF_8));
    }
}
