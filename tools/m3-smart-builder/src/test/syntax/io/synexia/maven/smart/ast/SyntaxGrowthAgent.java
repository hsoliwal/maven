// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Test-only agent instruments the real Builder.grow() ten Arrays.copyOf sites. */
public final class SyntaxGrowthAgent {
    public static final AtomicInteger SITES = new AtomicInteger();
    private SyntaxGrowthAgent() { }

    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> target,
                                    ProtectionDomain domain, byte[] original) {
                if (!"io/synexia/maven/smart/ast/SyntaxTree$Builder".equals(name)) return null;
                ClassReader reader = new ClassReader(original);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM8, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                      String signature, String[] exceptions) {
                        MethodVisitor base = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (!"grow".equals(method) || !"()V".equals(descriptor)) return base;
                        return new MethodVisitor(Opcodes.ASM8, base) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called,
                                                        String desc, boolean itf) {
                                if (opcode == Opcodes.INVOKESTATIC
                                        && owner.equals("java/util/Arrays")
                                        && called.equals("copyOf") && desc.equals("([II)[I")) {
                                    SITES.incrementAndGet();
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "io/synexia/maven/smart/ast/SyntaxGrowthFault", "beforeCopy", "()V", false);
                                }
                                super.visitMethodInsn(opcode, owner, called, desc, itf);
                            }
                        };
                    }
                };
                reader.accept(visitor, 0);
                return writer.toByteArray();
            }
        });
    }
}
