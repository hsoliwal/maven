// Copyright 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast.internal;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Independent IdentityHashMap oracle plus a real, attributed JavacTask fixture.
 * Source strings that look like Java declarations remain ordinary string data.
 */
//FLLDCIM: RELOCATE - copy of M3JDK m3/ast/src/test/java/jdk/internal/m3/ast/NullFlowVariableIdsProbe.java at b7539452eb6c
public final class NullFlowVariableIdsProbe {
    private static final String SEAL = "0123456789abcdef".repeat(4);
    private static long assertions;

    private NullFlowVariableIdsProbe() { }

    public static void main(String[] args) throws Exception {
        System.out.println("PASS NULL_FLOW_SYMBOLS attributed_contract_assertions=" + prove());
    }

    public static long prove() throws Exception {
        assertions = 0;
        scalarIdentityCases();
        attributedCompilerCases();
        return assertions;
    }

    private static void scalarIdentityCases() {
        NullFlowVariableIds ids = new NullFlowVariableIds(SEAL, "method#0");
        IdentityHashMap<Object, Integer> oracle = new IdentityHashMap<>();
        Object[] objects = new Object[8_193];
        SplittableRandom random = new SplittableRandom(0x1D5_2026_1031L);
        // Equal text must not make two attributed symbol identities equivalent.
        objects[0] = new String("same-name");
        objects[1] = new String("same-name");
        equal(objects[0], objects[1]);
        for (int i = 2; i < objects.length; i++) objects[i] = new Object();
        for (int i = 0; i < 35_000; i++) {
            Object symbol = objects[random.nextInt(objects.length)];
            Integer expected = oracle.get(symbol);
            if (expected == null) {
                expected = oracle.size();
                oracle.put(symbol, expected);
            }
            equal(expected, ids.intern(symbol));
            equal(expected, ids.findId(symbol));
        }
        for (Object symbol : objects) {
            Integer expected = oracle.get(symbol);
            equal(expected == null ? -1 : expected, ids.findId(symbol));
        }
        equal(oracle.size(), ids.variableCount());
        equal(-1, ids.findId(new String("same-name")));
        equal(-1, ids.findId(new Object()));
        ids.requireGeneration(SEAL, "method#0");
        fails(IllegalStateException.class,
                () -> ids.requireGeneration("0".repeat(64), "method#0"));
        fails(IllegalStateException.class,
                () -> ids.requireGeneration(SEAL, "method#1"));
        fails(IllegalArgumentException.class, () -> ids.requireId(new Object()));
        fails(NullPointerException.class, () -> ids.findId(null));
        fails(NullPointerException.class, () -> ids.intern(null));
        fails(IllegalStateException.class, ids::newFacts);
        ids.freeze();
        equal(true, ids.frozen());
        equal(oracle.size(), ids.newFacts().variableCount());
        for (Object symbol : objects) {
            Integer expected = oracle.get(symbol);
            equal(expected == null ? -1 : expected, ids.findId(symbol));
        }
        fails(IllegalStateException.class, () -> ids.intern(objects[0]));
        fails(IllegalStateException.class, ids::freeze);
        fails(IllegalArgumentException.class,
                () -> new NullFlowVariableIds("x", "method#0"));
        fails(NullPointerException.class,
                () -> new NullFlowVariableIds(SEAL, null));
        fails(IllegalArgumentException.class,
                () -> new NullFlowVariableIds(SEAL, "\n"));

        NullFlowVariableIds newGeneration = new NullFlowVariableIds(SEAL, "method#1");
        equal(-1, newGeneration.findId(objects[0]));
        equal(0, newGeneration.intern(objects[0]));
        newGeneration.requireGeneration(SEAL, "method#1");
    }

    private static void attributedCompilerCases() throws Exception {
        String source = String.join("\n",
                "package proof;",
                "import java.util.function.IntUnaryOperator;",
                "class Bindings {",
                "    int x;",
                "    int first(int x) {",
                "        final int captured = x + 1;",
                "        IntUnaryOperator op = z -> captured + z;",
                "        int total = 0;",
                "        for (int i = 0; i < 3; i++) {",
                "            total += op.applyAsInt(i);",
                "        }",
                "        Object candidate = total;",
                "        if (candidate instanceof Integer value) total += value;",
                "        String decoy = \"\"\"",
                "                int captured = x; class Fake {}",
                "                \"\"\";",
                "        return total + decoy.length();",
                "    }",
                "    int second(int x) {",
                "        try (AutoCloseable close = () -> {}) {",
                "            java.util.Objects.requireNonNull(close);",
                "            return x + 1;",
                "        } catch (Exception failure) {",
                "            return x - 1;",
                "        }",
                "    }",
                "    void third() {",
                "        Runnable r = new Runnable() {",
                "            public void run() { int x = 7; System.out.println(x); }",
                "        };",
                "        r.run();",
                "    }",
                "}",
                "record Pair(int left, int right) {",
                "    int sum() { return left + right; }",
                "}") + "\n";
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("real JDK compiler unavailable");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject input = new SimpleJavaFileObject(
                URI.create("string:///proof/Bindings.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "17", "-proc:none", "-Xlint:all", "-Werror"),
                    null, List.of(input));
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw new AssertionError("real JavacTask failed: " + diagnostics.getDiagnostics());
                }
            }
            equal(1, parsed.size());
            String sourceSha256 = sha256(source);
            SymbolScanner scanner = new SymbolScanner(Trees.instance(task), sourceSha256);
            parsed.forEach(root -> scanner.scan(root, null));
            equal(true, scanner.methods >= 4);
            equal(true, scanner.declarations >= 8);
            equal(true, scanner.references >= 8);
        }
    }

    private static final class SymbolScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final String sourceSha256;
        private NullFlowVariableIds current;
        private IdentityHashMap<Element, Integer> oracle;
        private int methods;
        private int declarations;
        private int references;

        private SymbolScanner(Trees trees, String sourceSha256) {
            this.trees = trees;
            this.sourceSha256 = sourceSha256;
        }

        @Override public Void visitMethod(MethodTree node, Void unused) {
            NullFlowVariableIds parent = current;
            IdentityHashMap<Element, Integer> parentOracle = oracle;
            String identity = node.getName() + "#" + methods++;
            current = new NullFlowVariableIds(sourceSha256, identity);
            oracle = new IdentityHashMap<>();
            Void result = super.visitMethod(node, unused);
            current.requireGeneration(sourceSha256, identity);
            equal(oracle.size(), current.variableCount());
            current.freeze();
            equal(true, current.frozen());
            equal(oracle.size(), current.newFacts().variableCount());
            current = parent;
            oracle = parentOracle;
            return result;
        }

        @Override public Void visitVariable(VariableTree node, Void unused) {
            Element symbol = trees.getElement(getCurrentPath());
            if (current != null && symbol instanceof VariableElement
                    && methodLocal(symbol.getKind())) {
                Integer expected = oracle.get(symbol);
                if (expected == null) {
                    expected = oracle.size();
                    oracle.put(symbol, expected);
                }
                equal(expected, current.intern(symbol));
                declarations++;
            }
            return super.visitVariable(node, unused);
        }

        @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
            Element symbol = trees.getElement(getCurrentPath());
            if (current != null && symbol instanceof VariableElement
                    && methodLocal(symbol.getKind())) {
                Integer expected = oracle.get(symbol);
                if (expected != null) {
                    equal(expected, current.requireId(symbol));
                    references++;
                }
            }
            return super.visitIdentifier(node, unused);
        }

        private static boolean methodLocal(ElementKind kind) {
            return switch (Objects.requireNonNull(kind)) {
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER,
                        RESOURCE_VARIABLE, BINDING_VARIABLE -> true;
                default -> false;
            };
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void fails(Class<? extends Throwable> expected, Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("wrong refusal", error);
        }
        throw new AssertionError("expected " + expected.getName());
    }
}
