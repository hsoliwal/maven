// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One canonical AST atom ordering shared by the syntax and attributed producer passes. */
final class MachineAtomInventory {
    record Atom(Tree tree, String signature) {
        String contractSha256() { return Hashes.sha256(signature.getBytes(StandardCharsets.UTF_8)); }
    }

    private MachineAtomInventory() { }

    static String atomId(int ordinal) { return String.format(Locale.ROOT, "a%06d", ordinal + 1); }

    static List<Atom> collect(CompilationUnitTree unit, String sourcePath) {
        List<Atom> atoms = new ArrayList<>();
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
        if (atoms.isEmpty()) atoms.add(new Atom(unit, "unit:" + sourcePath));
        return List.copyOf(atoms);
    }

    private static String signature(MethodTree method) {
        StringBuilder result = new StringBuilder("method:").append(method.getName())
                .append(':').append(method.getReturnType() == null ? "constructor" : method.getReturnType())
                .append(":modifiers:");
        method.getModifiers().getFlags().stream().map(Object::toString).sorted()
                .forEach(flag -> result.append(flag).append(','));
        for (var parameter : method.getParameters()) result.append(":parameter:").append(parameter.getType());
        for (var thrown : method.getThrows()) result.append(":throws:").append(thrown);
        return result.toString();
    }
}
