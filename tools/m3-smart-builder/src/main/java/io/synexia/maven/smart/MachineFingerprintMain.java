// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** JDK 21 AST-only atom fingerprint producer; no type attribution, refactoring, or behavior proof. */
public final class MachineFingerprintMain {
    private static final Set<String> OPTIONS = Set.of("--project-dir", "--build-dir", "--source-root",
            "--preparation-report", "--atom-pattern-manifest", "--workers");
    private static final int MAX_ATOMS = 4096;

    private MachineFingerprintMain() { }

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    static int run(String[] argv, PrintStream out, PrintStream err) {
        Path destination = null;
        try {
            Map<String, String> options = parse(argv);
            Path base = Path.of(options.getOrDefault("--project-dir", ".")).toAbsolutePath().normalize();
            Path source = base.resolve(required(options, "--source-root")).toAbsolutePath().normalize();
            Path build = base.resolve(options.getOrDefault("--build-dir", "target")).toAbsolutePath().normalize();
            if (build.resolve("m3-smart").startsWith(source))
                throw new IOException("fingerprint outputs overlap Java input scope");
            destination = SafeBuildOutput.directory(base, build);
            Path prepFile = base.resolve(required(options, "--preparation-report")).toAbsolutePath().normalize();
            Path atomFile = base.resolve(required(options, "--atom-pattern-manifest")).toAbsolutePath().normalize();
            String preparation = CanonicalPreparationEvidence.inspect(base, source, prepFile);
            AtomPatternEvidence.Summary atom = AtomPatternEvidence.inspect(base, source, atomFile);
            SourceInventory.Selected before = SourceInventory.inspectSelected(base, source);
            if (before.summary().javaFiles() != before.summary().files())
                throw new IOException("fingerprint producer requires a Java-only selected source root");
            int workers = Integer.parseInt(options.getOrDefault("--workers", "1"));
            if (workers < 1 || workers > 8) throw new IOException("worker count outside 1..8");
            List<String> lines = produce(source, before, workers);
            if (lines.size() > MAX_ATOMS) throw new IOException("fingerprint row budget exceeded");
            if (!preparation.equals(CanonicalPreparationEvidence.inspect(base, source, prepFile))
                    || !atom.equals(AtomPatternEvidence.inspect(base, source, atomFile))
                    || !before.equals(SourceInventory.inspectSelected(base, source)))
                throw new IOException("source or canonical evidence changed during AST fingerprinting");
            StringBuilder tsv = new StringBuilder(MachineEvidence.HEADER).append('\n');
            for (String line : lines) tsv.append(line).append('\n');
            SafeBuildOutput.write(destination, "machine-evidence.tsv", tsv.toString());
            MachineEvidence.Summary accepted = MachineEvidence.inspect(base, source, destination.resolve("machine-evidence.tsv"));
            if (accepted.atoms() != lines.size()) throw new IOException("AST fingerprint output did not revalidate");
            String receipt = "{\"schema\":\"m3.smart.machine-fingerprint-producer.v1\","
                    + "\"status\":\"syntax-only\",\"implementation\":\"JDK21_JAVAC_AST\","
                    + "\"sourceDigest\":" + TinyJson.quote(before.summary().digest())
                    + ",\"preparationFacts\":" + preparation
                    + ",\"atomPatternFacts\":" + atom.json()
                    + ",\"fingerprints\":" + accepted.json()
                    + ",\"workers\":" + workers
                    + ",\"typeAttributionExecuted\":false,\"physicalAtomizationExecuted\":false,"
                    + "\"semanticProofAuthenticated\":false,\"promotionAuthority\":false}\n";
            SafeBuildOutput.write(destination, "machine-fingerprints.json", receipt);
            out.println("M3 AST machine fingerprints: " + destination.resolve("machine-evidence.tsv"));
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refused(destination, "InterruptedException", err);
        } catch (Exception refused) {
            return refused(destination, refused.getClass().getSimpleName(), err);
        }
    }

    private record Atom(Tree tree, String signature) { }

    private static List<String> produce(Path source, SourceInventory.Selected inventory, int workers)
            throws IOException, InterruptedException {
        var pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<List<String>>> tasks = new ArrayList<>();
            for (SourceInventory.FileFact file : inventory.files())
                tasks.add(pool.submit(() -> fingerprintFile(source.resolve(file.path()), file)));
            List<String> all = new ArrayList<>();
            for (Future<List<String>> task : tasks) {
                try { all.addAll(task.get()); }
                catch (ExecutionException failed) {
                    if (failed.getCause() instanceof IOException io) throw io;
                    throw new IOException("JDK AST fingerprinting refused", failed.getCause());
                }
                if (all.size() > MAX_ATOMS) throw new IOException("fingerprint row budget exceeded");
            }
            return List.copyOf(all);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> fingerprintFile(Path path, SourceInventory.FileFact source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("JDK compiler AST API unavailable");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<Atom> atoms = new ArrayList<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-source", "21"),
                    null, files.getJavaFileObjectsFromPaths(List.of(path)));
            for (CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(MethodTree method, Void ignored) {
                        atoms.add(new Atom(method, signature(method)));
                        return super.visitMethod(method, ignored);
                    }
                    @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Void ignored) {
                        atoms.add(new Atom(lambda, "lambda:" + lambda.getParameters().size()));
                        return super.visitLambdaExpression(lambda, ignored);
                    }
                    @Override public Void visitClass(ClassTree type, Void ignored) {
                        for (Tree member : type.getMembers())
                            if (member instanceof BlockTree block)
                                atoms.add(new Atom(block, "initializer:" + block.isStatic()));
                        return super.visitClass(type, ignored);
                    }
                }.scan(unit, null);
                if (atoms.isEmpty()) atoms.add(new Atom(unit, "unit:" + source.path()));
            }
        }
        if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)
                || atoms.isEmpty()) throw new IOException("Java syntax cannot be fingerprinted");
        if (!source.sha256().equals(Hashes.sha256(path)))
            throw new IOException("Java source changed while compiling AST inventory");
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < atoms.size(); i++) {
            Fingerprint fp = Fingerprint.of(atoms.get(i).tree());
            String id = String.format(Locale.ROOT, "a%06d", i + 1);
            rows.add(source.path() + "\t" + source.sha256() + "\t" + id
                    + "\t" + fp.structural() + "\t" + fp.logic() + "\t" + fp.simHash()
                    + "\t" + Hashes.sha256(atoms.get(i).signature().getBytes(StandardCharsets.UTF_8)));
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    private static String signature(MethodTree method) {
        StringBuilder result = new StringBuilder("method:").append(method.getName())
                .append(':').append(method.getReturnType() == null ? "constructor" : method.getReturnType())
                .append(":modifiers:");
        method.getModifiers().getFlags().stream().map(Object::toString).sorted().forEach(flag -> result.append(flag).append(','));
        for (var parameter : method.getParameters()) result.append(":parameter:").append(parameter.getType());
        for (var thrown : method.getThrows()) result.append(":throws:").append(thrown);
        return result.toString();
    }

    private record Fingerprint(String structural, String logic, String simHash) {
        // A finite JDK AST kind vocabulary is indexed once rather than hashed for every node.
        private static final long[] KIND_FEATURES = kindFeatures();

        private static long[] kindFeatures() {
            Tree.Kind[] kinds = Tree.Kind.values();
            long[] features = new long[kinds.length];
            for (Tree.Kind kind : kinds)
                features[kind.ordinal()] = ByteBuffer.wrap(Hashes.digest()
                        .digest(kind.name().getBytes(StandardCharsets.UTF_8))).getLong();
            return features;
        }

        static Fingerprint of(Tree tree) {
            MessageDigest shape = Hashes.digest();
            MessageDigest logic = Hashes.digest();
            int[] weights = new int[64];
            new TreeScanner<Void, Void>() {
                @Override public Void scan(Tree node, Void unused) {
                    if (node == null) return null;
                    String kind = node.getKind().name();
                    update(shape, "<" + kind);
                    update(logic, "<" + kind);
                    long feature = KIND_FEATURES[node.getKind().ordinal()];
                    for (int bit = 0; bit < 64; bit++) weights[bit] += (feature >>> bit & 1L) == 1L ? 1 : -1;
                    if (node instanceof LiteralTree literal) update(logic, "value:" + Hashes.sha256(
                            String.valueOf(literal.getValue()).getBytes(StandardCharsets.UTF_8)));
                    else if (node instanceof MemberSelectTree select) update(logic, "member:" + select.getIdentifier());
                    else if (node instanceof IdentifierTree) update(logic, "identifier");
                    super.scan(node, unused);
                    update(shape, ">" + kind);
                    update(logic, ">" + kind);
                    return null;
                }
            }.scan(tree, null);
            long simHash = 0;
            for (int bit = 0; bit < 64; bit++) if (weights[bit] >= 0) simHash |= 1L << bit;
            return new Fingerprint(java.util.HexFormat.of().formatHex(shape.digest()),
                    java.util.HexFormat.of().formatHex(logic.digest()),
                    String.format(Locale.ROOT, "%016x", simHash));
        }
        private static void update(MessageDigest digest, String token) {
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private static int refused(Path directory, String error, PrintStream output) {
        if (directory != null) try {
            // Replace a prior successful ledger so a failed regeneration cannot be replayed as current.
            SafeBuildOutput.write(directory, "machine-evidence.tsv", "# m3.machine-evidence.refused\n");
            SafeBuildOutput.write(directory, "machine-fingerprints.json", "{\"schema\":\"m3.smart.machine-fingerprint-producer.v1\","
                    + "\"status\":\"refused\",\"errorType\":" + TinyJson.quote(error)
                    + ",\"semanticProofAuthenticated\":false,\"promotionAuthority\":false}\n");
        } catch (IOException ignored) { /* Refusal remains nonzero. */ }
        output.println("M3 AST machine fingerprint producer refused: " + error);
        return 2;
    }

    private static Map<String, String> parse(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("option/value pairs required");
        Map<String, String> parsed = new TreeMap<>();
        for (int i = 0; i < args.length; i += 2)
            if (!OPTIONS.contains(args[i]) || parsed.putIfAbsent(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("unknown or repeated option");
        return parsed;
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing producer input");
        return value;
    }
}
