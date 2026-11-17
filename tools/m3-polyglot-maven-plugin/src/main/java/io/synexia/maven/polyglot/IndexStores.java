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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the binding of {@link IndexStore}. M3jdk21 carries {@code jdk.m3.indexdb} either as a module of that
 * name or as a package of {@code java.base} exported to this code ({@code --add-exports}); either is the platform
 * binding. A stock JDK 17/21 gets the library form of the same M3IndexDB source.
 */
public final class IndexStores {
    static final String MODULE = "jdk.m3.indexdb";
    static final String TYPE = MODULE + ".M3IndexDB";

    /** One bound M3IndexDB engine; every store it opens has the same binding. */
    public interface Engine {
        String binding();

        IndexStore open(Path root) throws IOException;
    }

    private IndexStores() {}

    /** The platform engine when the layer has one, otherwise the library form carried by this plugin. */
    public static Engine engine(ModuleLayer layer) {
        Optional<Class<?>> platform = platformType(layer);
        return platform.isPresent() ? PlatformIndexStore.engine(platform.get()) : LibraryIndexStore.ENGINE;
    }

    /**
     * The runtime's own engine, never the library copy: the platform form, else an MIndexDB donor runtime
     * installed on the plugin (context realm first). Absence is a reflective failure for the caller to report.
     */
    static Engine runtimeEngine(ModuleLayer layer) throws ReflectiveOperationException {
        Optional<Class<?>> platform = platformType(layer);
        return platform.isPresent() ? PlatformIndexStore.engine(platform.get()) : DonorIndexStore.engine();
    }

    /** Opens a store with the binding the layer selects; {@link IndexStore#open} uses the boot layer. */
    public static IndexStore open(Path root, ModuleLayer layer) throws IOException {
        return engine(layer).open(root);
    }

    /** "platform" when the layer holds {@code jdk.m3.indexdb}, "library" otherwise. */
    public static String binding(ModuleLayer layer) {
        return engine(layer).binding();
    }

    /** Stable identity of the storage engine for cache keys: platform module and runtime version, or library. */
    public static String engineIdentity(ModuleLayer layer) {
        return platformModule(layer)
                .map(module -> "platform:" + module.getDescriptor().toNameAndVersion() + ":" + MODULE + ":"
                        + System.getProperty("java.runtime.version", ""))
                .orElse("library:" + MODULE);
    }

    /** Classes whose code bytes identify the engine: the caller, plus the library form when it is bound. */
    static List<Class<?>> engineClasses(Class<?> caller, ModuleLayer layer) {
        return platformType(layer).isPresent() ? List.of(caller) : List.of(caller, LibraryIndexStore.engineType());
    }

    static Optional<Class<?>> platformType(ModuleLayer layer) {
        return platformModule(layer).map(module -> Class.forName(module, TYPE));
    }

    private static Optional<Module> platformModule(ModuleLayer layer) {
        Optional<Module> named = layer.findModule(MODULE);
        if (named.isPresent()) return named;
        return layer.findModule("java.base").filter(base -> base.getPackages().contains(MODULE)
                && base.isExported(MODULE, IndexStores.class.getModule()));
    }
}
