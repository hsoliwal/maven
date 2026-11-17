/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The one storage interface: the library binding on a stock JDK, and the platform binding when a layer holds
 * a {@code jdk.m3.indexdb} module (as M3jdk21's boot layer does), proven here with a real module layer.
 */
public final class IndexStoreContractTestMain {
    private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);
    private static int checks;

    private IndexStoreContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temporary = Files.createTempDirectory("index-store-contract-");
        try {
            libraryBindingOnStockJdk(temporary.resolve("library"));
            platformBindingFromModuleLayer(temporary);
            System.out.println("PASS: " + checks + " IndexStore binding contract checks");
        } finally {
            erase(temporary);
        }
    }

    private static void libraryBindingOnStockJdk(Path root) throws Exception {
        check(IndexStores.binding(ModuleLayer.boot()).equals("library"), "stock JDK boot layer binds the library");
        check(IndexStores.engineIdentity(ModuleLayer.boot()).startsWith("library:"), "library engine identity");
        try (IndexStore store = IndexStore.open(root)) {
            check(store.binding().equals("library") && !store.contains("a"), "empty library store");
            IndexStore.Artifact written = store.write("a", "kind", 1, PAYLOAD);
            check(store.contains("a") && Arrays.equals(store.read("a").payload(), PAYLOAD), "library round trip");
            check(written.sha256().matches("[0-9a-f]{64}") && store.read("a").version() == 1, "artifact metadata");
            expectIo(() -> store.read("absent"));
        }
    }

    private static void platformBindingFromModuleLayer(Path temporary) throws Exception {
        ModuleLayer layer = moduleLayer(Files.createDirectories(temporary.resolve("modules")));
        check(IndexStores.binding(layer).equals("platform"), "a layer with jdk.m3.indexdb binds the platform");
        check(IndexStores.engineIdentity(layer).startsWith("platform:jdk.m3.indexdb"), "platform engine identity");
        check(IndexStores.engineClasses(InputStamps.class, layer).equals(List.of(InputStamps.class)),
                "platform engine has no library code source");
        Class<?> platform = IndexStores.platformType(layer).orElseThrow();
        check(platform.getModule().getName().equals("jdk.m3.indexdb")
                && platform != LibraryIndexStore.engineType(), "platform type comes from the named module");
        oneFormat(layer, temporary.resolve("shared"));
    }

    /** What the platform binding writes, the library binding reads. */
    private static void oneFormat(ModuleLayer layer, Path root) throws Exception {
        try (IndexStore store = IndexStores.open(root, layer)) {
            check(store.binding().equals("platform"), "platform store");
            store.write("b", "kind", 2, PAYLOAD);
            expectIo(() -> store.read("absent"));
        }
        try (IndexStore store = IndexStore.open(root)) {
            check(Arrays.equals(store.read("b").payload(), PAYLOAD) && store.read("b").version() == 2,
                    "library reads what the platform wrote: one storage format");
        }
    }

    /** Packs the library classes as an automatic module named jdk.m3.indexdb and resolves it in a new layer. */
    private static ModuleLayer moduleLayer(Path directory) throws Exception {
        Path jar = directory.resolve("jdk.m3.indexdb.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "jdk.m3.indexdb");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : classNames()) copy(name, out);
        }
        ModuleLayer boot = ModuleLayer.boot();
        Configuration configuration = boot.configuration().resolve(ModuleFinder.of(jar), ModuleFinder.of(),
                Set.of("jdk.m3.indexdb"));
        return boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
    }

    private static List<String> classNames() throws IOException, URISyntaxException {
        Path source = Path.of(LibraryIndexStore.engineType().getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(source)) {
            try (Stream<Path> files = Files.list(source.resolve("jdk/m3/indexdb"))) {
                return files.map(file -> "jdk/m3/indexdb/" + file.getFileName()).sorted().toList();
            }
        }
        try (JarFile jar = new JarFile(source.toFile())) {
            return jar.stream().map(JarEntry::getName).filter(name -> name.startsWith("jdk/m3/indexdb/")
                    && name.endsWith(".class")).sorted().toList();
        }
    }

    private static void copy(String name, JarOutputStream jar) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        try (InputStream in = IndexStoreContractTestMain.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing library class " + name);
            in.transferTo(jar);
        }
        jar.closeEntry();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("expected an IOException");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }

    private static void erase(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) delete(path);
        }
    }

    /** Windows keeps the module layer's JAR open until the JVM exits; that one file is removed then. */
    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException held) {
            path.toFile().deleteOnExit();
        }
    }
}
