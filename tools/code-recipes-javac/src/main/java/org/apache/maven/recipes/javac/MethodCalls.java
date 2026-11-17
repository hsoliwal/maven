/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.recipes.javac;

import java.util.concurrent.atomic.AtomicIntegerArray;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * How many calls of a tree carry a method type: method invocations, constructor calls and method references.
 *
 * @param attributed calls with a method type
 * @param unattributed calls without one
 */
public record MethodCalls(int attributed, int unattributed) {
    private static final int ATTRIBUTED = 0;
    private static final int UNATTRIBUTED = 1;

    /**
     * Counts the calls of one tree.
     *
     * @param lst the tree
     * @return the counts
     */
    public static MethodCalls count(J.CompilationUnit lst) {
        AtomicIntegerArray counts = new AtomicIntegerArray(2);
        new Counter().visit(lst, counts);
        return new MethodCalls(counts.get(ATTRIBUTED), counts.get(UNATTRIBUTED));
    }

    private static void add(JavaType.Method type, AtomicIntegerArray counts) {
        counts.incrementAndGet(type == null ? UNATTRIBUTED : ATTRIBUTED);
    }

    private static final class Counter extends JavaIsoVisitor<AtomicIntegerArray> {
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, AtomicIntegerArray counts) {
            add(call.getMethodType(), counts);
            return super.visitMethodInvocation(call, counts);
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass call, AtomicIntegerArray counts) {
            add(call.getConstructorType(), counts);
            return super.visitNewClass(call, counts);
        }

        @Override
        public J.MemberReference visitMemberReference(J.MemberReference reference, AtomicIntegerArray counts) {
            add(reference.getMethodType(), counts);
            return super.visitMemberReference(reference, counts);
        }
    }
}
