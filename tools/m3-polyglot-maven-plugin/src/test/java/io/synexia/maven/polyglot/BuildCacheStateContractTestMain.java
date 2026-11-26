/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Real Java process contracts joining portable output restoration to verified module-state generations. */
public final class BuildCacheStateContractTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static int checks;

    private BuildCacheStateContractTestMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0 && arguments[0].equals("fixture")) {
            fixture(arguments);
            return;
        }
        if (arguments.length != 0 && arguments[0].equals("provider-invoke")) {
            Path root = Path.of(arguments[1]), cache = Path.of(arguments[2]);
            run(root, "build", true, cache).forEach(System.out::println);
            run(root, "build", true, cache).forEach(System.out::println);
            return;
        }
        Path temporary = Files.createTempDirectory("m3-build-cache-state-");
        try {
            fusedInputFingerprintParity(temporary.resolve("fused"));
            portableRestoreAndLocalReceipt(temporary.resolve("portable"));
            failedProducerRevokesConsumers(temporary.resolve("failed-producer"));
            changedGenerationWithSameBytes(temporary.resolve("generation"));
            partialRestoreCannotAuthorizeReuse(temporary.resolve("partial"));
            unqualifiedTasksStayLive(temporary.resolve("unqualified"));
            internalStateIsNotTaskInputOrOutput(temporary.resolve("internal-state"));
            analysisStoreIsNotTaskInputOrOutput(temporary.resolve("analysis-store"));
            cacheDirectoryBoundaries(temporary.resolve("boundaries"));
            providerTasksStayLive(temporary.resolve("provider"));
            System.out.println("PASS: " + checks + " build-cache/module-state integration checks");
        } finally { erase(temporary); }
    }

    /** Keys keep their original bytes, while paired mode reads the declared input tree once. */
    private static void fusedInputFingerprintParity(Path directory) throws Exception {
        Path root = workspace(directory);
        manifest(root, task("build", "copy", "input.txt", "result.txt", List.of("input.txt")));
        TaskManifest.Task task = load(root).order("build").get(0);
        List<String> command = List.of(java(), "-version");
        Map<String, String> environment = Map.of("M3_TEST", "stable");
        Map<String, String> generations = Map.of();
        InputStamps.EngineTranscript engine = InputStamps.captureEngineTranscript();
        InputStamps.StampPair paired = InputStamps.pairedStamps(
                task, root, command, environment, generations, generations, engine);
        String local = InputStamps.stamp(task, root, command, environment, generations, engine);
        String portable = InputStamps.portableStamp(task, root, command, environment, generations, engine);
        check(paired.local().equals(local) && paired.portable().equals(portable),
                "fused observation preserves both existing cache keys");
        FileTime time = Files.getLastModifiedTime(root.resolve("input.txt"));
        Files.writeString(root.resolve("input.txt"), "two");
        Files.setLastModifiedTime(root.resolve("input.txt"), time);
        InputStamps.StampPair modified = InputStamps.pairedStamps(
                task, root, command, environment, generations, generations, engine);
        check(!modified.local().equals(local) && !modified.portable().equals(portable),
                "same-timestamp input mutation invalidates both fused keys");
        Files.delete(root.resolve("input.txt"));
        expectIo(() -> InputStamps.pairedStamps(
                task, root, command, environment, generations, generations, engine),
                "a missing declared input refuses the fused key");
    }

    private static void portableRestoreAndLocalReceipt(Path directory) throws Exception {
        Path cache = directory.resolve("cache");
        Path first = workspace(directory.resolve("first"));
        String body = task("build", "copy", "input.txt", "result.txt", List.of("input.txt"));
        manifest(first, body);
        List<String> log = run(first, "build", true, cache);
        check(count(first, "build") == 1 && has(log, "CACHED build"), "original success supplies the output cache");
        Path second = workspace(directory.resolve("second"));
        manifest(second, body);
        log = run(second, "build", true, cache);
        check(count(second, "build") == 0 && has(log, "FROM-CACHE build"), "another checkout restores through a portable key");
        check(Files.readString(second.resolve("result.txt")).equals("one"), "restored bytes match the original output");
        check(Files.isRegularFile(second.resolve(ModuleStateDb.EMBEDDED_FILE)), "restoration establishes the default embedded receipt");
        check(receipt(second, "build") != null, "restoration records a verified local module generation");
        log = run(second, "build", true, cache);
        check(has(log, "UP-TO-DATE build") && count(second, "build") == 0, "local verified receipt precedes a second restore");
        Files.delete(second.resolve("result.txt"));
        log = run(second, "build", true, cache);
        check(has(log, "FROM-CACHE build") && count(second, "build") == 0, "deleted output is restored by the explicit cache");
        Path cacheOnly = workspace(directory.resolve("cache-only"));
        manifest(cacheOnly, body);
        log = run(cacheOnly, "build", false, cache);
        check(has(log, "FROM-CACHE build") && count(cacheOnly, "build") == 0, "output cache remains independently opt-in");
        check(!Files.exists(cacheOnly.resolve(ModuleStateDb.EMBEDDED_FILE)), "cache-only run does not publish incremental receipts");
        Path dry = workspace(directory.resolve("dry"));
        manifest(dry, body);
        Path dryCache = directory.resolve("dry-cache");
        log = new ArrayList<>();
        TaskRunner.run(load(dry), dry, "build", true, TIMEOUT, false, true, "embedded", dryCache, log::add);
        check(has(log, "PLAN build") && count(dry, "build") == 0, "cache-enabled dry run only plans");
        check(!Files.exists(dry.resolve(".m3")) && !Files.exists(dryCache), "dry run creates neither module state nor output cache");
    }

    private static void failedProducerRevokesConsumers(Path directory) throws Exception {
        Path root = workspace(directory.resolve("module")), cache = directory.resolve("cache");
        manifest(root, chain());
        run(root, "child", true, cache);
        check(receipt(root, "child") != null, "child begins with a committed receipt");
        Files.writeString(root.resolve("input.txt"), "fail");
        expectIo(() -> run(root, "parent", true, cache), "changed parent really executes and fails");
        check(count(root, "parent") == 2 && count(root, "child") == 1, "failed parent never starts its consumer");
        check(receipt(root, "parent") == null && receipt(root, "child") == null,
                "producer failure durably revokes even an unselected transitive consumer");
        Files.writeString(root.resolve("input.txt"), "one");
        Files.delete(root.resolve("parent.out"));
        List<String> log = run(root, "child", true, cache);
        check(has(log, "FROM-CACHE parent") && has(log, "FROM-CACHE child"), "explicit valid artifacts can recover after revocation");
        check(!has(log, "UP-TO-DATE child"), "failed-generation receipt is never silently revived");
        check(count(root, "parent") == 2 && count(root, "child") == 1, "recovery uses artifacts without repeating the original tools");
        check(receipt(root, "child") != null, "recovery publishes a newly verified child receipt");
    }

    private static void changedGenerationWithSameBytes(Path directory) throws Exception {
        Path root = workspace(directory.resolve("module")), cache = directory.resolve("cache");
        manifest(root, chain());
        run(root, "child", true, cache);
        byte[] output = Files.readAllBytes(root.resolve("parent.out"));
        Files.writeString(root.resolve("input.txt"), "two");
        List<String> log = run(root, "child", true, cache);
        check(java.util.Arrays.equals(output, Files.readAllBytes(root.resolve("parent.out"))), "fixture changes producer generation without changing its output bytes");
        check(count(root, "parent") == 2 && count(root, "child") == 2, "changed prerequisite evidence misses the old child artifact");
        check(!has(log, "FROM-CACHE child") && !has(log, "UP-TO-DATE child"), "both child reuse paths honor prerequisite generation");
        log = run(root, "child", true, cache);
        check(has(log, "UP-TO-DATE parent") && has(log, "UP-TO-DATE child"), "new generation settles into ordinary verified reuse");
    }

    private static void partialRestoreCannotAuthorizeReuse(Path directory) throws Exception {
        Path root = workspace(directory.resolve("module")), cache = directory.resolve("cache");
        manifest(root, task("build", "copy", "input.txt", "result.txt", List.of("input.txt")));
        run(root, "build", true, cache);
        Path entry;
        try (var files = Files.walk(cache.resolve("entries"))) {
            entry = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        List<String> rows = Files.readAllLines(entry);
        String file = rows.stream().filter(line -> line.startsWith("file\tresult.txt\t")).findFirst().orElseThrow();
        // Both blob checks succeed; the second row fails only after the first
        // restored file prevents creation of the requested child directory.
        Files.writeString(entry, String.join("\n", rows) + "\n"
                + file.replace("file\tresult.txt\t", "file\tresult.txt/nested\t") + "\n");
        Files.delete(root.resolve("result.txt"));
        List<String> log = new ArrayList<>();
        expectIo(() -> TaskRunner.run(load(root), root, "build", false, TIMEOUT, false, true,
                "embedded", cache, log::add), "partial output restoration remains an explicit failure");
        check(!has(log, "FROM-CACHE build") && count(root, "build") == 1, "failed restore neither reports success nor launches the tool");
        check(receipt(root, "build") == null, "partial restore cannot retain the earlier module receipt");
        run(root, "build", true, null);
        check(count(root, "build") == 2, "following ordinary run executes even though partial output bytes exist");
    }

    private static void unqualifiedTasksStayLive(Path directory) throws Exception {
        Path root = workspace(directory.resolve("dependency")), cache = directory.resolve("dependency-cache");
        manifest(root, task("parent", "copy", "input.txt", "parent.out", List.of())
                + task("child", "copy", "child.txt", "child.out", List.of("child.txt")) + "dep\tchild\tparent\n");
        run(root, "child", true, cache);
        List<String> log = run(root, "child", true, cache);
        check(count(root, "parent") == 2 && count(root, "child") == 2, "unverified prerequisite prevents both child reuse paths");
        check(!has(log, "CACHED child") && !Files.exists(cache), "unqualified dependency does not produce a portable artifact");
        Path inplace = workspace(directory.resolve("inplace")), inplaceCache = directory.resolve("inplace-cache");
        manifest(inplace, task("build", "copy", "input.txt", "input.txt", List.of("input.txt")));
        run(inplace, "build", true, inplaceCache);
        run(inplace, "build", true, inplaceCache);
        check(count(inplace, "build") == 2 && !Files.exists(inplaceCache), "in-place tasks never enter either cache");
        Path overlap = workspace(directory.resolve("overlap")), overlapCache = directory.resolve("overlap-cache");
        manifest(overlap, task("parent", "tree", "input.txt", "out", List.of("input.txt"))
                + task("child", "copy", "child.txt", "out/nested.txt", List.of("child.txt")) + "dep\tchild\tparent\n");
        run(overlap, "child", true, overlapCache);
        run(overlap, "child", true, overlapCache);
        check(count(overlap, "parent") == 2 && count(overlap, "child") == 2 && !Files.exists(overlapCache),
                "overlapping output ownership cannot supply cache generations");
    }

    /** Internal M3DB and M3File ownership excludes cache admission and output publication. */
    private static void internalStateIsNotTaskInputOrOutput(Path directory) throws Exception {
        for (String value : List.of(".m3", ".m3/module.m3db", ".m3-watch",
                ".m3-watch/state.bin", "child/.m3", "child/.m3/module.m3db",
                "child/.m3-watch/state.bin", ".m3/polyglot-stamps/result")) {
            check(InputStamps.statePath(value), "internal state component is reserved: " + value);
        }
        for (String variant : List.of(".M3/MODULE.M3DB", "child/.M3/MODULE.M3DB",
                "child/.M3-WATCH/STATE.BIN", ".M3/MODULE-STATE.RUN.LOCK")) {
            check(InputStamps.statePath(variant),
                    "case-insensitive filesystem aliases of original state owners are reserved: " + variant);
        }
        check(!InputStamps.statePath("child/.m3-ordinary/input.txt"),
                "similarly named user paths remain ordinary files");
        check(!InputStamps.statePath(".M3/USER-CONFIG.JSON"),
                "case-varied ordinary .m3 user files are not falsely classified as DB state");
        check(!InputStamps.statePath(".m3/user-config.json"),
                "original module-state compatibility permits ordinary .m3 user configuration");
        check(!InputStamps.statePath("child/.m3/receipt"),
                "a nested user file is not a native database-owned artifact");
        check(!InputStamps.statePath("src/.m3-watchable.java"),
                "reserved names must be whole path components");
        Path watch = workspace(directory.resolve("watched-input"));
        Files.createDirectories(watch.resolve(".m3-watch"));
        Files.writeString(watch.resolve(".m3-watch/state.bin"), "watch");
        manifest(watch, task("build", "copy", ".m3-watch/state.bin", "result.txt",
                List.of(".m3-watch/state.bin")));
        run(watch, "build", true, null);
        run(watch, "build", true, null);
        check(count(watch, "build") == 2 && receipt(watch, "build") == null,
                "watcher checkpoints cannot authorize a persistent task receipt");
        Path nested = workspace(directory.resolve("nested-module"));
        Files.createDirectories(nested.resolve("child/.m3"));
        Files.writeString(nested.resolve("child/.m3/receipt"), "state");
        manifest(nested, task("build", "copy", "child/.m3/receipt", "result.txt", List.of("child/.m3")));
        run(nested, "build", true, null);
        run(nested, "build", true, null);
        check(count(nested, "build") == 2 && receipt(nested, "build") == null,
                "declared nested M3DB directory stays uncached across runs");
        Path broad = workspace(directory.resolve("broad-parent"));
        Files.createDirectories(broad.resolve("child/.m3"));
        Files.writeString(broad.resolve("child/.m3/module.m3db"), "old receipt");
        Files.writeString(broad.resolve("child/data.txt"), "source");
        manifest(broad, task("build", "copy", "child/data.txt", "result.txt", List.of("child")));
        run(broad, "build", true, null);
        run(broad, "build", true, null);
        check(count(broad, "build") == 2 && receipt(broad, "build") == null,
                "a declared parent tree containing a nested module DB can never authorize a hit");
        Path provider = workspace(directory.resolve("provider-nested"));
        Files.createDirectories(provider.resolve("toolchain/child/.m3"));
        Files.writeString(provider.resolve("toolchain/child/.m3/module.m3db"), "other module DB");
        TaskManifest.Task providerTask = loadTask(provider);
        M3ToolReuseInputs providerInputs = new M3ToolReuseInputs("m3.nested-state.v1",
                List.of(provider.resolve("toolchain")), List.of(), false);
        expectIo(() -> TaskRunner.validateProviderInputs(providerTask, provider, null, providerInputs),
                "provider additional input trees containing another module's DB cannot authorize reuse");
        Files.delete(provider.resolve("toolchain/child/.m3/module.m3db"));
        Files.delete(provider.resolve("toolchain/child/.m3"));
        TaskRunner.validateProviderInputs(providerTask, provider, null, providerInputs);
        check(true, "ordinary provider additional input directories remain admissible");

        Path external = Files.createDirectories(directory.resolve("external-provider-toolchain"));
        Path externalState = Files.createDirectories(external.resolve("child/.m3"));
        Path externalDb = Files.writeString(externalState.resolve("module.m3db"), "external owner receipt");
        M3ToolReuseInputs externalReuse = new M3ToolReuseInputs("m3.external-state.v1",
                List.of(external), List.of(), false);
        check(InputStamps.containsReservedState(provider, external),
                "external provider toolchain trees are inspected for nested database state");
        expectIo(() -> TaskRunner.validateProviderInputs(providerTask, provider, null, externalReuse),
                "external absolute toolchain containing M3DB receipts cannot authorize reuse");
        M3ToolReuseInputs directDb = new M3ToolReuseInputs("m3.external-direct-db.v1",
                List.of(externalDb), List.of(), false);
        check(InputStamps.containsReservedState(provider, externalDb),
                "direct external M3DB receipt is independently identified as reserved");
        expectIo(() -> TaskRunner.validateProviderInputs(providerTask, provider, null, directDb),
                "direct external database receipt cannot be admitted as a provider input");
        Files.delete(externalDb);
        Path externalUser = Files.writeString(externalState.resolve("user-config.json"), "ordinary");
        check(InputStamps.containsReservedState(provider, external),
                "a broad external .m3 directory stays conservatively excluded");
        M3ToolReuseInputs directUser = new M3ToolReuseInputs("m3.external-user-file.v1",
                List.of(externalUser), List.of(), false);
        check(!InputStamps.containsReservedState(provider, externalUser),
                "direct external .m3 user configuration remains eligible");
        TaskRunner.validateProviderInputs(providerTask, provider, null, directUser);
        Path cleanExternal = Files.createDirectories(directory.resolve("clean-provider-toolchain"));
        Files.writeString(cleanExternal.resolve("compiler.txt"), "fixed toolchain bytes");
        M3ToolReuseInputs cleanReuse = new M3ToolReuseInputs("m3.external-clean.v1",
                List.of(cleanExternal), List.of(), false);
        check(!InputStamps.containsReservedState(provider, cleanExternal),
                "ordinary external toolchain tree is permitted");
        TaskRunner.validateProviderInputs(providerTask, provider, null, cleanReuse);
        Path externalCompiler = cleanExternal.resolve("compiler.txt");
        String originalCleanIdentity = InputStamps.providerIdentity(cleanReuse, provider);
        FileTime compilerTime = Files.getLastModifiedTime(externalCompiler);
        Files.writeString(externalCompiler, "other toolchain bytes"); // identical length
        Files.setLastModifiedTime(externalCompiler, compilerTime);
        check(!originalCleanIdentity.equals(InputStamps.providerIdentity(cleanReuse, provider)),
                "external declared toolchain bytes invalidate reuse despite unchanged size and restored mtime");
        Files.writeString(externalCompiler, "fixed toolchain bytes");
        Files.setLastModifiedTime(externalCompiler, compilerTime);
        check(originalCleanIdentity.equals(InputStamps.providerIdentity(cleanReuse, provider)),
                "restored external toolchain bytes restore the exact original content fingerprint");
        Path newToolInput = Files.writeString(cleanExternal.resolve("new-tool.txt"), "added");
        check(!originalCleanIdentity.equals(InputStamps.providerIdentity(cleanReuse, provider)),
                "adding a new file to an external toolchain tree invalidates reuse");
        Files.delete(newToolInput);
        check(originalCleanIdentity.equals(InputStamps.providerIdentity(cleanReuse, provider)),
                "removing an added external file restores the original toolchain identity");
        String originalExternalUserIdentity = InputStamps.providerIdentity(directUser, provider);
        FileTime externalUserTime = Files.getLastModifiedTime(externalUser);
        Files.writeString(externalUser, "modified"); // same length as ordinary
        Files.setLastModifiedTime(externalUser, externalUserTime);
        check(!originalExternalUserIdentity.equals(InputStamps.providerIdentity(directUser, provider)),
                "direct external ordinary .m3 user file remains a fresh content-hashed provider input");
        Files.writeString(externalUser, "ordinary");
        Files.setLastModifiedTime(externalUser, externalUserTime);
        check(originalExternalUserIdentity.equals(InputStamps.providerIdentity(directUser, provider)),
                "direct external .m3 user file identity is fully content-defined");
        Path other = Files.createDirectories(directory.resolve("external-watcher-toolchain/child/.m3-watch"));
        Files.writeString(other.resolve("state.bin"), "watch checkpoint");
        M3ToolReuseInputs watchReuse = new M3ToolReuseInputs("m3.external-watch.v1",
                List.of(other.getParent().getParent()), List.of(), false);
        expectIo(() -> TaskRunner.validateProviderInputs(providerTask, provider, null, watchReuse),
                "external watcher checkpoints cannot authorize a provider receipt");
        Path watchFile = other.resolve("state.bin");
        M3ToolReuseInputs directWatch = new M3ToolReuseInputs("m3.external-direct-watch.v1",
                List.of(watchFile), List.of(), false);
        check(InputStamps.containsReservedState(provider, watchFile),
                "direct external watcher checkpoint is reserved independently of its directory");
        expectIo(() -> TaskRunner.validateProviderInputs(providerTask, provider, null, directWatch),
                "a direct external watcher checkpoint cannot enter a reusable provider receipt");

        Path plain = workspace(directory.resolve("ordinary-directory"));
        Files.createDirectories(plain.resolve("src"));
        Files.writeString(plain.resolve("src/input.txt"), "source");
        manifest(plain, task("build", "copy", "src/input.txt", "result.txt", List.of("src")));
        run(plain, "build", true, null);
        List<String> reused = run(plain, "build", true, null);
        check(count(plain, "build") == 1 && has(reused, "UP-TO-DATE build"),
                "ordinary directory trees remain eligible for verified incremental reuse");
        for (String output : List.of(".m3/module.m3db", ".m3-watch/custom.txt",
                "child/.m3/module.m3db", "child/.m3-watch/snapshot")) {
            Path module = workspace(directory.resolve("refuse-" + output.replace('/', '-')));
            manifest(module, task("build", "copy", "input.txt", output, List.of("input.txt")));
            expectIo(() -> run(module, "build", true, null),
                    "output inside internal state must be rejected before executing: " + output);
            check(count(module, "build") == 0, "internal-state output may not be written: " + output);
        }
        Path ordinary = workspace(directory.resolve("ordinary-m3-user-output"));
        manifest(ordinary, task("build", "copy", "input.txt", ".m3/user-config.json",
                List.of("input.txt")));
        run(ordinary, "build", true, null);
        run(ordinary, "build", true, null);
        check(count(ordinary, "build") == 1 && Files.readString(ordinary.resolve(".m3/user-config.json")).equals("one"),
                "ordinary user configuration in .m3 remains writable and cacheable");
    }

    /**
     * The m3:machine-fingerprints MIndexDB analysis store (.m3/maven/mindexdb)
     * and its sibling writer lock are M3-owned state at any reactor depth.
     * Ordinary user files beside them remain inputs and outputs.
     */
    private static void analysisStoreIsNotTaskInputOrOutput(Path directory) throws Exception {
        for (String value : List.of(".m3/maven", ".m3/maven/mindexdb",
                ".m3/maven/mindexdb/maven-syntax-fingerprints-v1.miart", ".m3/maven/mindexdb.lock",
                "child/.m3/maven/mindexdb/root.miart", "child/.m3/maven/mindexdb.lock",
                "child\\.m3\\maven\\mindexdb\\root.miart", ".M3/MAVEN/MINDEXDB/ROOT.MIART",
                "child/.M3/Maven/MIndexDB.lock")) {
            check(InputStamps.statePath(value), "MIndexDB analysis store is reserved M3 state: " + value);
        }
        for (String value : List.of(".m3/maven/notes.txt", ".m3/maven/mindexdb-notes.txt",
                ".m3/maven/mindexdb.lock.bak", "child/.m3/maven/user/config.json",
                "src/maven/mindexdb/root.miart", "maven/mindexdb.lock")) {
            check(!InputStamps.statePath(value), "ordinary paths near the analysis store stay ordinary: " + value);
        }
        check(!ModuleStateDb.statePath(".m3/maven/mindexdb/root.miart")
                        && !ModuleStateDb.statePath(".m3/maven/mindexdb.lock"),
                "the narrower legacy ModuleStateDb.statePath contract is unchanged");
        for (String value : List.of(".m3", "child/.M3", ".m3/maven", "child/.m3/Maven", "child\\.m3\\maven")) {
            check(ModuleStateDb.reservedStateParent(value), "state parent directory marker: " + value);
        }
        for (String value : List.of(".m3/maven/mindexdb", ".m3/user", "src/maven", "maven", ".m3-watch")) {
            check(!ModuleStateDb.reservedStateParent(value), "not a state parent directory marker: " + value);
        }
        Path declared = workspace(directory.resolve("declared-store"));
        Files.createDirectories(declared.resolve(".m3/maven/mindexdb"));
        Files.writeString(declared.resolve(".m3/maven/mindexdb/root.miart"), "analysis");
        manifest(declared, task("build", "copy", ".m3/maven/mindexdb/root.miart", "result.txt",
                List.of(".m3/maven/mindexdb")));
        run(declared, "build", true, null);
        run(declared, "build", true, null);
        check(count(declared, "build") == 2 && receipt(declared, "build") == null,
                "a declared MIndexDB analysis store stays uncached across runs");
        for (String output : List.of(".m3/maven/mindexdb/root.miart", ".m3/maven/mindexdb.lock",
                "child/.m3/maven/mindexdb/root.miart")) {
            Path module = workspace(directory.resolve("refuse-" + output.replace('/', '-')));
            manifest(module, task("build", "copy", "input.txt", output, List.of("input.txt")));
            expectIo(() -> run(module, "build", true, null),
                    "output inside the analysis store must be rejected before executing: " + output);
            check(count(module, "build") == 0, "analysis-store output may not be written: " + output);
        }
        Path ordinary = workspace(directory.resolve("ordinary-m3-maven-output"));
        manifest(ordinary, task("build", "copy", "input.txt", ".m3/maven/notes.txt", List.of("input.txt")));
        run(ordinary, "build", true, null);
        run(ordinary, "build", true, null);
        check(count(ordinary, "build") == 1 && Files.readString(ordinary.resolve(".m3/maven/notes.txt")).equals("one"),
                "an ordinary user file beside the analysis store remains writable and cacheable");
    }

    private static TaskManifest.Task loadTask(Path root) throws IOException {
        manifest(root, task("build", "copy", "input.txt", "result.txt", List.of("input.txt")));
        return load(root).order("build").get(0);
    }

    private static void cacheDirectoryBoundaries(Path directory) throws Exception {
        Path root = workspace(directory.resolve("output"));
        manifest(root, task("build", "tree", "input.txt", "out", List.of("input.txt")));
        expectIo(() -> run(root, "build", true, root.resolve("out/cache")), "cache inside a declared output is refused before execution");
        check(count(root, "build") == 0 && !Files.exists(root.resolve("out")), "overlap refusal does not execute or materialize outputs");
        Path input = workspace(directory.resolve("input"));
        Path cache = Files.createDirectories(input.resolve("cache-input"));
        Files.writeString(cache.resolve("value.txt"), "input");
        manifest(input, task("build", "copy", "cache-input/value.txt", "result.txt", List.of("cache-input")));
        run(input, "build", true, cache);
        run(input, "build", true, cache);
        check(count(input, "build") == 2 && !Files.exists(cache.resolve("entries")), "cache storage used as an input disables reuse");
    }

    private static void providerTasksStayLive(Path directory) throws Exception {
        Path root = workspace(directory.resolve("module")), cache = directory.resolve("cache");
        String body = task("build", "copy", "input.txt", "result.txt", List.of("input.txt"));
        manifest(root, body.replace("task\tbuild\t" + java(), "task\tbuild\t@m3:cachefixture:interpreter:java"));
        Path service = directory.resolve("service/META-INF/services/" + M3ToolProvider.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, FixtureProvider.class.getName() + "\n");
        Path log = directory.resolve("provider.log");
        Process process = new ProcessBuilder(java(), "-cp", directory.resolve("service") + File.pathSeparator + classpath(),
                BuildCacheStateContractTestMain.class.getName(), "provider-invoke", root.toString(), cache.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("provider qualification process timed out");
        }
        check(process.exitValue() == 0, "actual ServiceLoader provider execution succeeds: " + Files.readString(log));
        check(count(root, "build") == 2 && !Files.exists(cache), "provider-owned state never enters the portable output cache");
        check(!Files.readString(log).contains("FROM-CACHE"), "provider execution is never replaced by restoration");
    }

    /** Actual test-local implementation of Maven's public tool SPI; no external engine is impersonated. */
    public static final class FixtureProvider implements M3ToolProvider {
        @Override public String id() { return "cachefixture"; }
        @Override public Set<M3ToolCapability> capabilities() { return Set.of(M3ToolCapability.INTERPRETER); }
        @Override public Set<String> languages() { return Set.of("java"); }
        @Override public List<String> command(M3ToolCapability capability, String language,
                TaskManifest.Task task, Path workspace) {
            List<String> result = new ArrayList<>();
            result.add(java());
            result.addAll(task.arguments());
            return result;
        }
    }

    private static void fixture(String[] arguments) throws IOException {
        String mode = arguments[1], value = Files.readString(Path.of(arguments[2]));
        Path output = Path.of(arguments[3]);
        if (mode.equals("tree")) output = output.resolve("root.txt");
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, mode.equals("constant") ? "same" : value);
        Files.writeString(Path.of(arguments[4]), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (mode.equals("constant") && value.equals("fail")) System.exit(23);
    }

    private static String chain() throws IOException {
        return task("parent", "constant", "input.txt", "parent.out", List.of("input.txt"))
                + task("child", "copy", "child.txt", "child.out", List.of("child.txt")) + "dep\tchild\tparent\n";
    }

    private static String task(String name, String mode, String input, String output, List<String> inputs) throws IOException {
        StringBuilder body = new StringBuilder("task\t").append(name).append('\t').append(java()).append("\t.\n");
        for (String argument : List.of("-cp", classpath(), BuildCacheStateContractTestMain.class.getName(),
                "fixture", mode, input, output, name + ".runs")) {
            body.append("arg\t").append(name).append('\t').append(argument).append('\n');
        }
        for (String path : inputs) body.append("input\t").append(name).append('\t').append(path).append('\n');
        return body.append("output\t").append(name).append('\t').append(output).append('\n').toString();
    }

    private static String classpath() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try {
            for (Class<?> type : List.of(BuildCacheStateContractTestMain.class, TaskRunner.class)) {
                paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            }
        } catch (URISyntaxException invalid) { throw new IOException("test code source is not a filesystem path", invalid); }
        return String.join(File.pathSeparator, paths);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static Path workspace(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("input.txt"), "one");
        Files.writeString(root.resolve("child.txt"), "child");
        return root;
    }

    private static void manifest(Path root, String body) throws IOException {
        Files.writeString(root.resolve("m3-build.tsv"), TaskManifest.MAGIC + "\n" + body);
    }

    private static TaskManifest load(Path root) throws IOException { return TaskManifest.load(root.resolve("m3-build.tsv")); }

    private static List<String> run(Path root, String target, boolean incremental, Path cache) throws Exception {
        List<String> log = new ArrayList<>();
        TaskRunner.run(load(root), root, target, false, TIMEOUT, false, incremental, "embedded", cache, log::add);
        return log;
    }

    private static String[] receipt(Path root, String task) throws IOException {
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            return state.lookup("task-" + InputStamps.combine(List.of(task)));
        }
    }

    private static long count(Path root, String task) throws IOException {
        Path file = root.resolve(task + ".runs");
        return Files.exists(file) ? Files.readAllLines(file).size() : 0;
    }

    private static boolean has(List<String> log, String prefix) { return log.stream().anyMatch(line -> line.startsWith(prefix)); }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void expectIo(Checked callback, String message) throws Exception {
        try { callback.run(); }
        catch (IOException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }

    private static void erase(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
