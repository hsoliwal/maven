// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Optional JDK-only compiler attribution, never effect equivalence or dynamic-dispatch proof. */
final class CompilerAttribution {
    private static final int MAX_FILES = 256;
    private static final int MAX_BYTES = 8_388_608;
    private static final int MAX_CALLS_PER_ATOM = 4096;

    private CompilerAttribution() { }

    static List<String> augment(Path project, Path sourceRoot, SourceInventory.Selected inventory,
                                List<String> syntaxRows) throws IOException {
        if (inventory.files().size() > MAX_FILES || inventory.summary().bytes() > MAX_BYTES)
            throw new IOException("compiler attribution source budget exceeded");
        if (Runtime.version().feature() < 21) throw new IOException("JDK 21 compiler required");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("JDK 21 compiler attribution unavailable");
        Path emptyClassPath = project.resolve("target/m3-smart/__m3_no_external_classpath__");
        if (Files.exists(emptyClassPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("reserved compiler classpath must be absent");
        List<Path> paths = inventory.files().stream().map(f -> sourceRoot.resolve(f.path())).toList();
        Map<String, String> original = new HashMap<>();
        for (String row : syntaxRows) {
            String[] fields = row.split("\t", -1);
            if (fields.length != 9) throw new IOException("compiler attribution requires extended syntax evidence");
            String key = fields[0] + "\t" + fields[2];
            if (original.putIfAbsent(key, fields[6]) != null) throw new IOException("duplicated syntax atom");
        }
        Map<String, String> attributed = new TreeMap<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            // This partial JDK 21 has the current platform classes but not a complete ct.sym.
            // On newer JDKs --release 21 is required to avoid attributing against newer APIs.
            List<String> language = Runtime.version().feature() == 21
                    ? List.of("-source", "21") : List.of("--release", "21");
            List<String> compilerOptions = new ArrayList<>(List.of("-proc:none", "-implicit:none",
                    "-classpath", emptyClassPath.toString()));
            compilerOptions.addAll(language);
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    compilerOptions,
                    null, files.getJavaFileObjectsFromPaths(paths));
            List<CompilationUnitTree> units = new ArrayList<>();
            Map<CompilationUnitTree, List<MachineAtomInventory.Atom>> parsedAtoms = new IdentityHashMap<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
                Path path = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                if (!path.startsWith(sourceRoot)) throw new IOException("compiler visited an out-of-scope source");
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                // javac may inject synthetic constructors during analyze(); freeze atom IDs first.
                parsedAtoms.put(unit, MachineAtomInventory.collect(unit, relative));
            }
            task.analyze();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                throw new IOException("compiler attribution has unresolved Java errors");
            Trees trees = Trees.instance(task);
            Map<String, String> sourceHashes = new HashMap<>();
            for (SourceInventory.FileFact file : inventory.files())
                sourceHashes.put(file.path(), file.sha256());
            for (CompilationUnitTree unit : units) {
                Path real = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                if (!real.startsWith(sourceRoot)) throw new IOException("compiler visited an out-of-scope source");
                String relative = sourceRoot.relativize(real).toString().replace('\\', '/');
                if (!sourceHashes.containsKey(relative)) throw new IOException("compiler source not in inventory");
                List<MachineAtomInventory.Atom> atoms = parsedAtoms.get(unit);
                for (int i = 0; i < atoms.size(); i++) {
                    MachineAtomInventory.Atom atom = atoms.get(i);
                    String key = relative + "\t" + MachineAtomInventory.atomId(i);
                    if (!atom.contractSha256().equals(original.get(key)))
                        throw new IOException("syntax and compiler atom inventories diverged");
                    TreePath path = TreePath.getPath(unit, atom.tree());
                    if (path == null) throw new IOException("compiler atom path unavailable");
                    String type = typeDescriptor(trees, path);
                    CallFacts calls = calls(trees, path);
                    String row = calls.resolved() + "\t" + calls.unresolved()
                            + "\t" + Hashes.sha256(type.getBytes(StandardCharsets.UTF_8))
                            + "\t" + calls.sha256();
                    if (attributed.putIfAbsent(key, row) != null)
                        throw new IOException("duplicated compiler atom");
                }
            }
        }
        if (!attributed.keySet().equals(original.keySet()))
            throw new IOException("compiler attribution did not cover every syntax atom");
        List<String> result = new ArrayList<>(syntaxRows.size());
        for (String row : syntaxRows) {
            String[] fields = row.split("\t", -1);
            result.add(row + "\t" + attributed.get(fields[0] + "\t" + fields[2]));
        }
        return List.copyOf(result);
    }

    private static String typeDescriptor(Trees trees, TreePath path) {
        Element element;
        TypeMirror mirror;
        try {
            element = trees.getElement(path);
            mirror = trees.getTypeMirror(path);
        } catch (IllegalArgumentException unsupportedNode) {
            return "NO_DECLARED_TYPE:" + path.getLeaf().getKind();
        }
        String declared = element == null ? "NO_ELEMENT" : element.getKind() + ":" + element.asType();
        String inferred = mirror == null ? "NO_TYPE_MIRROR" : mirror.toString();
        return path.getLeaf().getKind() + ":" + declared + ":" + inferred;
    }

    private record CallFacts(int resolved, int unresolved, String sha256) { }

    private static CallFacts calls(Trees trees, TreePath path) throws IOException {
        List<String> targets = new ArrayList<>();
        int[] counts = new int[2];
        new TreePathScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                Element target;
                try { target = trees.getElement(new TreePath(getCurrentPath(), call.getMethodSelect())); }
                catch (IllegalArgumentException unavailable) { target = null; }
                if (target instanceof ExecutableElement method) {
                    counts[0]++;
                    StringBuilder descriptor = new StringBuilder(method.getEnclosingElement().toString())
                            .append('#').append(method.getSimpleName()).append('(');
                    for (var parameter : method.getParameters())
                        descriptor.append(parameter.asType()).append(';');
                    descriptor.append("): ").append(method.getReturnType());
                    targets.add(descriptor.toString());
                } else counts[1]++;
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(path, null);
        if (counts[0] > MAX_CALLS_PER_ATOM || counts[1] > MAX_CALLS_PER_ATOM)
            throw new IOException("compiler call count budget exceeded");
        targets.sort(String::compareTo);
        return new CallFacts(counts[0], counts[1],
                Hashes.sha256(String.join("\n", targets).getBytes(StandardCharsets.UTF_8)));
    }
}
