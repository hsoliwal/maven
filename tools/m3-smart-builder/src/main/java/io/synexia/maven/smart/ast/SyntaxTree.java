// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/**
 * Full-source, parser-neutral syntax occurrence plane. Provider kind and field
 * names survive projection unchanged; there is deliberately no universal enum
 * that can erase grammar distinctions. Byte offsets are half-open UTF-8/source
 * byte coordinates, never JVM UTF-16 indices. The exact source byte image is
 * retained once, including gaps, comments, errors and unparsed content.
 *
 * <p>Persistent nodes occupy aligned primitive columns, not object instances.
 * The optional visitor/import stack is transient. Semantic binding data and
 * parser-owned tree lifetimes are not inferred from this source projection.
 */
public final class SyntaxTree {
    public static final int TOKEN = 1;
    public static final int TRIVIA = 1 << 1;
    public static final int MISSING = 1 << 2;
    public static final int ERROR = 1 << 3;
    public static final int NAMED = 1 << 4;
    public static final int SYNTHETIC = 1 << 5;
    private static final int ALL_FLAGS = TOKEN | TRIVIA | MISSING | ERROR | NAMED | SYNTHETIC;

    /** Six methods are enough to map a foreign concrete or abstract syntax tree. */
    public interface ForeignNode<N> {
        String kind(N node);
        String field(N node);
        int startByte(N node);
        int endByte(N node);
        int flags(N node);
        Iterable<N> children(N node);
    }

    private final String grammar;
    private final byte[] source;
    private final String[] kinds;
    private final String[] fields;
    private final int[] kind, field, flags, parent, firstChild, nextSibling, start, end;
    private final String sha256;

    private SyntaxTree(Builder b) {
        grammar = b.grammar;
        source = b.source;
        kinds = b.kinds.keySet().toArray(String[]::new);
        fields = b.fields.keySet().toArray(String[]::new);
        kind = Arrays.copyOf(b.kind, b.size);
        field = Arrays.copyOf(b.field, b.size);
        flags = Arrays.copyOf(b.flags, b.size);
        parent = Arrays.copyOf(b.parent, b.size);
        firstChild = Arrays.copyOf(b.firstChild, b.size);
        nextSibling = Arrays.copyOf(b.nextSibling, b.size);
        start = Arrays.copyOf(b.start, b.size);
        end = Arrays.copyOf(b.end, b.size);
        sha256 = digest(source);
    }

    public String grammar() { return grammar; }
    public String sourceSha256() { return sha256; }
    public int sourceLength() { return source.length; }
    public byte[] sourceBytes() { return source.clone(); }
    public byte[] sourceBytes(int from, int to) {
        Objects.checkFromToIndex(from, to, source.length);
        return Arrays.copyOfRange(source, from, to);
    }
    public int size() { return kind.length; }
    public String kind(int n) { return kinds[kind[checked(n)]]; }
    /** Empty means the parser does not assign a field name to this edge. */
    public String field(int n) { return fields[field[checked(n)]]; }
    public int flags(int n) { return flags[checked(n)]; }
    public int parent(int n) { return parent[checked(n)]; }
    public int firstChild(int n) { return firstChild[checked(n)]; }
    public int nextSibling(int n) { return nextSibling[checked(n)]; }
    public int startByte(int n) { return start[checked(n)]; }
    public int endByte(int n) { return end[checked(n)]; }
    public boolean hasFlag(int n, int mask) { return (flags(n) & mask) == mask; }
    private int checked(int node) { return Objects.checkIndex(node, kind.length); }

    /** Returns the deepest covering occurrence, or -1 at EOF/empty input. */
    public int deepestAtByte(int offset) {
        Objects.checkIndex(offset, source.length + 1);
        if (offset == source.length) return -1;
        int current = 0;
        while (true) {
            int found = -1;
            for (int child = firstChild[current]; child >= 0; child = nextSibling[child]) {
                if (start[child] <= offset && offset < end[child]) { found = child; break; }
                if (start[child] > offset) break;
            }
            if (found < 0) return current;
            current = found;
        }
    }

    /** Stable source+grammar+occurrence identity; not a compiler semantic hash. */
    public String projectionSha256() {
        MessageDigest d = shaEngine();
        framed(d, "m3.syntax.projection/1");
        framed(d, grammar);
        integer(d, source.length);
        d.update(source);
        for (int i = 0; i < size(); i++) {
            framed(d, kinds[kind[i]]);
            framed(d, fields[field[i]]);
            integer(d, flags[i]); integer(d, parent[i]);
            integer(d, start[i]); integer(d, end[i]);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    public static Builder builder(String grammar, byte[] source) {
        return new Builder(grammar, source);
    }

    /** DFS projection: foreign provider objects do not survive into the primitive lanes. */
    public static <N> SyntaxTree project(String grammar, byte[] source,
                                           N root, ForeignNode<N> adapter) {
        return project0(grammar, source, root, adapter, false,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** A parser fragment gets an explicit full-file synthetic source root. */
    public static <N> SyntaxTree projectFragment(String grammar, byte[] source,
                                                   N fragment, ForeignNode<N> adapter) {
        return project0(grammar, source, fragment, adapter, true,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Bounded, cycle-refusing foreign AST intake. Some provider node APIs expose
     * parent references or DAGs rather than trees; identical objects are allowed
     * in separate completed branches but a reference repeated on the ACTIVE path
     * is refused. Node and depth limits also bound hostile/infinite iterators.
     */
    public static <N> SyntaxTree projectBounded(String grammar, byte[] source,
            N root, ForeignNode<N> adapter, int maxNodes, int maxDepth) {
        return project0(grammar, source, root, adapter, false, maxNodes, maxDepth);
    }

    /** Bounded source-fragment import; limits count provider nodes, not the wrapper. */
    public static <N> SyntaxTree projectFragmentBounded(String grammar, byte[] source,
            N fragment, ForeignNode<N> adapter, int maxNodes, int maxDepth) {
        return project0(grammar, source, fragment, adapter, true, maxNodes, maxDepth);
    }

    private static <N> SyntaxTree project0(String grammar, byte[] source,
                                            N root, ForeignNode<N> adapter,
                                            boolean fragment, int maxNodes, int maxDepth) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(adapter, "adapter");
        if (maxNodes < 1 || maxDepth < 1) {
            throw new IllegalArgumentException("positive parser node and depth budgets required");
        }
        Builder b = builder(grammar, source);
        if (fragment) b.open("m3.source_image", "", 0, SYNTHETIC);
        ArrayDeque<Frame<N>> stack = new ArrayDeque<>();
        IdentityHashMap<N, Boolean> active = new IdentityHashMap<>();
        int admitted = 0;
        add(root, adapter, b, stack, active);
        admitted++;
        while (!stack.isEmpty()) {
            Frame<N> frame = stack.peek();
            if (frame.children.hasNext()) {
                if (admitted == maxNodes || stack.size() == maxDepth) {
                    throw new IllegalStateException("foreign parser node/depth budget exceeded");
                }
                add(Objects.requireNonNull(frame.children.next(), "child"), adapter,
                        b, stack, active);
                admitted++;
            } else {
                b.close(adapter.endByte(frame.node));
                stack.pop();
                active.remove(frame.node);
            }
        }
        if (fragment) b.close(b.source.length);
        return b.build();
    }

    private static <N> void add(N node, ForeignNode<N> adapter, Builder b,
            ArrayDeque<Frame<N>> stack, IdentityHashMap<N, Boolean> active) {
        if (active.putIfAbsent(node, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("foreign parser node cycle");
        }
        try {
            b.open(adapter.kind(node), adapter.field(node),
                    adapter.startByte(node), adapter.flags(node));
            stack.push(new Frame<>(node,
                    Objects.requireNonNull(adapter.children(node), "children").iterator()));
        } catch (RuntimeException failure) {
            active.remove(node);
            throw failure;
        }
    }
    private record Frame<N>(N node, Iterator<N> children) { }

    public static final class Builder {
        private final String grammar;
        private final byte[] source;
        private final Map<String, Integer> kinds = new LinkedHashMap<>();
        private final Map<String, Integer> fields = new LinkedHashMap<>();
        private int[] kind = new int[16], field = new int[16], flags = new int[16];
        private int[] parent = new int[16], firstChild = new int[16], nextSibling = new int[16];
        private int[] lastChild = new int[16], lastEnd = new int[16];
        private int[] start = new int[16], end = new int[16], stack = new int[16];
        private int size, depth;
        private boolean built;

        private Builder(String grammar, byte[] source) {
            this.grammar = name(grammar, "grammar");
            this.source = Objects.requireNonNull(source, "source").clone();
            fields.put("", 0);
        }
        public int open(String kindName, String edgeName, int from, int bits) {
            ready();
            name(kindName, "kind");
            if ((bits & ~ALL_FLAGS) != 0 || ((bits & TRIVIA) != 0 && (bits & TOKEN) == 0))
                throw new IllegalArgumentException("unsupported flags");
            if (from < 0 || from > source.length) throw new IllegalArgumentException("start outside source");
            if (size == 0 && from != 0) throw new IllegalArgumentException("root must start at byte zero");
            if (size > 0 && depth == 0) throw new IllegalStateException("multiple roots");
            int p = depth == 0 ? -1 : stack[depth - 1];
            if (p >= 0 && (from < start[p] || from < lastEnd[p]))
                throw new IllegalArgumentException("unordered/overlapping child");
            grow();
            int id = size++;
            kind[id] = kinds.computeIfAbsent(kindName, k -> kinds.size());
            String edge = edgeName == null || edgeName.isEmpty() ? "" : name(edgeName, "field");
            field[id] = fields.computeIfAbsent(edge, k -> fields.size());
            flags[id] = bits;
            parent[id] = p;
            firstChild[id] = nextSibling[id] = lastChild[id] = -1;
            lastEnd[id] = from;
            start[id] = from;
            if (p >= 0) {
                if (firstChild[p] < 0) firstChild[p] = id;
                else nextSibling[lastChild[p]] = id;
                lastChild[p] = id;
            }
            if (depth == stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
            stack[depth++] = id;
            return id;
        }
        public int leaf(String kindName, String edgeName, int from, int to, int bits) {
            if (from < 0 || to < from || to > source.length
                    || ((bits & MISSING) != 0 && to != from)) {
                throw new IllegalArgumentException("invalid leaf range");
            }
            int id = open(kindName, edgeName, from, bits);
            close(to);
            return id;
        }
        public void close(int to) {
            ready();
            if (depth == 0) throw new IllegalStateException("no open node");
            int id = stack[depth - 1];
            if (to < start[id] || to > source.length || to < lastEnd[id]
                    || ((flags[id] & MISSING) != 0 && to != start[id]))
                throw new IllegalArgumentException("invalid end or missing-token width");
            if (depth == 1 && to != source.length)
                throw new IllegalArgumentException("root must cover complete source");
            end[id] = to;
            depth--;
            if (depth > 0) lastEnd[stack[depth - 1]] = to;
        }
        public SyntaxTree build() {
            ready();
            if (depth != 0 || size == 0) throw new IllegalStateException("incomplete tree");
            built = true;
            return new SyntaxTree(this);
        }
        private void ready() {
            if (built) throw new IllegalStateException("builder already consumed");
        }
        private void grow() {
            if (size < kind.length) return;
            int n = Math.addExact(kind.length, (kind.length >>> 1) + 1);
            // Stage every original lane before publishing any replacement. If
            // allocation fails, all ten field references and lengths survive.
            int[] newKind = Arrays.copyOf(kind, n);
            int[] newField = Arrays.copyOf(field, n);
            int[] newFlags = Arrays.copyOf(flags, n);
            int[] newParent = Arrays.copyOf(parent, n);
            int[] newFirstChild = Arrays.copyOf(firstChild, n);
            int[] newNextSibling = Arrays.copyOf(nextSibling, n);
            int[] newLastChild = Arrays.copyOf(lastChild, n);
            int[] newLastEnd = Arrays.copyOf(lastEnd, n);
            int[] newStart = Arrays.copyOf(start, n);
            int[] newEnd = Arrays.copyOf(end, n);
            kind = newKind;
            field = newField;
            flags = newFlags;
            parent = newParent;
            firstChild = newFirstChild;
            nextSibling = newNextSibling;
            lastChild = newLastChild;
            lastEnd = newLastEnd;
            start = newStart;
            end = newEnd;
        }
    }
    private static String name(String s, String description) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("empty " + description);
        return s;
    }
    private static MessageDigest shaEngine() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String digest(byte[] data) {
        return HexFormat.of().formatHex(shaEngine().digest(data));
    }
    private static void framed(MessageDigest d, String value) {
        // UTF-16 code units rather than lossy replacement of isolated surrogates.
        integer(d, value.length());
        for (int i = 0; i < value.length(); i++) {
            int unit = value.charAt(i);
            d.update((byte) (unit >>> 8)); d.update((byte) unit);
        }
    }
    private static void integer(MessageDigest d, int n) {
        d.update((byte) (n >>> 24)); d.update((byte) (n >>> 16));
        d.update((byte) (n >>> 8)); d.update((byte) n);
    }
}
