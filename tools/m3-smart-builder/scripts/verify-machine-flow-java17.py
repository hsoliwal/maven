#!/usr/bin/env python3
"""Java 17 release compilation gate for the *real* bounded compiler may-flow class.

Compiles the original source with only a strict test-local Hashes stub. It does
not compile against a fake MachineFlowAnalysis, skip any branches, or change the
production implementation. The main Maven reactor still supplies full tests.
"""
from pathlib import Path
import os
import subprocess
import tempfile

SOURCE = (Path(__file__).resolve().parents[1] / "src/main/java/io/synexia/maven/smart/MachineFlowAnalysis.java")
text = SOURCE.read_text(encoding="utf-8")
assert text.count("switch (atom.getLeaf())") == 0, "original Java 21 pattern switch remains"
assert text.count("java.util.Objects.requireNonNull(atom.getLeaf()) instanceof MethodTree method") == 2
assert "instanceof BlockTree block ? block : null" in text
assert "lambda.getParameters()" in text

STUB = """package io.synexia.maven.smart;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
final class Hashes {
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
"""

PROBE = """package io.synexia.maven.smart;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
final class MachineFlowJava17RuntimeProof {
    public static void main(String[] arguments) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new AssertionError("missing original JDK compiler");
        JavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///MachineFlowFixture.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) {
                return "class MachineFlowFixture { "
                    + "void run(int value) { int x = value; if (x > 0) { x++; } } "
                    + "void other(int value) { Runnable r = () -> { int y = value; }; r.run(); } "
                    + "}";
            }
        };
        JavacTask task = (JavacTask) javac.getTask(null, null, null,
                List.of("--release", "17", "-proc:none"), null, List.of(source));
        Iterable<? extends CompilationUnitTree> parsed = task.parse();
        task.analyze();
        Trees trees = Trees.instance(task);
        int[] visited = {0};
        for (CompilationUnitTree unit : parsed) {
            new TreePathScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree method, Void context) {
                    if ("run".contentEquals(method.getName()) || "other".contentEquals(method.getName())) {
                        try {
                            MachineFlowAnalysis.Facts facts = MachineFlowAnalysis.inspect(trees, getCurrentPath());
                            if (facts.nodes() < 3 || facts.edges() < 2
                                    || facts.cfg() == null || facts.cfg().isBlank()) {
                                throw new AssertionError("incomplete original method CFG evidence");
                            }
                            visited[0]++;
                        } catch (Exception failure) {
                            throw new AssertionError("original MachineFlowAnalysis inspection failed", failure);
                        }
                    }
                    return super.visitMethod(method, context);
                }
            }.scan(unit, null);
        }
        if (visited[0] != 2) throw new AssertionError("expected two real compiler methods, got " + visited[0]);
        System.out.println("PASS: real MachineFlowAnalysis compiled for Java 17 and inspected 2 javac methods");
    }
}
"""

with tempfile.TemporaryDirectory(prefix="m3-machine-flow-java17-") as directory:
    root = Path(directory)
    package = root / "io" / "synexia" / "maven" / "smart"
    package.mkdir(parents=True)
    stub = package / "Hashes.java"
    probe = package / "MachineFlowJava17RuntimeProof.java"
    stub.write_text(STUB, encoding="utf-8")
    probe.write_text(PROBE, encoding="utf-8")
    output = root / "classes"
    output.mkdir()
    java = os.environ.get("JAVA_BIN", "java")
    javac = os.environ.get("JAVAC_BIN", "javac")
    compile = subprocess.run([javac, "--release", "17", "-proc:none", "-d", str(output),
                              str(SOURCE), str(stub), str(probe)],
                             capture_output=True, text=True)
    if compile.returncode:
        raise SystemExit(f"FAIL: Java 17 source compile failed\n{compile.stdout}\n{compile.stderr}")
    run = subprocess.run([java, "-cp", str(output),
                          "io.synexia.maven.smart.MachineFlowJava17RuntimeProof"],
                         capture_output=True, text=True)
    if run.returncode:
        raise SystemExit(f"FAIL: original Java compiler flow check failed\n{run.stdout}\n{run.stderr}")
    print(run.stdout.strip())
    print("PASS: Java 17 release compatibility is checked against production source, not a replacement fixture")
