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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The one storage layer. Exercises both the current {@code jdk.m3.db.M3DB} M3JDK owner and the older
 * {@code jdk.m3.indexdb.M3IndexDB} owner as named modules and as exported {@code java.base} packages.
 * The current owner sources are pinned test-only snapshots; semantic-index signatures have a test stub.
 * Child JVMs prove the real CLI and facade reuse, and cross-owner artifact format interoperability.
 */
public final class IndexStoreContractTestMain {
    private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);
    private static final String INPUT = "a".repeat(64);
    private static final String OUTPUT = "b".repeat(64);
    private static int checks;

    private IndexStoreContractTestMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && "tool".equals(arguments[0])) {
            tool();
        } else if (arguments.length == 3 && "child".equals(arguments[0])) {
            child(Path.of(arguments[1]), arguments[2]);
        } else {
            parent();
        }
    }

    private static void parent() throws Exception {
        Path temporary = Files.createTempDirectory("index-store-contract-");
        try {
            libraryBindingOnStockJdk(temporary.resolve("library"));
            platformBindingFromModuleLayer(temporary, "jdk.m3.indexdb", moduleForm(temporary.resolve("layer-legacy")));
            platformBindingFromModuleLayer(temporary, "jdk.m3.db", currentModuleForm(temporary.resolve("layer-current")));
            prefersCurrentOwner(temporary.resolve("layer-both"));
            noDonorRuntimeOnContractClasspath();
            runtimeBackendThroughPlatform(temporary.resolve("legacy-module"), moduleForm(temporary.resolve("modules")),
                    "jdk.m3.indexdb.M3IndexDB");
            runtimeBackendThroughPlatform(temporary.resolve("legacy-base"), javaBaseForm(temporary.resolve("patch")),
                    "jdk.m3.indexdb.M3IndexDB");
            runtimeBackendThroughPlatform(temporary.resolve("current-module"),
                    currentModuleForm(temporary.resolve("current-modules")), "jdk.m3.db.M3DB");
            runtimeBackendThroughPlatform(temporary.resolve("current-base"),
                    currentJavaBaseForm(temporary.resolve("current-patch")), "jdk.m3.db.M3DB");
            System.out.println("PASS: " + checks + " IndexStore binding and m3db backend contract checks");
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

    private static void platformBindingFromModuleLayer(Path temporary, String module, List<String> arguments)
            throws Exception {
        ModuleLayer layer = moduleLayer(arguments, Set.of(module));
        check(IndexStores.binding(layer).equals("platform"), "a layer with " + module + " binds the platform");
        check(IndexStores.engineIdentity(layer).startsWith("platform:" + module + ":" + module),
                "platform engine identity includes the owner type");
        check(IndexStores.engineClasses(InputStamps.class, layer).equals(List.of(InputStamps.class)),
                "platform engine has no library code source");
        Class<?> platform = IndexStores.platformType(layer).orElseThrow();
        check(platform.getModule().getName().equals(module)
                && platform != LibraryIndexStore.engineType(), "platform type comes from the named module");
        oneFormat(layer, temporary.resolve("shared-" + module));
    }

    private static void prefersCurrentOwner(Path temporary) throws Exception {
        List<String> old = moduleForm(temporary.resolve("old"));
        List<String> current = currentModuleForm(temporary.resolve("current"));
        ModuleLayer layer = moduleLayer(List.of("--module-path",
                old.get(1) + File.pathSeparator + current.get(1)), Set.of("jdk.m3.indexdb", "jdk.m3.db"));
        check(IndexStores.platformType(layer).orElseThrow().getName().equals("jdk.m3.db.M3DB"),
                "current M3DB takes precedence when both platform owners are present");
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

    private static void noDonorRuntimeOnContractClasspath() {
        try {
            IndexStores.runtimeEngine(ModuleLayer.boot());
            throw new AssertionError("the m3db engine fell back to the library copy");
        } catch (ReflectiveOperationException expected) {
            check(expected instanceof ClassNotFoundException, "no platform and no donor: the m3db engine is absent");
        }
    }

    /** Runs the m3db scenario in a JVM with the given platform form, then reads its directory with the library. */
    private static void runtimeBackendThroughPlatform(Path work, List<String> jvmOptions, String expectedType)
            throws Exception {
        Files.createDirectories(work);
        List<String> command = new ArrayList<>(List.of(java()));
        command.addAll(jvmOptions);
        command.addAll(List.of("-cp", classpath(), IndexStoreContractTestMain.class.getName(), "child",
                work.toString(), expectedType));
        String log = run(command, work.resolve("child.log"));
        check(log.contains("PASS: child"), "m3db scenario passes on the platform binding: " + log);
        try (IndexStore store = IndexStore.open(work.resolve("cli/" + M3DbModuleStore.NATIVE_DIRECTORY))) {
            IndexStore.Artifact artifact = store.read(DirectoryStateBackend.ARTIFACT);
            check(artifact.kind().equals(DirectoryStateBackend.KIND) && artifact.version() == M3DbModuleStore.VERSION,
                    "the library engine reads the platform-written m3db directory: one format");
        }
    }

    /** Both one-file embedded and native-directory M3DB backends: CLI twice, read-only view, facade. */
    private static void child(Path work, String expectedType) throws Exception {
        check(IndexStores.binding(ModuleLayer.boot()).equals("platform"), "child JVM binds the platform");
        check(IndexStores.platformType(ModuleLayer.boot()).orElseThrow().getName().equals(expectedType),
                "child JVM uses the requested M3JDK owner");
        Path embedded = project(work.resolve("embedded"));
        check(cli(embedded, "embedded").stream().noneMatch(line -> line.startsWith("UP-TO-DATE"))
                && runs(embedded) == 1, "cold one-file M3DB run executes the tool");
        check(cli(embedded, "embedded").stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile"))
                && runs(embedded) == 1 && Files.isRegularFile(embedded.resolve(M3DbModuleStore.FILE)),
                "warm one-file M3DB run reuses the runtime-owner receipt");
        Path module = project(work.resolve("cli"));
        check(cli(module, "m3db").stream().noneMatch(line -> line.startsWith("UP-TO-DATE")) && runs(module) == 1,
                "cold m3db run executes the tool");
        check(cli(module, "native").stream().anyMatch(line -> line.startsWith("UP-TO-DATE compile"))
                && runs(module) == 1, "warm m3db run reuses the platform IndexStore receipt");
        noFallback(module);
        List<ModuleStateView.TaskState> view = ModuleStateView.read(module);
        check(view.size() == 1 && view.get(0).backend().equals("m3db") && view.get(0).task().startsWith("task-")
                && view.get(0).source().equals(M3DbModuleStore.NATIVE_DIRECTORY), "read-only view of the m3db receipt");
        facade(Files.createDirectories(work.resolve("facade")));
        System.out.println("PASS: child " + checks);
    }

    private static void noFallback(Path module) throws IOException {
        check(Files.isDirectory(module.resolve(M3DbModuleStore.NATIVE_DIRECTORY + "/refs")), "m3db directory store");
        check(!Files.exists(module.resolve(M3DbModuleStore.FILE)) && !Files.exists(module.resolve(M3DbModuleStore.FILE_BACKEND)),
                "m3db never publishes an embedded or file image");
        check(Files.readString(module.resolve(StateFiles.IGNORE)).equals("*\n"), "the state directory ignores itself");
    }

    private static void facade(Path root) throws Exception {
        try (ModuleStateDb store = ModuleStateDb.open(root, "m3db")) {
            store.record("compile", INPUT, OUTPUT);
        }
        try (ModuleStateDb store = ModuleStateDb.open(root, "m3db")) {
            check(store.hit("compile", INPUT, OUTPUT), "facade receipt survives reopening on the m3db backend");
            store.invalidate("compile");
        }
        try (ModuleStateDb store = ModuleStateDb.open(root, "native")) {
            check(store.lookup("compile") == null, "m3db revocation is durable");
        }
    }

    private static Path project(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("source.txt"), "source");
        StringBuilder manifest = new StringBuilder(TaskManifest.MAGIC).append("\ntask\tcompile\t").append(java())
                .append("\t.\n");
        for (String argument : List.of("-cp", classpath(), IndexStoreContractTestMain.class.getName(), "tool", "copy")) {
            manifest.append("arg\tcompile\t").append(argument).append('\n');
        }
        manifest.append("input\tcompile\tsource.txt\noutput\tcompile\tresult.txt\n");
        Files.writeString(project.resolve("m3-build.tsv"), manifest);
        return project;
    }

    private static List<String> cli(Path project, String backend) throws Exception {
        List<String> output = new ArrayList<>();
        PolyglotCli.execute(new String[] {"run", project.toString(), project.resolve("m3-build.tsv").toString(),
                "compile", "--incremental", "--state-backend", backend}, output::add);
        return output;
    }

    private static void tool() throws IOException {
        Files.writeString(Path.of("runs.log"), "run\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
    }

    private static int runs(Path project) throws IOException {
        Path counter = project.resolve("runs.log");
        return Files.exists(counter) ? Files.readAllLines(counter).size() : 0;
    }

    /** Older M3JDK module form, retained as a compatibility gate. */
    private static List<String> moduleForm(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path jar = directory.resolve("jdk.m3.indexdb.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "jdk.m3.indexdb");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : classNames("jdk/m3/indexdb")) copy(name, out);
        }
        return List.of("--module-path", jar.toString(), "--add-modules", "jdk.m3.indexdb");
    }

    /** Current M3JDK owner form: public M3DB artifact API in a named module. */
    private static List<String> currentModuleForm(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path jar = directory.resolve("jdk.m3.db.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "jdk.m3.db");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : classNames("jdk/m3/db")) copy(name, out);
        }
        return List.of("--module-path", jar.toString(), "--add-modules", "jdk.m3.db");
    }

    /** M3jdk21 java.base form: the package patched into java.base and exported to the plugin's unnamed module. */
    private static List<String> javaBaseForm(Path directory) throws Exception {
        return javaBaseForm(directory, "jdk/m3/indexdb");
    }

    private static List<String> currentJavaBaseForm(Path directory) throws Exception {
        return javaBaseForm(directory, "jdk/m3/db");
    }

    private static List<String> javaBaseForm(Path directory, String packageName) throws Exception {
        for (String name : classNames(packageName)) {
            Path target = Files.createDirectories(directory.resolve(name).getParent()).resolve(Path.of(name).getFileName());
            try (InputStream in = resource(name)) {
                Files.copy(in, target);
            }
        }
        return List.of("--patch-module", "java.base=" + directory, "--add-exports",
                "java.base/" + packageName.replace('/', '.') + "=ALL-UNNAMED");
    }

    private static ModuleLayer moduleLayer(List<String> moduleForm, Set<String> roots) {
        ModuleLayer boot = ModuleLayer.boot();
        Path[] paths = Arrays.stream(moduleForm.get(1).split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(Path::of).toArray(Path[]::new);
        Configuration configuration = boot.configuration().resolve(ModuleFinder.of(paths),
                ModuleFinder.of(), roots);
        return boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
    }

    private static List<String> classNames(String packageName) throws IOException, URISyntaxException {
        Path source = Path.of(LibraryIndexStore.engineType().getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(source)) {
            try (Stream<Path> files = Files.list(source.resolve(packageName))) {
                return files.filter(file -> file.getFileName().toString().endsWith(".class"))
                        .map(file -> packageName + "/" + file.getFileName()).sorted().toList();
            }
        }
        try (JarFile jar = new JarFile(source.toFile())) {
            return jar.stream().map(JarEntry::getName)
                    .filter(name -> name.startsWith(packageName + "/") && name.endsWith(".class")).sorted().toList();
        }
    }

    private static void copy(String name, JarOutputStream jar) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        try (InputStream in = resource(name)) {
            in.transferTo(jar);
        }
        jar.closeEntry();
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = IndexStoreContractTestMain.class.getClassLoader().getResourceAsStream(name);
        if (in == null) throw new IOException("missing library class " + name);
        return in;
    }

    private static String run(List<String> command, Path log) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child JVM timed out");
        }
        String output = Files.readString(log);
        if (process.exitValue() != 0) throw new AssertionError("child JVM failed: " + output);
        return output;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String classpath() throws URISyntaxException {
        List<String> entries = new ArrayList<>();
        for (Class<?> owner : List.of(IndexStoreContractTestMain.class, TaskRunner.class)) {
            String entry = Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!entries.contains(entry)) entries.add(entry);
        }
        return String.join(File.pathSeparator, entries);
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
