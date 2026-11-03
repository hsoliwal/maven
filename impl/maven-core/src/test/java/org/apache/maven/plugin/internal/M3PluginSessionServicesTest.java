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
package org.apache.maven.plugin.internal;

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.maven.api.Service;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Priority;
import org.apache.maven.api.di.Provides;
import org.apache.maven.di.Key;
import org.apache.maven.di.impl.DIException;
import org.apache.maven.di.impl.InjectorImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frozen compatibility oracle for plugin providers and session service defaults. */
class M3PluginSessionServicesTest {
    private static final Echo CORE = new EchoValue("core");
    private static final Echo LEGACY = new EchoValue("legacy");
    private static final Echo CUSTOM = new EchoValue("custom");
    private static final AtomicInteger LEGACY_CALLS = new AtomicInteger();

    @Test
    void missingServiceIsSuppliedLazily() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        AtomicInteger calls = new AtomicInteger();
        admit(injector, () -> {
            calls.incrementAndGet();
            return CORE;
        });
        assertEquals(0, calls.get());
        assertSame(CORE, injector.getInstance(Echo.class));
        assertEquals(1, calls.get());
    }

    @Test
    void legacyPluginFactoryKeepsItsIdentityWithoutAnAmbiguousCoreDuplicate() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(LegacyProviders.class);
        LEGACY_CALLS.set(0);
        AtomicInteger calls = new AtomicInteger();
        admit(injector, () -> {
            calls.incrementAndGet();
            return CORE;
        });
        assertEquals(0, LEGACY_CALLS.get());
        assertEquals(0, calls.get());
        assertSame(LEGACY, injector.getInstance(Echo.class));
        assertEquals(1, LEGACY_CALLS.get());
        assertEquals(0, calls.get());
    }

    @Test
    void genuinelyAmbiguousPluginFactoriesStillFail() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(EqualProviders.class);
        admit(injector, () -> CORE);
        DIException error = assertThrows(DIException.class, () -> injector.getInstance(Echo.class));
        assertTrue(error.getMessage().contains("Ambiguous bindings"));
        assertTrue(error.getMessage().contains("first()"));
        assertTrue(error.getMessage().contains("second()"));
    }

    @Test
    void namedServiceDoesNotDisplaceTheCoreDefault() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(NamedProviders.class);
        admit(injector, () -> CORE);
        assertSame(CORE, injector.getInstance(Echo.class));
        assertSame(CUSTOM, injector.getInstance(Key.of(Echo.class, "custom")));
    }

    @Test
    void explicitlyRegisteredEmptyInterfaceStillGetsTheCoreService() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(Echo.class);
        admit(injector, () -> CORE);
        assertSame(CORE, injector.getInstance(Echo.class));
    }

    @Test
    void lowerPriorityPluginFactoryStillDefersToTheCore() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(LowerProviders.class);
        admit(injector, () -> CORE);
        assertSame(CORE, injector.getInstance(Echo.class));
        assertEquals(2, injector.getAllBindings(Echo.class).size());
    }

    @Test
    void higherPriorityPluginFactoryKeepsTheOriginalBindingSet() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(HigherProviders.class);
        admit(injector, () -> CORE);
        assertSame(CUSTOM, injector.getInstance(Echo.class));
        assertEquals(2, injector.getAllBindings(Echo.class).size());
    }

    @Test
    void aHigherPriorityNamedFactoryPreservesExistingDefaultBindings() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(LegacyProviders.class);
        injector.bindImplicit(HigherNamedProviders.class);
        admit(injector, () -> CORE);
        assertSame(CUSTOM, injector.getInstance(Echo.class));
        assertEquals(3, injector.getAllBindings(Echo.class).size());
    }

    @Test
    void emptyNamedFactoryHasDefaultSemantics() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        injector.bindImplicit(EmptyNamedProviders.class);
        admit(injector, () -> CORE);
        assertSame(LEGACY, injector.getInstance(Echo.class));
    }

    @Test
    void unrelatedServicesAndOriginalSupplierFailuresRemainIndependent() throws Exception {
        InjectorImpl injector = new InjectorImpl();
        RuntimeException expected = new RuntimeException("supplier failure");
        admit(injector, () -> {
            throw expected;
        });
        assertSame(expected, assertThrows(RuntimeException.class, () -> injector.getInstance(Echo.class)));
    }

    private static void admit(InjectorImpl injector, Supplier<? extends Service> supplier) throws Exception {
        Method method =
                DefaultMavenPluginManager.class.getDeclaredMethod("bindSessionServices", InjectorImpl.class, Map.class);
        method.setAccessible(true);
        method.invoke(null, injector, Map.of(Echo.class, supplier));
    }

    public interface Echo extends Service {
        String value();
    }

    public record EchoValue(String value) implements Echo {}

    public static class LegacyProviders {
        @Provides
        public static Echo echo() {
            LEGACY_CALLS.incrementAndGet();
            return LEGACY;
        }
    }

    public static class EqualProviders {
        @Provides
        public static Echo first() {
            return LEGACY;
        }

        @Provides
        public static Echo second() {
            return CUSTOM;
        }
    }

    public static class NamedProviders {
        @Provides
        @Named("custom")
        public static Echo echo() {
            return CUSTOM;
        }
    }

    public static class LowerProviders {
        @Provides
        @Priority(-1)
        public static Echo echo() {
            return CUSTOM;
        }
    }

    public static class HigherProviders {
        @Provides
        @Priority(1)
        public static Echo echo() {
            return CUSTOM;
        }
    }

    public static class HigherNamedProviders {
        @Provides
        @Named("custom")
        @Priority(1)
        public static Echo echo() {
            return CUSTOM;
        }
    }

    public static class EmptyNamedProviders {
        @Provides
        @Named
        public static Echo echo() {
            return LEGACY;
        }
    }
}
