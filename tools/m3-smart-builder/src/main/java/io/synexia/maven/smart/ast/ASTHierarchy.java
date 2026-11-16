// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors.
package io.synexia.maven.smart.ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Target-owned, source-pinned L0-L5 hierarchy over verified AST occurrence IDs.
 *
 * <p>Adapted from canonical Synexia MIndexASTHierarchy through a hash-pinned recipe.
 * No runtime dependency on Synexia, new AST parser, or OpenJDK AST replacement.
 *
 * <p>L0 is per-file atom facts; L1 is its file summary; L2 package; L3 module;
 * L4 project; L5 repository. No parser, mutable AST node or rewrite authority is
 * introduced here. Entries below L1 live only in primitive aligned arrays, not in
 * retained per-atom objects. One immutable scope plane is retained per level.
 *
 * <p>This works for every source family, including all M3 collection families.
 * Family bit masks are classification/evidence, not a claim that API contracts
 * of disparate collections are interchangeable. Source and receipt seals must
 * be supplied by the existing canonical file atomizer and compiler gates.
 */
public final class ASTHierarchy {
    public enum Level {
        L0_FILE_FACTS, L1_FILE_SUMMARY, L2_PACKAGE,
        L3_MODULE, L4_PROJECT, L5_REPOSITORY
    }

    public static final int ARRAY = 1;
    public static final int LIST = 1 << 1;
    public static final int SET = 1 << 2;
    public static final int MAP = 1 << 3;
    public static final int MULTIMAP = 1 << 4;
    public static final int MULTISET = 1 << 5;
    public static final int SORTED = 1 << 6;
    public static final int QUEUE_DEQUE = 1 << 7;
    public static final int TREE_GRAPH = 1 << 8;
    public static final int BIT_MASK = 1 << 9;
    public static final int CONCURRENT = 1 << 10;
    public static final int VIEW_ADAPTER = 1 << 11;
    public static final int MEMORY_PAGING = 1 << 12;
    public static final int ALL_COLLECTION_FAMILIES = (1 << 13) - 1;
    private static final String SCHEMA = "m3.ast.l0-l5/1";

    /** One source-file descriptor. A zero family mask means no collection classification. */
    public record FileFact(String project, String module, String packageName,
                           String path, String sourceSha256, String atomizationReceiptSha256,
                           int collectionFamilies, int[] atomHandles, byte[] atomStateMasks) {
        public FileFact {
            project = token(project, "project");
            module = optionalScope(module, "module");
            packageName = optionalScope(packageName, "packageName");
            path = ASTHierarchy.path(path);
            sourceSha256 = hash(sourceSha256, "sourceSha256");
            atomizationReceiptSha256 = hash(atomizationReceiptSha256, "atomizationReceiptSha256");
            if ((collectionFamilies & ~ALL_COLLECTION_FAMILIES) != 0) {
                throw new IllegalArgumentException("unrecognized/empty collection family mask");
            }
            atomHandles = Objects.requireNonNull(atomHandles, "atomHandles").clone();
            atomStateMasks = Objects.requireNonNull(atomStateMasks, "atomStateMasks").clone();
            if (atomHandles.length == 0 || atomHandles.length != atomStateMasks.length) {
                throw new IllegalArgumentException("atom/mask lane length mismatch");
            }
            for (int atom : atomHandles) {
                if (atom <= 0) throw new IllegalArgumentException("invalid canonical atom handle");
            }
        }
        @Override public int[] atomHandles() { return atomHandles.clone(); }
        @Override public byte[] atomStateMasks() { return atomStateMasks.clone(); }
    }

    /** Immutable dense plane; keys/hashes are per scope, never per AST occurrence. */
    public static final class Plane {
        private final Level level;
        private final String[] keys;
        private final String[] roots;
        private final int[] starts;
        private final int[] lengths;
        private final int[] familyMasks;
        private final int[] occurrenceTotals;

        private Plane(Level level, String[] keys, String[] roots, int[] starts,
                      int[] lengths, int[] familyMasks, int[] occurrenceTotals) {
            this.level = level;
            this.keys = keys;
            this.roots = roots;
            this.starts = starts;
            this.lengths = lengths;
            this.familyMasks = familyMasks;
            this.occurrenceTotals = occurrenceTotals;
        }
        public Level level() { return level; }
        public int size() { return keys.length; }
        public String key(int ordinal) { return keys[Objects.checkIndex(ordinal, size())]; }
        public String root(int ordinal) { return roots[Objects.checkIndex(ordinal, size())]; }
        public int firstChild(int ordinal) { return starts[Objects.checkIndex(ordinal, size())]; }
        public int childCount(int ordinal) { return lengths[Objects.checkIndex(ordinal, size())]; }
        public int familyMask(int ordinal) { return familyMasks[Objects.checkIndex(ordinal, size())]; }
        public int occurrenceCount(int ordinal) { return occurrenceTotals[Objects.checkIndex(ordinal, size())]; }
        /** A parent refers to a contiguous range of child coordinates, never child node references. */
        public int childOrdinal(int parent, int childOffset) {
            Objects.checkIndex(childOffset, childCount(parent));
            return firstChild(parent) + childOffset;
        }
        public int[] childOffsets() { return starts.clone(); }
        public int[] childLengths() { return lengths.clone(); }
    }

    private final String repository;
    private final Plane[] levels;
    private final int[] occurrenceHandles;
    private final byte[] occurrenceMasks;
    private final String root;

    private ASTHierarchy(String repository, Plane[] levels,
                               int[] occurrenceHandles, byte[] occurrenceMasks) {
        this.repository = repository;
        this.levels = levels;
        this.occurrenceHandles = occurrenceHandles;
        this.occurrenceMasks = occurrenceMasks;
        this.root = levels[Level.L5_REPOSITORY.ordinal()].root(0);
    }

    public String repository() { return repository; }
    public Plane level(Level level) { return levels[Objects.requireNonNull(level).ordinal()]; }
    public String rootSha256() { return root; }
    public int totalOccurrences() { return occurrenceHandles.length; }
    public int totalFiles() { return level(Level.L1_FILE_SUMMARY).size(); }
    public int familyMask() { return level(Level.L5_REPOSITORY).familyMask(0); }
    public int atomHandle(int occurrenceCoordinate) {
        return occurrenceHandles[Objects.checkIndex(occurrenceCoordinate, occurrenceHandles.length)];
    }
    public int stateMask(int occurrenceCoordinate) {
        return occurrenceMasks[Objects.checkIndex(occurrenceCoordinate, occurrenceMasks.length)] & 0xff;
    }
    public void requireFamilies(int requiredMask) {
        if ((requiredMask & ~ALL_COLLECTION_FAMILIES) != 0
                || (familyMask() & requiredMask) != requiredMask) {
            throw new IllegalStateException("missing collection-family coverage");
        }
    }

    /** Reproducible serial upward fan-in. Input order cannot affect any resulting root. */
    public static ASTHierarchy build(String repository, Collection<FileFact> files) {
        String repositoryId = token(repository, "repository");
        List<FileFact> ordered = new ArrayList<>(Objects.requireNonNull(files, "files"));
        if (ordered.isEmpty()) throw new IllegalArgumentException("empty repository");
        ordered.sort(Comparator.comparing(FileFact::project)
                .thenComparing(FileFact::module)
                .thenComparing(FileFact::packageName).thenComparing(FileFact::path));
        long total = 0;
        for (FileFact fact : ordered) total += fact.atomHandles.length;
        if (total > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("atom lane exceeds JVM limit");
        int[] handles = new int[(int) total];
        byte[] masks = new byte[(int) total];
        Builder l0 = new Builder(Level.L0_FILE_FACTS);
        Builder l1 = new Builder(Level.L1_FILE_SUMMARY);
        int position = 0;
        String previousIdentity = null;
        for (FileFact file : ordered) {
            String identity = join(file.project, file.module, file.packageName, file.path);
            if (identity.equals(previousIdentity)) throw new IllegalArgumentException("duplicate file identity");
            previousIdentity = identity;
            int start = position;
            System.arraycopy(file.atomHandles, 0, handles, position, file.atomHandles.length);
            System.arraycopy(file.atomStateMasks, 0, masks, position, file.atomStateMasks.length);
            position += file.atomHandles.length;
            String leaf = digestFile(file);
            l0.add(identity, leaf, start, file.atomHandles.length,
                    file.collectionFamilies, file.atomHandles.length);
            String fileSummary = digestOne(Level.L1_FILE_SUMMARY, identity,
                    file.collectionFamilies, file.atomHandles.length, leaf);
            l1.add(identity, fileSummary, l0.size() - 1, 1,
                    file.collectionFamilies, file.atomHandles.length);
        }
        Plane[] planes = new Plane[Level.values().length];
        planes[0] = l0.freeze();
        planes[1] = l1.freeze();
        int[] fileOrigins = new int[ordered.size()];
        for (int i = 0; i < fileOrigins.length; i++) fileOrigins[i] = i;
        AggregateResult packages = aggregate(Level.L2_PACKAGE, planes[1], fileOrigins, ordered);
        planes[2] = packages.plane();
        AggregateResult modules = aggregate(Level.L3_MODULE, planes[2], packages.firstFiles(), ordered);
        planes[3] = modules.plane();
        AggregateResult projectScopes = aggregate(Level.L4_PROJECT, planes[3], modules.firstFiles(), ordered);
        planes[4] = projectScopes.plane();
        Builder top = new Builder(Level.L5_REPOSITORY);
        Plane projects = planes[4];
        top.add(repositoryId, digestRange(Level.L5_REPOSITORY, repositoryId,
                        projectsUnion(projects), projectsTotal(projects), projects, 0, projects.size()),
                0, projects.size(), projectsUnion(projects), projectsTotal(projects));
        planes[5] = top.freeze();
        verifyContiguous(planes, handles.length);
        return new ASTHierarchy(repositoryId, planes, handles, masks);
    }

    private record AggregateResult(Plane plane, int[] firstFiles) { }

    private static AggregateResult aggregate(Level level, Plane child,
                                             int[] childFirstFiles, List<FileFact> ordered) {
        Builder out = new Builder(level);
        int[] firstFileByScope = new int[child.size()];
        int written = 0;
        int first = 0;
        while (first < child.size()) {
            int fileId = childFirstFiles[first];
            String key = parentKey(level, ordered.get(fileId));
            int end = first + 1;
            while (end < child.size() && parentKey(level,
                    ordered.get(childFirstFiles[end])).equals(key)) end++;
            int union = 0, occurrences = 0;
            for (int i = first; i < end; i++) {
                union |= child.familyMask(i);
                occurrences = Math.addExact(occurrences, child.occurrenceCount(i));
            }
            out.add(key, digestRange(level, key, union, occurrences, child, first, end),
                    first, end - first, union, occurrences);
            firstFileByScope[written++] = fileId;
            first = end;
        }
        return new AggregateResult(out.freeze(), Arrays.copyOf(firstFileByScope, written));
    }

    private static String parentKey(Level level, FileFact source) {
        return switch (level) {
            case L2_PACKAGE -> join(source.project, source.module, source.packageName);
            case L3_MODULE -> join(source.project, source.module);
            case L4_PROJECT -> source.project;
            default -> throw new IllegalArgumentException("invalid fan-in level");
        };
    }

    private static int projectsUnion(Plane plane) {
        int result = 0;
        for (int i = 0; i < plane.size(); i++) result |= plane.familyMask(i);
        return result;
    }
    private static int projectsTotal(Plane plane) {
        int result = 0;
        for (int i = 0; i < plane.size(); i++) result = Math.addExact(result, plane.occurrenceCount(i));
        return result;
    }

    private static String digestFile(FileFact f) {
        MessageDigest d = digestEngine();
        append(d, SCHEMA); append(d, "L0");
        append(d, f.project); append(d, f.module); append(d, f.packageName); append(d, f.path);
        append(d, f.sourceSha256); append(d, f.atomizationReceiptSha256);
        integer(d, f.collectionFamilies); integer(d, f.atomHandles.length);
        for (int i = 0; i < f.atomHandles.length; i++) {
            integer(d, f.atomHandles[i]); d.update(f.atomStateMasks[i]);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    private static String digestOne(Level level, String key, int familyMask,
                                    int occurrences, String childHash) {
        MessageDigest d = digestEngine();
        append(d, SCHEMA); append(d, level.name()); append(d, key);
        integer(d, familyMask); integer(d, occurrences); integer(d, 1);
        append(d, childHash);
        return HexFormat.of().formatHex(d.digest());
    }
    private static String digestRange(Level level, String key, int familyMask,
                                      int occurrences, Plane children, int start, int end) {
        MessageDigest d = digestEngine();
        append(d, SCHEMA); append(d, level.name()); append(d, key);
        integer(d, familyMask); integer(d, occurrences); integer(d, end - start);
        for (int index = start; index < end; index++) append(d, children.root(index));
        return HexFormat.of().formatHex(d.digest());
    }
    private static MessageDigest digestEngine() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException never) { throw new ExceptionInInitializerError(never); }
    }
    private static void integer(MessageDigest d, int value) {
        d.update((byte) (value >>> 24));
        d.update((byte) (value >>> 16));
        d.update((byte) (value >>> 8));
        d.update((byte) value);
    }
    private static void append(MessageDigest d, String value) {
        if (wellFormedUtf16(value)) {
            // Preserve every existing well-formed identifier's v1 hash encoding.
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            integer(d, bytes.length); d.update(bytes);
        } else {
            // 0xff cannot occur in valid UTF-8. This disjoint framed extension
            // preserves every accepted UTF-16 code unit without replacement.
            integer(d, Math.addExact(1, Math.multiplyExact(value.length(), Character.BYTES)));
            d.update((byte) 0xff);
            for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                d.update((byte) (unit >>> Byte.SIZE));
                d.update((byte) unit);
            }
        }
    }

    private static boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index)))
                    return false;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }
    private static String hash(String s, String name) {
        if (s == null || !s.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name);
        return s;
    }
    private static String token(String s, String name) {
        if (s == null || s.isBlank() || s.length() > 512 || s.indexOf('\u001f') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) throw new IllegalArgumentException(name);
        return s;
    }
    /** Empty scope coordinates preserve unnamed Java modules and packages. */
    private static String optionalScope(String value, String name) {
        if (value != null && value.isEmpty()) return value;
        return token(value, name);
    }
    private static String path(String s) {
        s = token(s, "path");
        if (s.startsWith("/") || s.contains("\\") || s.endsWith("/") || s.contains("//")) {
            throw new IllegalArgumentException("not a normalized relative path");
        }
        for (String component : s.split("/")) {
            if (component.equals(".") || component.equals("..") || component.isBlank())
                throw new IllegalArgumentException("path traversal");
        }
        return s;
    }
    private static String join(String... s) {
        return String.join("\u001f", s);
    }
    private static void verifyContiguous(Plane[] planes, int atoms) {
        for (int level = 0; level < planes.length; level++) {
            Plane v = planes[level];
            int expected = 0;
            for (int i = 0; i < v.size(); i++) {
                if (v.firstChild(i) != expected || v.childCount(i) <= 0) {
                    throw new IllegalStateException("noncontiguous L" + level + " child range");
                }
                expected = Math.addExact(expected, v.childCount(i));
            }
            int childTotal = level == 0 ? atoms : planes[level - 1].size();
            if (expected != childTotal) throw new IllegalStateException("missing L" + level + " child");
        }
    }

    /** Geometric primitive lanes: neither boxed Integer nor Entry/Node per scope. */
    private static final class Builder {
        private final Level level;
        private String[] keys = new String[0];
        private String[] roots = new String[0];
        private int[] starts = new int[0];
        private int[] lengths = new int[0];
        private int[] family = new int[0];
        private int[] totals = new int[0];
        private int size;
        Builder(Level level) { this.level = level; }
        int size() { return size; }
        private void grow() {
            if (size < keys.length) return;
            int next = keys.length == 0 ? 8 : Math.addExact(keys.length, (keys.length >>> 1) + 1);
            keys = Arrays.copyOf(keys, next);
            roots = Arrays.copyOf(roots, next);
            starts = Arrays.copyOf(starts, next);
            lengths = Arrays.copyOf(lengths, next);
            family = Arrays.copyOf(family, next);
            totals = Arrays.copyOf(totals, next);
        }
        void add(String key, String digest, int start, int length, int mask, int occurrences) {
            grow();
            keys[size] = key; roots[size] = digest; starts[size] = start;
            lengths[size] = length; family[size] = mask; totals[size] = occurrences;
            size++;
        }
        Plane freeze() {
            return new Plane(level, Arrays.copyOf(keys, size), Arrays.copyOf(roots, size),
                    Arrays.copyOf(starts, size), Arrays.copyOf(lengths, size),
                    Arrays.copyOf(family, size), Arrays.copyOf(totals, size));
        }
    }
}
