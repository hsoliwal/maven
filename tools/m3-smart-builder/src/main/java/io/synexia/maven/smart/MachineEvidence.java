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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Source-bound, producer-reported atom fingerprints. Similarity is never semantic equivalence. */
final class MachineEvidence {
    static final String HEADER = "source_path\tsource_sha256\tatom_id\tstructural_sha256\tlogic_sha256\tsimhash64\tcontract_sha256";
    static final String EXTENDED_HEADER = HEADER + "\tcontrol_flow_mask\teffect_cue_mask";
    static final String ATTRIBUTED_HEADER = EXTENDED_HEADER
            + "\tresolved_call_sites\tunresolved_call_sites\tattributed_type_sha256\tcall_targets_sha256";
    static final String FLOW_HEADER = ATTRIBUTED_HEADER
            + "\tcfg_nodes\tcfg_edges\tcfg_sha256\tlocal_read_sites\tlocal_write_sites"
            + "\treaching_def_links\tdef_use_sha256\tflow_incomplete_mask";
    static final int FLOW_CONDITIONAL = 0x001;
    static final int FLOW_LOOP = 0x002;
    static final int FLOW_SWITCH = 0x004;
    static final int FLOW_TRY = 0x008;
    static final int FLOW_RETURN = 0x010;
    static final int FLOW_JUMP = 0x020;
    static final int FLOW_THROW = 0x040;
    static final int FLOW_YIELD = 0x080;
    static final int EFFECT_CALL = 0x001;
    static final int EFFECT_POTENTIAL_WRITE = 0x002;
    static final int EFFECT_ALLOCATION = 0x004;
    static final int EFFECT_THROW = 0x008;
    static final int EFFECT_SYNCHRONIZATION = 0x010;
    private static final int ALL_FLOW = 0x0ff;
    private static final int ALL_EFFECT = 0x01f;
    private static final int MAX_ROWS = 4096;
    private static final int MAX_CALLS_PER_ATOM = 4096;
    private static final int MAX_CANDIDATES = 24;
    private static final int NEAR_DISTANCE = 3;

    private MachineEvidence() { }

    private record Atom(String path, String id, String structure, String logic, long simHash,
                        String contract, int flow, int effect, int resolvedCalls, int unresolvedCalls,
                        int cfgNodes, int cfgEdges, String cfgHash, int localReads, int localWrites, int reachingLinks,
                        String defUseHash, int incomplete) { }

    private enum Pattern { LOGIC_EXACT, STRUCTURE_EXACT, SIMHASH_NEAR }

    private record Candidate(Pattern kind, Atom left, Atom right, int distance, boolean contractMatch) {
        boolean flowCompared() { return left.cfgHash() != null && right.cfgHash() != null; }
        boolean flowMatches() { return flowCompared() && left.cfgHash().equals(right.cfgHash()); }
        boolean defUseMatches() { return flowCompared() && left.defUseHash().equals(right.defUseHash()); }
        String json() {
            return "{\"kind\":" + TinyJson.quote(kind.name())
                    + ",\"leftPath\":" + TinyJson.quote(left.path())
                    + ",\"leftAtom\":" + TinyJson.quote(left.id())
                    + ",\"rightPath\":" + TinyJson.quote(right.path())
                    + ",\"rightAtom\":" + TinyJson.quote(right.id())
                    + ",\"simHashDistance\":" + distance
                    + ",\"contractSignatureMatches\":" + contractMatch
                    + ",\"mayFlowCompared\":" + flowCompared()
                    + ",\"mayFlowFingerprintMatches\":" + flowMatches()
                    + ",\"localDefUseFingerprintMatches\":" + defUseMatches()
                    + ",\"flowIncompleteMaskUnion\":" + (left.incomplete() | right.incomplete())
                    + ",\"authority\":\"REVIEW_ONLY\"}";
        }
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt((Candidate c) -> c.kind().ordinal())
            .thenComparing(c -> !c.contractMatch())
            .thenComparing(c -> c.flowCompared() && !c.flowMatches())
            .thenComparing(c -> c.flowCompared() && !c.defUseMatches())
            .thenComparingInt(Candidate::distance)
            .thenComparing(c -> c.left().path()).thenComparing(c -> c.left().id())
            .thenComparing(c -> c.right().path()).thenComparing(c -> c.right().id());

    record Cues(boolean available, int branchAtoms, int loopAtoms, int exceptionAtoms,
                int callAtoms, int potentialWriteAtoms, int allocationAtoms, int synchronizedAtoms) {
        String json() {
            return "{\"available\":" + available + ",\"branchAtoms\":" + branchAtoms
                    + ",\"loopAtoms\":" + loopAtoms + ",\"exceptionAtoms\":" + exceptionAtoms
                    + ",\"unresolvedCallAtoms\":" + callAtoms
                    + ",\"potentialWriteAtoms\":" + potentialWriteAtoms
                    + ",\"allocationAtoms\":" + allocationAtoms
                    + ",\"synchronizedAtoms\":" + synchronizedAtoms
                    + ",\"typeAttributionExecuted\":false,\"effectProofAuthenticated\":false}";
        }
    }

    record Attribution(boolean available, int resolvedCalls, int unresolvedCalls) {
        String json() {
            return "{\"available\":" + available + ",\"resolvedCallSites\":" + resolvedCalls
                    + ",\"unresolvedCallSites\":" + unresolvedCalls
                    + ",\"compilerAttributionExecuted\":" + available
                    + ",\"dynamicDispatchProven\":false,\"effectProofAuthenticated\":false}";
        }
    }

    record Flow(boolean available, int cfgNodes, int cfgEdges, int localReadSites,
                int localWriteSites, int reachingDefinitionLinks, int incompleteAtoms) {
        String json() {
            return "{\"available\":" + available + ",\"cfgNodes\":" + cfgNodes
                    + ",\"cfgEdges\":" + cfgEdges + ",\"localReadSites\":" + localReadSites
                    + ",\"localWriteSites\":" + localWriteSites
                    + ",\"mayReachingDefinitionLinks\":" + reachingDefinitionLinks
                    + ",\"incompleteAtoms\":" + incompleteAtoms
                    + ",\"cfgIsComplete\":false,\"aliasEffectsProven\":false,"
                    + "\"reachingDefinitionsAreProof\":false}";
        }
    }

    record Summary(String inputSha256, int files, int atoms, int structuralGroups,
                   int logicGroups, long similarPairs, Cues cues, Attribution attribution, Flow flow, long patternPairs,
                   List<Candidate> candidates) {
        String json() {
            StringBuilder json = new StringBuilder("{\"schema\":\"m3.smart.machine-evidence.v1\",\"inputSha256\":")
                    .append(TinyJson.quote(inputSha256))
                    .append(",\"sourceBound\":true,\"files\":").append(files)
                    .append(",\"atoms\":").append(atoms)
                    .append(",\"structuralDuplicateGroups\":").append(structuralGroups)
                    .append(",\"logicDuplicateGroups\":").append(logicGroups)
                    .append(",\"simHashNearPairs\":").append(similarPairs)
                    .append(",\"staticCues\":").append(cues.json())
                    .append(",\"attributionFacts\":").append(attribution.json())
                    .append(",\"flowFacts\":").append(flow.json())
                    .append(",\"patternCandidatePairs\":").append(patternPairs)
                    .append(",\"patternCandidatesTruncated\":").append(patternPairs > candidates.size())
                    .append(",\"patternCandidates\":[");
            for (int i = 0; i < candidates.size(); i++)
                json.append(i == 0 ? "" : ",").append(candidates.get(i).json());
            return json.append("],\"similarityIsProof\":false,\"semanticProofAuthenticated\":false}").toString();
        }
    }

    static Summary inspect(Path base, Path sourceRoot, Path file) throws IOException {
        byte[] bytes = RecipeCatalog.readLocal(base, file, 2_097_152);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || !text.endsWith("\n"))
            throw new IOException("invalid machine fingerprint ledger encoding");
        String[] rows = text.substring(0, text.length() - 1).split("\n", -1);
        if (rows.length < 2 || rows.length > MAX_ROWS + 1
                || !(HEADER.equals(rows[0]) || EXTENDED_HEADER.equals(rows[0])
                || ATTRIBUTED_HEADER.equals(rows[0]) || FLOW_HEADER.equals(rows[0])))
            throw new IOException("invalid machine fingerprint ledger schema");
        boolean hasFlow = FLOW_HEADER.equals(rows[0]);
        boolean attributed = hasFlow || ATTRIBUTED_HEADER.equals(rows[0]);
        boolean extended = attributed || EXTENDED_HEADER.equals(rows[0]);
        var selected = SourceInventory.inspectSelected(base, sourceRoot);
        Map<String, String> sources = new HashMap<>();
        for (SourceInventory.FileFact source : selected.files())
            sources.put(source.path(), source.sha256());
        Set<String> covered = new HashSet<>();
        Map<String, Integer> structural = new HashMap<>();
        Map<String, Integer> logic = new HashMap<>();
        List<Atom> atoms = new ArrayList<>(rows.length - 1);
        String previous = "";
        for (int i = 1; i < rows.length; i++) {
            String[] parts = rows[i].split("\t", -1);
            if (parts.length != (hasFlow ? 21 : attributed ? 13 : extended ? 9 : 7)
                    || !parts[0].endsWith(".java") || !sources.containsKey(parts[0])
                    || !parts[1].matches("[0-9a-f]{64}") || !parts[2].matches("[a-zA-Z0-9_$#.:/-]{1,128}")
                    || !parts[3].matches("[0-9a-f]{64}") || !parts[4].matches("[0-9a-f]{64}")
                    || !parts[5].matches("[0-9a-f]{16}") || !parts[6].matches("[0-9a-f]{64}"))
                throw new IOException("invalid fingerprint row");
            if (extended && (!parts[7].matches("[0-9a-f]{4}") || !parts[8].matches("[0-9a-f]{4}")))
                throw new IOException("invalid static-cue masks");
            if (attributed && (!parts[9].matches("[0-9]{1,5}") || !parts[10].matches("[0-9]{1,5}")
                    || !parts[11].matches("[0-9a-f]{64}") || !parts[12].matches("[0-9a-f]{64}")))
                throw new IOException("invalid compiler attribution row");
            if (hasFlow && (!parts[13].matches("[0-9]{1,3}") || !parts[14].matches("[0-9]{1,4}")
                    || !parts[15].matches("[0-9a-f]{64}") || !parts[16].matches("[0-9]{1,4}")
                    || !parts[17].matches("[0-9]{1,4}") || !parts[18].matches("[0-9]{1,4}")
                    || !parts[19].matches("[0-9a-f]{64}") || !parts[20].matches("[0-9a-f]{4}")))
                throw new IOException("invalid flow analysis row");
            int flow = extended ? Integer.parseInt(parts[7], 16) : 0;
            int effect = extended ? Integer.parseInt(parts[8], 16) : 0;
            int resolved = attributed ? Integer.parseInt(parts[9]) : 0;
            int unresolved = attributed ? Integer.parseInt(parts[10]) : 0;
            if ((flow & ~ALL_FLOW) != 0 || (effect & ~ALL_EFFECT) != 0)
                throw new IOException("unsupported static-cue mask bits");
            if (resolved > MAX_CALLS_PER_ATOM || unresolved > MAX_CALLS_PER_ATOM)
                throw new IOException("unbounded compiler invocation facts");
            int nodes = hasFlow ? Integer.parseInt(parts[13]) : 0;
            int edges = hasFlow ? Integer.parseInt(parts[14]) : 0;
            int reads = hasFlow ? Integer.parseInt(parts[16]) : 0;
            int writes = hasFlow ? Integer.parseInt(parts[17]) : 0;
            int links = hasFlow ? Integer.parseInt(parts[18]) : 0;
            int incomplete = hasFlow ? Integer.parseInt(parts[20], 16) : 0;
            if (hasFlow && (nodes < 2 || nodes > MachineFlowAnalysis.MAX_NODES
                    || edges < 1 || edges > MachineFlowAnalysis.MAX_EDGES
                    || reads > 4096 || writes > 4096 || links > MachineFlowAnalysis.MAX_LINKS
                    || (incomplete & ~MachineFlowAnalysis.ALL_INCOMPLETE) != 0))
                throw new IOException("out-of-budget or unsupported flow facts");
            String key = parts[0] + "\t" + parts[2];
            if (key.compareTo(previous) <= 0 || !parts[1].equals(sources.get(parts[0])))
                throw new IOException("fingerprint ordering or source binding refused");
            previous = key;
            covered.add(parts[0]);
            structural.merge(parts[3], 1, Integer::sum);
            logic.merge(parts[4], 1, Integer::sum);
            atoms.add(new Atom(parts[0], parts[2], parts[3], parts[4],
                    Long.parseUnsignedLong(parts[5], 16), parts[6], flow, effect, resolved, unresolved,
                    nodes, edges, hasFlow ? parts[15] : null, reads, writes, links,
                    hasFlow ? parts[19] : null, incomplete));
        }
        if (!covered.equals(sources.keySet())) throw new IOException("incomplete machine source coverage");
        long near = 0;
        long patterns = 0;
        TreeSet<Candidate> best = new TreeSet<>(CANDIDATE_ORDER);
        for (int i = 0; i < atoms.size(); i++) {
            Atom left = atoms.get(i);
            for (int j = i + 1; j < atoms.size(); j++) {
                Atom right = atoms.get(j);
                int distance = Long.bitCount(left.simHash() ^ right.simHash());
                if (distance <= NEAR_DISTANCE) near++;
                Pattern kind = left.logic().equals(right.logic()) ? Pattern.LOGIC_EXACT
                        : left.structure().equals(right.structure()) ? Pattern.STRUCTURE_EXACT
                        : distance <= NEAR_DISTANCE ? Pattern.SIMHASH_NEAR : null;
                if (kind == null) continue;
                patterns++;
                // Bounded top-K, deterministic across hash-map iteration and producer worker order.
                if (best.size() == MAX_CANDIDATES && kind.ordinal() > best.last().kind().ordinal()) continue;
                Candidate candidate = new Candidate(kind, left, right, distance,
                        left.contract().equals(right.contract()));
                if (best.add(candidate) && best.size() > MAX_CANDIDATES) best.pollLast();
            }
        }
        int structures = (int) structural.values().stream().filter(n -> n > 1).count();
        int logics = (int) logic.values().stream().filter(n -> n > 1).count();
        if (!selected.summary().digest().equals(SourceInventory.inspectSelected(base, sourceRoot).summary().digest()))
            throw new IOException("source changed during fingerprint examination");
        return new Summary(Hashes.sha256(bytes), covered.size(), atoms.size(), structures, logics, near,
                cues(atoms, extended), attribution(atoms, attributed), flow(atoms, hasFlow), patterns, List.copyOf(best));
    }

    private static Cues cues(List<Atom> atoms, boolean available) {
        int branches = 0, loops = 0, exceptions = 0, calls = 0, writes = 0, allocations = 0, synchronizedBlocks = 0;
        for (Atom atom : atoms) {
            if ((atom.flow() & (FLOW_CONDITIONAL | FLOW_SWITCH)) != 0) branches++;
            if ((atom.flow() & FLOW_LOOP) != 0) loops++;
            if ((atom.flow() & (FLOW_TRY | FLOW_THROW)) != 0) exceptions++;
            if ((atom.effect() & EFFECT_CALL) != 0) calls++;
            if ((atom.effect() & EFFECT_POTENTIAL_WRITE) != 0) writes++;
            if ((atom.effect() & EFFECT_ALLOCATION) != 0) allocations++;
            if ((atom.effect() & EFFECT_SYNCHRONIZATION) != 0) synchronizedBlocks++;
        }
        return new Cues(available, branches, loops, exceptions, calls, writes, allocations, synchronizedBlocks);
    }

    private static Attribution attribution(List<Atom> atoms, boolean available) {
        int resolved = 0, unresolved = 0;
        for (Atom atom : atoms) {
            resolved += atom.resolvedCalls();
            unresolved += atom.unresolvedCalls();
        }
        return new Attribution(available, resolved, unresolved);
    }

    private static Flow flow(List<Atom> atoms, boolean available) {
        int nodes = 0, edges = 0, reads = 0, writes = 0, links = 0, incomplete = 0;
        for (Atom atom : atoms) {
            nodes += atom.cfgNodes();
            edges += atom.cfgEdges();
            reads += atom.localReads();
            writes += atom.localWrites();
            links += atom.reachingLinks();
            if (atom.incomplete() != 0) incomplete++;
        }
        return new Flow(available, nodes, edges, reads, writes, links, incomplete);
    }
}
