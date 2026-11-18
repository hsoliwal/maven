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
 * Chooses the binding of {@link IndexStore}. Current M3jdk21 owns {@code jdk.m3.db.M3DB} in
 * {@code java.base}; older images may provide {@code jdk.m3.indexdb.M3IndexDB}. Either can
 * be exported to this plugin or provided as a named module. A stock JDK uses the library form.
 */
public final class IndexStores {
    static final String MODULE = "jdk.m3.db";
    static final String TYPE = MODULE + ".M3DB";
    static final String LEGACY_MODULE = "jdk.m3.indexdb";
    static final String LEGACY_TYPE = LEGACY_MODULE + ".M3IndexDB";

    /** One bound M3DB engine; every store it opens has the same binding. */
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
     * The runtime's own engine, never the library copy: current M3DB, legacy M3IndexDB, or an MIndexDB
     * donor runtime installed on the plugin (context realm first). Absence is a reflective failure.
     */
    static Engine runtimeEngine(ModuleLayer layer) throws ReflectiveOperationException {
        Optional<Class<?>> platform = platformType(layer);
        return platform.isPresent() ? PlatformIndexStore.engine(platform.get()) : DonorIndexStore.engine();
    }

    /** Opens a store with the binding the layer selects; {@link IndexStore#open} uses the boot layer. */
    public static IndexStore open(Path root, ModuleLayer layer) throws IOException {
        return engine(layer).open(root);
    }

    /** "platform" when an exported M3JDK database is available, "library" otherwise. */
    public static String binding(ModuleLayer layer) {
        return engine(layer).binding();
    }

    /** Stable identity for cache keys: exact platform owner and runtime version, or the legacy library. */
    public static String engineIdentity(ModuleLayer layer) {
        return platformType(layer)
                .map(type -> "platform:" + type.getModule().getDescriptor().toNameAndVersion() + ":" + type.getName() + ":"
                        + System.getProperty("java.runtime.version", ""))
                .orElse("library:" + LEGACY_MODULE);
    }

    /** Classes whose code bytes identify the engine: the caller, plus the library form when it is bound. */
    static List<Class<?>> engineClasses(Class<?> caller, ModuleLayer layer) {
        return platformType(layer).isPresent() ? List.of(caller) : List.of(caller, LibraryIndexStore.engineType());
    }

    static Optional<Class<?>> platformType(ModuleLayer layer) {
        Optional<Class<?>> current = platformType(layer, MODULE, TYPE);
        return current.isPresent() ? current : platformType(layer, LEGACY_MODULE, LEGACY_TYPE);
    }

    private static Optional<Class<?>> platformType(ModuleLayer layer, String packageName, String typeName) {
        return platformModule(layer, packageName).flatMap(module -> Optional.ofNullable(Class.forName(module, typeName)));
    }

    private static Optional<Module> platformModule(ModuleLayer layer, String packageName) {
        Optional<Module> named = layer.findModule(packageName);
        if (named.isPresent()) return named;
        return layer.findModule("java.base").filter(base -> base.getPackages().contains(packageName)
                && base.isExported(packageName, IndexStores.class.getModule()));
    }
}
