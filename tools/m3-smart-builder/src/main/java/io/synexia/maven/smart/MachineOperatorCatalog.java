// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, source-bound operator recognition. Hash matches yield review candidates,
 * never semantic equality, refactoring permission or compiler-verified operators.
 */
final class MachineOperatorCatalog {
    static final String HEADER = "operator_id\tfamily\tmatch_kind\tfingerprint_sha256";
    static final String CONTRACT_PINNED_HEADER = HEADER + "\tcontract_sha256";
    private static final Set<String> FAMILIES = Set.of("MAP", "FILTER", "FLATMAP", "FOLD", "REDUCE", "ZIP",
            "JOIN", "GROUP", "PREDICATE", "ADAPTER", "STRATEGY", "STATE", "PIPELINE", "CONTEXT", "OTHER");
    private static final int MAX_OPERATORS = 128;
    private static final int MAX_DETAILS = 24;

    private MachineOperatorCatalog() { }

    private record Operator(String id, String family, String matchKind, String fingerprint,
                            String pinnedContract) { }

    record Leaf(String path, String atomId, String semanticId, String operatorId, String family,
                String matchKind, String residue, MachineContractEnvelope envelope) {
        int proofObligationMask() { return envelope.outstandingProofMask(); }
        String json() {
            return "{\"path\":" + TinyJson.quote(path)
                    + ",\"atomId\":" + TinyJson.quote(atomId)
                    + ",\"semanticIdSha256\":" + TinyJson.quote(semanticId)
                    + ",\"operatorId\":" + (operatorId == null ? "null" : TinyJson.quote(operatorId))
                    + ",\"family\":" + (family == null ? "null" : TinyJson.quote(family))
                    + ",\"matchKind\":" + TinyJson.quote(matchKind)
                    + ",\"residue\":" + TinyJson.quote(residue)
                    + ",\"proofObligationMask\":" + proofObligationMask()
                    + ",\"contractEnvelope\":" + envelope.json()
                    + ",\"authority\":\"REVIEW_ONLY\"}";
        }
    }

    record Summary(String catalogSha256, String evidenceSha256, int atoms, int operatorHints,
                   int unclassified, int ambiguous, int contractCueMismatches,
                   List<Leaf> details, List<Leaf> allLeaves) {
        // Preserves synthetic fixtures and old call sites; real producers retain all bounded leaves.
        Summary(String catalogSha256, String evidenceSha256, int atoms, int operatorHints,
                int unclassified, int ambiguous, int contractCueMismatches, List<Leaf> details) {
            this(catalogSha256, evidenceSha256, atoms, operatorHints,
                    unclassified, ambiguous, contractCueMismatches, details, details);
        }
        Summary {
            details = List.copyOf(details);
            allLeaves = List.copyOf(allLeaves);
        }

        String json() {
            StringBuilder json = new StringBuilder("{\"schema\":\"m3.smart.semantic-operator-candidates.v1\",\"catalogSha256\":")
                    .append(TinyJson.quote(catalogSha256))
                    .append(",\"machineEvidenceSha256\":").append(TinyJson.quote(evidenceSha256))
                    .append(",\"atoms\":").append(atoms)
                    .append(",\"operatorHints\":").append(operatorHints)
                    .append(",\"unclassified\":").append(unclassified)
                    .append(",\"ambiguous\":").append(ambiguous)
                    .append(",\"contractCueMismatches\":").append(contractCueMismatches)
                    .append(",\"detailsTruncated\":").append(atoms > details.size())
                    .append(",\"semanticLeaves\":[");
            for (int i = 0; i < details.size(); i++)
                json.append(i == 0 ? "" : ",").append(details.get(i).json());
            return json.append("],\"sourceBound\":true,\"similarityIsProof\":false,"
                    + "\"contractEnvelopeComplete\":false,\"semanticProofAuthenticated\":false,"
                    + "\"promotionAuthority\":false}").toString();
        }
    }

    /** The caller must have independently validated the machine ledger against current source bytes. */
    static Summary inspect(Path base, Path evidenceFile, Path catalogFile,
                           MachineEvidence.Summary validatedEvidence) throws IOException {
        if (validatedEvidence == null) throw new IOException("operator catalogue requires a validated machine ledger");
        byte[] evidence = RecipeCatalog.readLocal(base, evidenceFile, 2_097_152);
        String evidenceSha = Hashes.sha256(evidence);
        if (!evidenceSha.equals(validatedEvidence.inputSha256()))
            throw new IOException("machine evidence changed before semantic projection");
        byte[] approved = RecipeCatalog.readLocal(base, catalogFile, 65_536);
        List<Operator> operators = parseCatalog(decode(approved));
        Map<String, List<Operator>> logic = new HashMap<>();
        Map<String, List<Operator>> structural = new HashMap<>();
        for (Operator operator : operators)
            (operator.matchKind().equals("LOGIC") ? logic : structural)
                    .computeIfAbsent(operator.fingerprint(), ignored -> new ArrayList<>()).add(operator);

        String[] rows = rows(decode(evidence));
        if (rows.length < 2 || rows.length - 1 != validatedEvidence.atoms()
                || !Set.of(MachineEvidence.HEADER, MachineEvidence.EXTENDED_HEADER,
                        MachineEvidence.ATTRIBUTED_HEADER, MachineEvidence.FLOW_HEADER).contains(rows[0]))
            throw new IOException("inconsistent machine evidence rows");
        int hints = 0, unclassified = 0, ambiguous = 0, contractMismatches = 0;
        List<Leaf> all = new ArrayList<>();
        for (int i = 1; i < rows.length; i++) {
            String[] fields = rows[i].split("\t", -1);
            if (!Set.of(7, 9, 13, 21).contains(fields.length))
                throw new IOException("invalid machine atom row");
            MachineContractEnvelope envelope = MachineContractEnvelope.fromValidatedRow(fields);
            List<Operator> matches = logic.getOrDefault(fields[4], List.of());
            String matchKind = "LOGIC";
            if (matches.isEmpty()) {
                matches = structural.getOrDefault(fields[3], List.of());
                matchKind = matches.isEmpty() ? "NONE" : "STRUCTURAL";
            }
            boolean contractMismatch = !matches.isEmpty() && matches.stream().noneMatch(op ->
                    op.pinnedContract() == null || op.pinnedContract().equals(envelope.syntacticContractSha256()));
            matches = matches.stream().filter(op -> op.pinnedContract() == null
                    || op.pinnedContract().equals(envelope.syntacticContractSha256())).toList();
            String residue;
            Operator selected = null;
            if (contractMismatch) {
                residue = "CONTRACT_CUE_MISMATCH";
                contractMismatches++;
            } else if (matches.isEmpty()) {
                residue = "NO_ADMITTED_OPERATOR";
                unclassified++;
            } else if (matches.size() != 1) {
                residue = "AMBIGUOUS_OPERATOR";
                ambiguous++;
            } else {
                selected = matches.getFirst();
                residue = "CONTRACT_AND_EFFECT_PROOF_REQUIRED";
                hints++;
            }
            String identity = Hashes.sha256(("m3.semantic-leaf.v1\n" + fields[0] + "\n" + fields[1]
                    + "\n" + fields[2] + "\n" + fields[3] + "\n" + fields[4]
                    + "\n" + fields[6]).getBytes(StandardCharsets.UTF_8));
            all.add(new Leaf(fields[0], fields[2], identity,
                    selected == null ? null : selected.id(), selected == null ? null : selected.family(),
                    matchKind, residue, envelope));
        }
        // Spend the bounded prompt budget on unsolved/ambiguous atoms first, not easy matches.
        all.sort(Comparator.comparingInt((Leaf leaf) -> switch (leaf.residue()) {
            case "CONTRACT_CUE_MISMATCH" -> 0;
            case "NO_ADMITTED_OPERATOR" -> 1;
            case "AMBIGUOUS_OPERATOR" -> 2;
            default -> 3;
        }).thenComparing(Leaf::path).thenComparing(Leaf::atomId));
        return new Summary(Hashes.sha256(approved), evidenceSha, all.size(), hints, unclassified,
                ambiguous, contractMismatches, List.copyOf(all.subList(0, Math.min(MAX_DETAILS, all.size()))),
                List.copyOf(all));
    }

    private static List<Operator> parseCatalog(String text) throws IOException {
        String[] rows = rows(text);
        boolean contractPinned = CONTRACT_PINNED_HEADER.equals(rows[0]);
        if (rows.length < 2 || rows.length > MAX_OPERATORS + 1
                || !(HEADER.equals(rows[0]) || contractPinned))
            throw new IOException("invalid semantic operator catalogue schema");
        String previous = "";
        List<Operator> operators = new ArrayList<>();
        for (int i = 1; i < rows.length; i++) {
            String[] fields = rows[i].split("\t", -1);
            if (fields.length != (contractPinned ? 5 : 4) || !fields[0].matches("[a-z][a-z0-9-]{0,63}")
                    || fields[0].compareTo(previous) <= 0 || !FAMILIES.contains(fields[1])
                    || !Set.of("LOGIC", "STRUCTURAL").contains(fields[2])
                    || !fields[3].matches("[0-9a-f]{64}")
                    || contractPinned && !fields[4].matches("[0-9a-f]{64}"))
                throw new IOException("invalid or unsorted semantic operator row");
            previous = fields[0];
            operators.add(new Operator(fields[0], fields[1], fields[2], fields[3],
                    contractPinned ? fields[4] : null));
        }
        return List.copyOf(operators);
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String[] rows(String text) throws IOException {
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || !text.endsWith("\n"))
            throw new IOException("invalid semantic ledger encoding");
        return text.substring(0, text.length() - 1).split("\n", -1);
    }
}
