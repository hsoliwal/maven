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
package org.apache.maven.plugins.clustering;

import java.util.Set;

/**
 * Native methods that HotSpot implements itself, so that no library source holds their functions: when such a method
 * has no native code in the scan (for example because {@code src/hotspot} is not scanned), it is VM_BOUND instead of
 * MISSING_NATIVE. The list: every native method of {@code java.lang.Object}, {@code jdk.internal.misc.Unsafe},
 * {@code java.lang.invoke.MethodHandleNatives}, {@code jdk.internal.vm.Continuation}, {@code jdk.internal.perf.Perf},
 * the JFR class {@code jdk.jfr.internal.JVM} and JVMCI's {@code jdk.vm.ci.hotspot.CompilerToVM}; of the classes whose
 * {@code registerNatives} HotSpot binds in its own table ({@code lookup_special_native_methods} in
 * {@code nativeLookup.cpp}): {@code jdk.internal.foreign.abi.UpcallStubs}, {@code UpcallLinker},
 * {@code NativeEntryPoint}, {@code jdk.internal.vm.vector.VectorSupport}, {@code jdk.internal.misc.ScopedMemoryAccess},
 * {@code jdk.vm.ci.runtime.JVMCI}, {@code jdk.vm.ci.services.Services} and the WhiteBox test classes; and the
 * signature-polymorphic methods of {@code java.lang.invoke.MethodHandle} and {@code VarHandle} (native, with a single
 * variable arity parameter of type {@code Object[]}, JLS 15.12.3); a plain {@code Object[]} parameter does not count.
 */
final class JniVmBound {
    private static final Set<String> OWNERS = Set.of("java/lang/Object", "jdk/internal/misc/Unsafe",
            "java/lang/invoke/MethodHandleNatives", "jdk/internal/vm/Continuation", "jdk/internal/perf/Perf",
            "jdk/jfr/internal/JVM", "jdk/vm/ci/hotspot/CompilerToVM", "jdk/internal/foreign/abi/UpcallStubs",
            "jdk/internal/foreign/abi/UpcallLinker", "jdk/internal/foreign/abi/NativeEntryPoint",
            "jdk/internal/vm/vector/VectorSupport", "jdk/internal/misc/ScopedMemoryAccess", "jdk/vm/ci/runtime/JVMCI",
            "jdk/vm/ci/services/Services", "sun/hotspot/WhiteBox", "jdk/test/whitebox/WhiteBox");
    private static final Set<String> POLYMORPHIC = Set.of("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle");
    private static final String OBJECT_VARARGS = "[Ljava/lang/Object;";

    private JniVmBound() { }

    static boolean bound(JniMethod m) {
        return OWNERS.contains(m.className())
                || POLYMORPHIC.contains(m.className()) && m.varargs() && m.arguments().equals(OBJECT_VARARGS);
    }
}
