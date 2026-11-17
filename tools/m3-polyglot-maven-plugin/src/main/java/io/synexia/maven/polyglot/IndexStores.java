/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the binding of {@link IndexStore}: the {@code jdk.m3.indexdb} module of the given layer when it
 * exists (M3jdk21), otherwise the library form of the same M3IndexDB source on a stock JDK 17/21.
 */
public final class IndexStores {
    /** Module and type names of the platform form. */
    static final String MODULE = "jdk.m3.indexdb";
    static final String TYPE = "jdk.m3.indexdb.M3IndexDB";

    private IndexStores() {}

    /** Opens a store with the binding the layer selects; {@link IndexStore#open} uses the boot layer. */
    public static IndexStore open(Path root, ModuleLayer layer) throws IOException {
        Optional<Class<?>> platform = platformType(layer);
        return platform.isPresent() ? PlatformIndexStore.open(platform.get(), root) : LibraryIndexStore.open(root);
    }

    /** "platform" when the layer holds the {@code jdk.m3.indexdb} module, "library" otherwise. */
    public static String binding(ModuleLayer layer) {
        return platformType(layer).isPresent() ? "platform" : "library";
    }

    /** Stable identity of the storage engine for cache keys: module name and version, or "library". */
    public static String engineIdentity(ModuleLayer layer) {
        return layer.findModule(MODULE)
                .map(module -> "platform:" + module.getDescriptor().toNameAndVersion() + ":"
                        + System.getProperty("java.runtime.version", ""))
                .orElse("library:" + MODULE);
    }

    /** Classes whose code bytes identify the engine: the caller, plus the library form when it is bound. */
    static List<Class<?>> engineClasses(Class<?> caller, ModuleLayer layer) {
        return platformType(layer).isPresent() ? List.of(caller) : List.of(caller, LibraryIndexStore.engineType());
    }

    static Optional<Class<?>> platformType(ModuleLayer layer) {
        return layer.findModule(MODULE).map(module -> Class.forName(module, TYPE));
    }
}
