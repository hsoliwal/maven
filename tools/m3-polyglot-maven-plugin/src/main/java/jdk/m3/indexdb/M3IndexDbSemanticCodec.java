// SPDX-License-Identifier: Apache-2.0
package jdk.m3.indexdb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compact deterministic V1 codec. Role: snapshot decoder.
 *
 * <p>Declared row counts must fit the supplied frame before allocation. Malformed
 * UTF-8 is rejected rather than replaced; valid wire bytes and public APIs are unchanged.
 * The caller must not mutate the payload concurrently with decoding.</p>
 */
final class M3IndexDbSemanticCodec {
    private static final long MAGIC = 0x4d3349444253454dL; // M3IDBSEM
    private static final int VERSION = 1;
    private static final int SHA_BYTES = 32;
    private static final int MAX_ROWS = 50_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int NODE_BYTES = SHA_BYTES * 4 + 1 + Integer.BYTES * 5 + Long.BYTES * 3;
    private static final int EDGE_BYTES = Integer.BYTES * 4;

    private M3IndexDbSemanticCodec() {}

    static byte[] encode(M3IndexDbSemanticIndex index) {
        Objects.requireNonNull(index, "index");
        List<M3IndexDbSemanticNode> nodes = index.nodes();
        List<M3IndexDbSemanticEdge> edges = index.edges();
        StringTable strings = strings(nodes, edges);

        HashMap<String, Integer> nodeOrdinals =
                new HashMap<>(Math.max(16, nodes.size() * 2));
        for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
            nodeOrdinals.put(nodes.get(ordinal).nodeId(), ordinal);
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(strings.values().size());
                out.writeInt(nodes.size());
                out.writeInt(edges.size());

                for (String value : strings.values()) {
                    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }

                for (M3IndexDbSemanticNode node : nodes) {
                    writeSha(out, node.nodeId());
                    out.writeByte(node.kind().ordinal());
                    out.writeInt(strings.id(node.semanticKey()));
                    out.writeInt(strings.id(node.sourcePath()));
                    out.writeInt(strings.id(node.symbol()));
                    out.writeInt(strings.id(node.patternRole()));

                    M3IndexDbSemanticFingerprint fingerprint = node.fingerprint();
                    writeSha(out, fingerprint.exactSha256());
                    writeSha(out, fingerprint.structuralSha256());
                    writeSha(out, fingerprint.logicSha256());
                    out.writeLong(fingerprint.structuralHash64());
                    out.writeLong(fingerprint.logicHash64());
                    out.writeLong(fingerprint.simHash64());
                    out.writeInt(strings.id(fingerprint.normalizedComposition()));
                }

                for (M3IndexDbSemanticEdge edge : edges) {
                    edge(out, edge, nodeOrdinals, strings);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Edge-emission atom; validates both references before writing any edge bytes. */
    private static void edge(DataOutputStream out, M3IndexDbSemanticEdge edge,
            Map<String, Integer> nodeOrdinals, StringTable strings) throws IOException {
        Integer parent = nodeOrdinals.get(edge.parentId());
        Integer child = nodeOrdinals.get(edge.childId());
        if (parent == null || child == null) {
            throw new IllegalStateException("semantic edge references absent node");
        }
        out.writeInt(parent);
        out.writeInt(child);
        out.writeInt(strings.id(edge.role()));
        out.writeInt(edge.ordinal());
    }

    static M3IndexDbSemanticIndex decode(byte[] payload) {
        byte[] checked = Objects.requireNonNull(payload, "payload");
        try (DataInputStream in =
                new DataInputStream(new ByteArrayInputStream(checked))) {
            if (in.readLong() != MAGIC) {
                throw new IllegalArgumentException("invalid M3IndexDB semantic magic");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported M3IndexDB semantic version: " + version);
            }

            int stringCount = count(in.readInt(), "stringCount");
            int nodeCount = count(in.readInt(), "nodeCount");
            int edgeCount = count(in.readInt(), "edgeCount");
            budget(in.available(), stringCount, nodeCount, edgeCount);

            ArrayList<String> strings = new ArrayList<>(stringCount);
            for (int index = 0; index < stringCount; index++) {
                strings.add(text(in));
            }

            ArrayList<M3IndexDbSemanticNode> nodes = new ArrayList<>(nodeCount);
            M3IndexDbSemanticKind[] kinds = M3IndexDbSemanticKind.values();
            for (int index = 0; index < nodeCount; index++) {
                String nodeId = readSha(in);
                int kindOrdinal = Byte.toUnsignedInt(in.readByte());
                if (kindOrdinal >= kinds.length) {
                    throw new IllegalArgumentException(
                            "invalid semantic kind ordinal: " + kindOrdinal);
                }
                String semanticKey = string(strings, in.readInt());
                String sourcePath = string(strings, in.readInt());
                String symbol = string(strings, in.readInt());
                String patternRole = string(strings, in.readInt());
                String exact = readSha(in);
                String structural = readSha(in);
                String logic = readSha(in);
                long structural64 = in.readLong();
                long logic64 = in.readLong();
                long simHash64 = in.readLong();
                String normalized = string(strings, in.readInt());

                nodes.add(
                        new M3IndexDbSemanticNode(
                                nodeId,
                                kinds[kindOrdinal],
                                semanticKey,
                                sourcePath,
                                symbol,
                                patternRole,
                                new M3IndexDbSemanticFingerprint(
                                        exact,
                                        structural,
                                        logic,
                                        structural64,
                                        logic64,
                                        simHash64,
                                        normalized)));
            }

            ArrayList<M3IndexDbSemanticEdge> edges = new ArrayList<>(edgeCount);
            for (int index = 0; index < edgeCount; index++) {
                int parent = ordinal(in.readInt(), nodeCount, "parent");
                int child = ordinal(in.readInt(), nodeCount, "child");
                String role = string(strings, in.readInt());
                int producerOrdinal = in.readInt();
                if (producerOrdinal < 0) {
                    throw new IllegalArgumentException("negative semantic producer ordinal");
                }
                edges.add(
                        new M3IndexDbSemanticEdge(
                                nodes.get(parent).nodeId(),
                                nodes.get(child).nodeId(),
                                role,
                                producerOrdinal));
            }

            if (in.read() != -1) {
                throw new IllegalArgumentException("trailing M3IndexDB semantic bytes");
            }
            return M3IndexDbSemanticIndex.of(nodes, edges);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException(
                    "truncated M3IndexDB semantic payload",
                    truncated);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "cannot decode M3IndexDB semantic payload",
                    invalid);
        }
    }

    /**
     * Frame-budget atom. Row minima use long before multiplication; rejected
     * counts never become ArrayList capacities. String bodies are checked separately.
     */
    private static void budget(int available, int strings, int nodes, int edges) {
        long minimum = (long) strings * Integer.BYTES
                + (long) nodes * NODE_BYTES + (long) edges * EDGE_BYTES;
        if (minimum > available) {
            throw new IllegalArgumentException("semantic row counts exceed payload");
        }
    }

    /** Length-prefixed text atom; validates the frame before decoding its UTF-8 body. */
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException(
                    "invalid M3IndexDB semantic string length: " + length);
        }
        byte[] utf8 = in.readNBytes(length);
        if (utf8.length != length) throw new EOFException("truncated semantic string");
        return text(utf8);
    }

    /** Text-decoding atom. Each invocation owns its non-thread-safe JDK decoder. */
    private static String text(byte[] utf8) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8)).toString();
    }

    private static StringTable strings(
            List<M3IndexDbSemanticNode> nodes,
            List<M3IndexDbSemanticEdge> edges) {
        TreeSet<String> unique = new TreeSet<>();
        for (M3IndexDbSemanticNode node : nodes) {
            unique.add(node.semanticKey());
            unique.add(node.sourcePath());
            unique.add(node.symbol());
            unique.add(node.patternRole());
            unique.add(node.fingerprint().normalizedComposition());
        }
        for (M3IndexDbSemanticEdge edge : edges) unique.add(edge.role());

        List<String> values = List.copyOf(unique);
        HashMap<String, Integer> ids = new HashMap<>(Math.max(16, values.size() * 2));
        for (int index = 0; index < values.size(); index++) ids.put(values.get(index), index);
        return new StringTable(values, Map.copyOf(ids));
    }

    private static void writeSha(DataOutputStream out, String sha256) throws IOException {
        byte[] bytes = HexFormat.of().parseHex(sha256);
        if (bytes.length != SHA_BYTES) throw new IllegalArgumentException("SHA-256 required");
        out.write(bytes);
    }

    private static String readSha(DataInputStream in) throws IOException {
        byte[] bytes = in.readNBytes(SHA_BYTES);
        if (bytes.length != SHA_BYTES) throw new EOFException("truncated SHA-256");
        return HexFormat.of().formatHex(bytes);
    }

    private static int count(int value, String field) {
        if (value < 0 || value > MAX_ROWS) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    private static int ordinal(int value, int size, String field) {
        if (value < 0 || value >= size) {
            throw new IllegalArgumentException("invalid " + field + " ordinal: " + value);
        }
        return value;
    }

    private static String string(List<String> values, int id) {
        return values.get(ordinal(id, values.size(), "string"));
    }

    private record StringTable(List<String> values, Map<String, Integer> ids) {
        int id(String value) {
            Integer id = ids.get(value);
            if (id == null) throw new IllegalStateException("uninterned semantic string");
            return id;
        }
    }
}
