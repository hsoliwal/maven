// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable hierarchical semantic index persisted by M3IndexDB.
 *
 * <p>Leaf fingerprints come from analyzers. Every node with children is recomputed child-first from
 * canonical role + child-semantic-key ordering, so FILE through REPOSITORY identities are stable
 * across parallel producer order. Producer edge ordinals remain evidence/tie-breakers only.
 */
public final class M3IndexDbSemanticIndex {
    public static final String ARTIFACT_KIND = "m3.semantic-index";
    public static final int FORMAT_VERSION = 1;

    private final List<M3IndexDbSemanticNode> nodes;
    private final List<M3IndexDbSemanticEdge> edges;
    private final Map<String, M3IndexDbSemanticNode> byId;
    private final Map<String, List<M3IndexDbSemanticNode>> exact;
    private final Map<String, List<M3IndexDbSemanticNode>> structure;
    private final Map<String, List<M3IndexDbSemanticNode>> logic;
    private final Map<String, List<M3IndexDbSemanticNode>> patternRoles;
    private final Map<String, List<M3IndexDbSemanticEdge>> children;
    private final Map<String, List<M3IndexDbSemanticEdge>> parents;

    private M3IndexDbSemanticIndex(
            List<M3IndexDbSemanticNode> nodes,
            List<M3IndexDbSemanticEdge> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.byId = indexById(this.nodes);
        this.exact = indexByHash(this.nodes, HashKind.EXACT);
        this.structure = indexByHash(this.nodes, HashKind.STRUCTURE);
        this.logic = indexByHash(this.nodes, HashKind.LOGIC);
        this.patternRoles = indexByPatternRole(this.nodes);
        this.children = edgeIndex(this.edges, true, this.byId);
        this.parents = edgeIndex(this.edges, false, this.byId);
    }

    public static M3IndexDbSemanticIndex of(
            Collection<M3IndexDbSemanticNode> nodeRows,
            Collection<M3IndexDbSemanticEdge> edgeRows) {
        Objects.requireNonNull(nodeRows, "nodeRows");
        Objects.requireNonNull(edgeRows, "edgeRows");

        TreeMap<String, M3IndexDbSemanticNode> unique = new TreeMap<>();
        for (M3IndexDbSemanticNode node : nodeRows) {
            M3IndexDbSemanticNode checked = Objects.requireNonNull(node, "node");
            M3IndexDbSemanticNode prior = unique.putIfAbsent(checked.nodeId(), checked);
            if (prior != null && !prior.equals(checked)) {
                throw new IllegalArgumentException(
                        "conflicting semantic node rows for " + checked.nodeId());
            }
        }

        List<M3IndexDbSemanticEdge> edges = canonicalEdges(edgeRows);
        validateEdges(unique, edges);
        edges = normalizeOrdinals(unique, edges);
        Map<String, List<M3IndexDbSemanticEdge>> children = edgeIndex(edges, true, unique);
        TreeMap<String, M3IndexDbSemanticNode> recomposed = new TreeMap<>();
        HashMap<String, VisitState> state = new HashMap<>();
        for (String nodeId : unique.keySet()) {
            composeNode(nodeId, unique, children, recomposed, state);
        }
        return new M3IndexDbSemanticIndex(List.copyOf(recomposed.values()), edges);
    }

    public static String nodeId(M3IndexDbSemanticKind kind, CharSequence semanticKey) {
        Objects.requireNonNull(kind, "kind");
        String key = Objects.requireNonNull(semanticKey, "semanticKey").toString();
        return M3IndexDbSemanticFingerprint.utf16Sha256(
                "M3_NODE_V1|" + kind + "|" + key);
    }

    public List<M3IndexDbSemanticNode> nodes() {
        return nodes;
    }

    public List<M3IndexDbSemanticEdge> edges() {
        return edges;
    }

    public Optional<M3IndexDbSemanticNode> find(String nodeId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(nodeId, "nodeId")));
    }

    public M3IndexDbSemanticNode require(String nodeId) {
        return find(nodeId).orElseThrow(
                () -> new IllegalArgumentException("unknown semantic node: " + nodeId));
    }

    public List<M3IndexDbSemanticNode> nodesOfKind(M3IndexDbSemanticKind kind) {
        Objects.requireNonNull(kind, "kind");
        return nodes.stream().filter(node -> node.kind() == kind).toList();
    }

    public List<M3IndexDbSemanticNode> exactSha256(String sha256) {
        return exact.getOrDefault(requireSha(sha256), List.of());
    }

    public List<M3IndexDbSemanticNode> structuralSha256(String sha256) {
        return structure.getOrDefault(requireSha(sha256), List.of());
    }

    public List<M3IndexDbSemanticNode> logicSha256(String sha256) {
        return logic.getOrDefault(requireSha(sha256), List.of());
    }

    public List<M3IndexDbSemanticNode> patternRole(String patternRole) {
        String checked = Objects.requireNonNull(patternRole, "patternRole").strip();
        if (checked.isEmpty()) throw new IllegalArgumentException("patternRole");
        return patternRoles.getOrDefault(checked, List.of());
    }

    public List<M3IndexDbSemanticNode> structuralHash64(long hash) {
        return nodes.stream()
                .filter(node -> node.fingerprint().structuralHash64() == hash)
                .toList();
    }

    public List<M3IndexDbSemanticNode> logicHash64(long hash) {
        return nodes.stream()
                .filter(node -> node.fingerprint().logicHash64() == hash)
                .toList();
    }

    /** Candidate-only locality-sensitive lookup; never an equivalence decision. */
    public List<Similarity> nearSimHash(long simHash64, int maximumHammingDistance) {
        if (maximumHammingDistance < 0 || maximumHammingDistance > Long.SIZE) {
            throw new IllegalArgumentException("maximumHammingDistance");
        }
        ArrayList<Similarity> matches = new ArrayList<>();
        for (M3IndexDbSemanticNode node : nodes) {
            int distance = M3IndexDbSemanticFingerprint.hammingDistance(
                    simHash64,
                    node.fingerprint().simHash64());
            if (distance <= maximumHammingDistance) {
                matches.add(new Similarity(node, distance));
            }
        }
        matches.sort(
                Comparator.comparingInt(Similarity::hammingDistance)
                        .thenComparing(match -> match.node().semanticKey())
                        .thenComparing(match -> match.node().nodeId()));
        return List.copyOf(matches);
    }

    public List<M3IndexDbSemanticNode> children(String parentId) {
        return children.getOrDefault(
                        Objects.requireNonNull(parentId, "parentId"),
                        List.of())
                .stream()
                .map(edge -> require(edge.childId()))
                .toList();
    }

    public List<M3IndexDbSemanticNode> parents(String childId) {
        return parents.getOrDefault(
                        Objects.requireNonNull(childId, "childId"),
                        List.of())
                .stream()
                .map(edge -> require(edge.parentId()))
                .toList();
    }

    public byte[] encode() {
        return M3IndexDbSemanticCodec.encode(this);
    }

    public static M3IndexDbSemanticIndex decode(byte[] payload) {
        return M3IndexDbSemanticCodec.decode(payload);
    }

    public M3IndexDbArtifact store(M3IndexDB database, String artifactName) throws IOException {
        return Objects.requireNonNull(database, "database")
                .putSemanticIndex(artifactName, this);
    }

    public static M3IndexDbSemanticIndex load(M3IndexDB database, String artifactName)
            throws IOException {
        return Objects.requireNonNull(database, "database")
                .requireSemanticIndex(artifactName);
    }

    public record Similarity(M3IndexDbSemanticNode node, int hammingDistance) {
        public Similarity {
            node = Objects.requireNonNull(node, "node");
            if (hammingDistance < 0 || hammingDistance > Long.SIZE) {
                throw new IllegalArgumentException("hammingDistance");
            }
        }
    }

    private static M3IndexDbSemanticNode composeNode(
            String nodeId,
            Map<String, M3IndexDbSemanticNode> source,
            Map<String, List<M3IndexDbSemanticEdge>> children,
            Map<String, M3IndexDbSemanticNode> output,
            Map<String, VisitState> state) {
        M3IndexDbSemanticNode known = output.get(nodeId);
        if (known != null) return known;
        if (state.get(nodeId) == VisitState.VISITING) {
            throw new IllegalArgumentException("cyclic semantic composition at " + nodeId);
        }

        state.put(nodeId, VisitState.VISITING);
        M3IndexDbSemanticNode node = source.get(nodeId);
        List<M3IndexDbSemanticEdge> outgoing = children.getOrDefault(nodeId, List.of());
        M3IndexDbSemanticNode result = node;
        if (!outgoing.isEmpty()) {
            ArrayList<M3IndexDbSemanticFingerprint.Component> components =
                    new ArrayList<>(outgoing.size());
            for (M3IndexDbSemanticEdge edge : outgoing) {
                M3IndexDbSemanticNode child =
                        composeNode(edge.childId(), source, children, output, state);
                components.add(
                        new M3IndexDbSemanticFingerprint.Component(
                                edge.role(),
                                child.fingerprint()));
            }
            result = node.withFingerprint(
                    M3IndexDbSemanticFingerprint.compose(
                            node.kind() + ":" + node.semanticKey(),
                            components));
        }

        output.put(nodeId, result);
        state.put(nodeId, VisitState.DONE);
        return result;
    }

    private static List<M3IndexDbSemanticEdge> canonicalEdges(
            Collection<M3IndexDbSemanticEdge> rows) {
        ArrayList<M3IndexDbSemanticEdge> sorted = new ArrayList<>(rows.size());
        for (M3IndexDbSemanticEdge edge : rows) {
            sorted.add(Objects.requireNonNull(edge, "edge"));
        }
        sorted.sort(
                Comparator.comparing(M3IndexDbSemanticEdge::parentId)
                        .thenComparing(M3IndexDbSemanticEdge::role)
                        .thenComparing(M3IndexDbSemanticEdge::childId)
                        .thenComparingInt(M3IndexDbSemanticEdge::ordinal));

        ArrayList<M3IndexDbSemanticEdge> unique = new ArrayList<>(sorted.size());
        M3IndexDbSemanticEdge previous = null;
        for (M3IndexDbSemanticEdge edge : sorted) {
            if (!edge.equals(previous)) {
                unique.add(edge);
                previous = edge;
            }
        }
        return List.copyOf(unique);
    }

    private static List<M3IndexDbSemanticEdge> normalizeOrdinals(
            Map<String, M3IndexDbSemanticNode> nodes,
            List<M3IndexDbSemanticEdge> edges) {
        ArrayList<M3IndexDbSemanticEdge> sorted = new ArrayList<>(edges);
        sorted.sort((left, right) -> compareForComposition(left, right, nodes));

        ArrayList<M3IndexDbSemanticEdge> normalized = new ArrayList<>(sorted.size());
        String priorParent = null;
        String priorRole = null;
        int canonicalOrdinal = 0;
        int priorOrderedOrdinal = -1;
        for (M3IndexDbSemanticEdge edge : sorted) {
            boolean newGroup = !edge.parentId().equals(priorParent)
                    || !edge.role().equals(priorRole);
            if (newGroup) {
                canonicalOrdinal = 0;
                priorOrderedOrdinal = -1;
                priorParent = edge.parentId();
                priorRole = edge.role();
            }

            if (orderedRole(edge.role())) {
                if (edge.ordinal() == priorOrderedOrdinal) {
                    throw new IllegalArgumentException(
                            "conflicting ordered semantic ordinal at "
                                    + edge.parentId() + "/" + edge.role() + "#" + edge.ordinal());
                }
                normalized.add(edge);
                priorOrderedOrdinal = edge.ordinal();
            } else {
                normalized.add(
                        new M3IndexDbSemanticEdge(
                                edge.parentId(),
                                edge.childId(),
                                edge.role(),
                                canonicalOrdinal++));
            }
        }
        return List.copyOf(normalized);
    }

    private static int compareForComposition(
            M3IndexDbSemanticEdge left,
            M3IndexDbSemanticEdge right,
            Map<String, M3IndexDbSemanticNode> nodes) {
        int compared = left.parentId().compareTo(right.parentId());
        if (compared != 0) return compared;
        compared = left.role().compareTo(right.role());
        if (compared != 0) return compared;

        if (orderedRole(left.role())) {
            compared = Integer.compare(left.ordinal(), right.ordinal());
            if (compared != 0) return compared;
        }

        compared = nodes.get(left.childId())
                .semanticKey()
                .compareTo(nodes.get(right.childId()).semanticKey());
        if (compared != 0) return compared;
        return left.childId().compareTo(right.childId());
    }

    private static boolean orderedRole(String role) {
        return "ATOM".equals(role)
                || "FIELD".equals(role)
                || role.startsWith("ORDERED:");
    }

    private static void validateEdges(
            Map<String, M3IndexDbSemanticNode> nodes,
            List<M3IndexDbSemanticEdge> edges) {
        for (M3IndexDbSemanticEdge edge : edges) {
            if (!nodes.containsKey(edge.parentId())) {
                throw new IllegalArgumentException(
                        "dangling semantic parent: " + edge.parentId());
            }
            if (!nodes.containsKey(edge.childId())) {
                throw new IllegalArgumentException(
                        "dangling semantic child: " + edge.childId());
            }
        }
    }

    private static Map<String, M3IndexDbSemanticNode> indexById(
            List<M3IndexDbSemanticNode> nodes) {
        LinkedHashMap<String, M3IndexDbSemanticNode> result = new LinkedHashMap<>();
        for (M3IndexDbSemanticNode node : nodes) result.put(node.nodeId(), node);
        return Map.copyOf(result);
    }

    private static Map<String, List<M3IndexDbSemanticNode>> indexByPatternRole(
            List<M3IndexDbSemanticNode> nodes) {
        HashMap<String, ArrayList<M3IndexDbSemanticNode>> mutable = new HashMap<>();
        for (M3IndexDbSemanticNode node : nodes) {
            mutable.computeIfAbsent(node.patternRole(), ignored -> new ArrayList<>()).add(node);
        }
        HashMap<String, List<M3IndexDbSemanticNode>> result = new HashMap<>();
        mutable.forEach((role, values) -> {
            values.sort(Comparator.comparing(M3IndexDbSemanticNode::semanticKey));
            result.put(role, List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private static Map<String, List<M3IndexDbSemanticNode>> indexByHash(
            List<M3IndexDbSemanticNode> nodes,
            HashKind kind) {
        HashMap<String, ArrayList<M3IndexDbSemanticNode>> mutable = new HashMap<>();
        for (M3IndexDbSemanticNode node : nodes) {
            String hash = switch (kind) {
                case EXACT -> node.fingerprint().exactSha256();
                case STRUCTURE -> node.fingerprint().structuralSha256();
                case LOGIC -> node.fingerprint().logicSha256();
            };
            mutable.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(node);
        }
        HashMap<String, List<M3IndexDbSemanticNode>> result = new HashMap<>();
        mutable.forEach((hash, values) -> {
            values.sort(Comparator.comparing(M3IndexDbSemanticNode::semanticKey));
            result.put(hash, List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private static Map<String, List<M3IndexDbSemanticEdge>> edgeIndex(
            List<M3IndexDbSemanticEdge> edges,
            boolean byParent,
            Map<String, M3IndexDbSemanticNode> nodes) {
        HashMap<String, ArrayList<M3IndexDbSemanticEdge>> mutable = new HashMap<>();
        for (M3IndexDbSemanticEdge edge : edges) {
            String key = byParent ? edge.parentId() : edge.childId();
            mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
        }
        HashMap<String, List<M3IndexDbSemanticEdge>> result = new HashMap<>();
        mutable.forEach((key, values) -> {
            values.sort(byParent
                    ? (left, right) -> compareForComposition(left, right, nodes)
                    : Comparator.comparing(
                                    (M3IndexDbSemanticEdge edge) ->
                                            nodes.get(edge.parentId()).semanticKey())
                            .thenComparing(M3IndexDbSemanticEdge::role)
                            .thenComparingInt(M3IndexDbSemanticEdge::ordinal)
                            .thenComparing(M3IndexDbSemanticEdge::parentId));
            result.put(key, List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private static String requireSha(String value) {
        String checked = Objects.requireNonNull(value, "sha256");
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("lowercase SHA-256 required");
        }
        return checked;
    }

    private enum HashKind {
        EXACT,
        STRUCTURE,
        LOGIC
    }

    private enum VisitState {
        VISITING,
        DONE
    }
}
