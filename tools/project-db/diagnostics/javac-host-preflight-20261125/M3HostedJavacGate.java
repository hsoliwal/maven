// SPDX-License-Identifier: Apache-2.0
// Read-only host diagnostic; NOT an alternate compiler, fingerprint or admission rule.
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Inspect the *actual running JVM's* toolchain with the original Maven gate. */
public final class M3HostedJavacGate {
    private static final int MAX_ENTRIES = 262_144;
    private static final int MAX_LINKS = 4_096;
    private static final long MAX_LINK_BYTES = 64L << 20;
    private static final long MAX_TOTAL_BYTES = 256L << 20;
    private static final List<String> SCOPES = List.of("bin", "lib", "conf");
    private static final List<String> INJECTION_KEYS = List.of(
            "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
            "DYLD_FALLBACK_LIBRARY_PATH");
    private M3HostedJavacGate() {}

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    /** Advisory hash for a linked regular target. Never use this for production cache admission. */
    private static String linkedBytes(Path link, BasicFileAttributes before, long[] total) throws IOException {
        if (before.size() > MAX_LINK_BYTES || total[0] + before.size() > MAX_TOTAL_BYTES)
            return "NOT_HASHED_BUDGET";
        MessageDigest hash = digest();
        long length = 0;
        try (InputStream in = Files.newInputStream(link)) {
            byte[] block = new byte[65_536];
            for (int n; (n = in.read(block)) != -1;) {
                if (n == 0 || (length += n) > MAX_LINK_BYTES) return "NOT_HASHED_BUDGET";
                hash.update(block, 0, n);
            }
        } catch (IOException unavailable) { return "NOT_HASHED_UNAVAILABLE"; }
        BasicFileAttributes after;
        try { after = Files.readAttributes(link, BasicFileAttributes.class); }
        catch (IOException unavailable) { return "NOT_HASHED_CHANGED"; }
        if (!after.isRegularFile() || length != before.size() || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !java.util.Objects.equals(after.fileKey(), before.fileKey())) return "NOT_HASHED_CHANGED";
        total[0] += length;
        return shaDigest(hash);
    }
    private static String shaDigest(MessageDigest value) { return HexFormat.of().formatHex(value.digest()); }

    /** Bounds traversal, never follows linked directories or emits link target text or env values. */
    private static List<String> describe(Path home) throws Exception {
        Path root = home.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("invalid JDK root");
        Method safeTree = Class.forName("io.synexia.maven.polyglot.JdkReuseInputs")
                .getDeclaredMethod("safeTree", Path.class);
        safeTree.setAccessible(true);
        Method toolchain = Class.forName("io.synexia.maven.polyglot.JdkReuseInputs")
                .getDeclaredMethod("toolchain", Path.class);
        toolchain.setAccessible(true);
        List<String> lines = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        lines.add("M3_HOSTED_JAVAC_GATE_PREFLIGHT_V1");
        lines.add("javaHomeSha256=" + sha(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lines.add("jdkMajor=" + Runtime.version().feature());
        String selected = System.getenv("JAVA_HOME");
        lines.add("javaHomeMatchesSetup=" + (selected != null
                && Path.of(selected).toAbsolutePath().normalize().equals(root)));
        lines.add("cacertsIsLink=" + Files.isSymbolicLink(root.resolve("lib/security/cacerts")));
        TreeSet<String> injections = new TreeSet<>();
        for (Map.Entry<String,String> item : System.getenv().entrySet()) {
            String name = item.getKey();
            if (!item.getValue().isBlank() && (INJECTION_KEYS.contains(name)
                    || name.startsWith("LD_") || name.startsWith("DYLD_"))) injections.add(name);
        }
        lines.add("injectionVariableNames=" + String.join(",", injections));
        AtomicInteger entries = new AtomicInteger();
        AtomicInteger links = new AtomicInteger();
        long[] bytes = {0};
        for (String scope : SCOPES) {
            Path tree = root.resolve(scope);
            if (Files.isSymbolicLink(tree) || !Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("invalid JDK scope: " + scope);
            lines.add("originalSafeTree(" + scope + ")=" + safeTree.invoke(null, tree));
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                private void count() throws IOException {
                    if (entries.incrementAndGet() > MAX_ENTRIES) throw new IOException("entry limit exceeded");
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    count();
                    if (!attrs.isDirectory()) throw new IOException("unexpected directory type");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    count();
                    if (attrs.isSymbolicLink()) {
                        if (links.incrementAndGet() > MAX_LINKS) throw new IOException("link limit exceeded");
                        String relative = root.relativize(path).toString().replace('\\','/');
                        String linkTextHash = sha(Files.readSymbolicLink(path).toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        String kind;
                        String targetHash = "NOT_HASHED";
                        String targetScope;
                        try {
                            Path effective = path.toRealPath();
                            targetScope = effective.startsWith(root) ? "inside-jdk" : "external";
                        } catch (IOException unavailable) { targetScope = "unresolved"; }
                        try {
                            BasicFileAttributes target = Files.readAttributes(path, BasicFileAttributes.class);
                            if (target.isRegularFile()) {
                                kind = "regular";
                                targetHash = linkedBytes(path, target, bytes);
                            } else kind = target.isDirectory() ? "directory-not-followed" : "special-refused";
                        } catch (IOException invalid) { kind = "dangling-or-cycle"; }
                        observations.add("link=" + relative + " kind=" + kind
                                + " targetScope=" + targetScope
                                + " linkTextSha256=" + linkTextHash + " effectiveSha256=" + targetHash);
                    } else if (!attrs.isRegularFile()) {
                        observations.add("special=" + root.relativize(path).toString().replace('\\','/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        observations.sort(String::compareTo);
        lines.addAll(observations);
        @SuppressWarnings("unchecked")
        List<Path> eligible = (List<Path>) toolchain.invoke(null, root);
        lines.add("originalToolchainInputCount=" + eligible.size());
        lines.add("linkCount=" + links.get());
        lines.add("scannedEntries=" + entries.get());
        lines.add("linkedBytesRead=" + bytes[0]);
        return List.copyOf(lines);
    }

    private static void assertGate(boolean check, String label) {
        if (!check) throw new AssertionError(label);
    }
    private static void selfTest() throws Exception {
        Path base = Files.createTempDirectory("m3-host-javac-test-");
        Path home = Files.createDirectory(base.resolve("jdk"));
        try {
            for (String scope : SCOPES) Files.createDirectory(home.resolve(scope));
            Files.write(home.resolve("bin/java"), new byte[]{1});
            assertGate(home.resolve("bin/java").toFile().setExecutable(true, true), "synthetic java executable");
            Files.write(home.resolve("lib/modules"), new byte[]{2});
            Files.write(home.resolve("release"), new byte[]{3});
            Path security = Files.createDirectory(home.resolve("lib/security"));
            Path cacerts = security.resolve("cacerts");
            Files.write(cacerts, "AAAA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<String> clean = describe(home);
            assertGate(clean.contains("originalToolchainInputCount=4"), "ordinary admission");
            Path target = base.resolve("external-cacerts");
            Files.write(target, "AAAA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.delete(cacerts);
            Files.createSymbolicLink(cacerts, target);
            List<String> linked = describe(home);
            assertGate(linked.contains("originalToolchainInputCount=0"), "linked refusal");
            assertGate(linked.stream().anyMatch(s -> s.contains("link=lib/security/cacerts kind=regular targetScope=external")), "link recorded");
            assertGate(linked.contains("cacertsIsLink=true"), "CA link classified");
            String a = linked.stream().filter(s -> s.startsWith("link=")).findFirst().orElseThrow();
            FileTime old = Files.getLastModifiedTime(target);
            Files.write(target, "BBBB".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.setLastModifiedTime(target, old);
            String b = describe(home).stream().filter(s -> s.startsWith("link=")).findFirst().orElseThrow();
            assertGate(!a.equals(b), "target byte difference with same metadata");
            Files.delete(cacerts);
            Files.createSymbolicLink(cacerts, Path.of("cacerts"));
            assertGate(describe(home).stream().anyMatch(s -> s.contains("kind=dangling-or-cycle")), "cycle observed");
            Files.delete(cacerts);
            Files.createSymbolicLink(cacerts, Path.of("missing"));
            assertGate(describe(home).stream().anyMatch(s -> s.contains("kind=dangling-or-cycle")), "dangling observed");
            Files.delete(cacerts);
            Path alias = home.resolve("lib/linked-conf");
            Files.createSymbolicLink(alias, Path.of("../conf"));
            assertGate(describe(home).stream().anyMatch(s -> s.contains("link=lib/linked-conf kind=directory-not-followed")), "linked directory not traversed");
            Files.delete(alias);
            Files.write(cacerts, "AAAA".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertGate(describe(home).contains("originalToolchainInputCount=4"), "ordinary restored");
            System.out.println("M3_HOSTED_JAVAC_GATE_SELF_TEST=PASS 9 controls");
        } finally {
            Files.deleteIfExists(home.resolve("lib/security/cacerts"));
            Files.deleteIfExists(home.resolve("lib/security"));
            Files.deleteIfExists(home.resolve("lib/modules"));
            Files.deleteIfExists(home.resolve("bin/java"));
            for (String scope : SCOPES) Files.deleteIfExists(home.resolve(scope));
            Files.deleteIfExists(home.resolve("release"));
            Files.deleteIfExists(home);
            Files.deleteIfExists(base.resolve("external-cacerts"));
            Files.deleteIfExists(base);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        if (args.length != 1 || !"--inspect-current-jdk".equals(args[0]))
            throw new IllegalArgumentException("usage: --self-test | --inspect-current-jdk");
        for (String line : describe(Path.of(System.getProperty("java.home")))) System.out.println(line);
    }
}
