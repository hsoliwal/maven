/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Actual compiler/process tests for opt-in provider reuse through the existing runner and cache. */
public final class ProviderReuseExecutionTestMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static int checks;

    private ProviderReuseExecutionTestMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "fixture".equals(arguments[0])) {
            fixture(arguments);
            return;
        }
        if (arguments.length > 0 && "adversarial".equals(arguments[0])) {
            adversarialRun(Path.of(arguments[1]), arguments[2]);
            return;
        }
        if (arguments.length > 0 && "consume".equals(arguments[0])) {
            Path root = Path.of(arguments[1]);
            Files.copy(root.resolve("classes/Hello.class"), root.resolve("consumed.bin"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(root.resolve("consumer-runs.txt"), "run\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return;
        }
        Path temporary = Files.createTempDirectory("m3-provider-reuse-");
        try {
            rejectsInvalidCliOptions();
            additionalContentIdentities(temporary.resolve("identity"));
            changingProviderProofs(temporary.resolve("changing"));
            Path qualification = workspace(temporary.resolve("qualification"), "Hello", "one");
            if (M3ToolProviders.plan(load(qualification).order("compile").get(0), qualification, true)
                    .reuseInputs().isEmpty()) {
                System.out.println("NOT_RUN: real javac reuse needs an eligible JDK layout and uninjected environment");
            } else {
                defaultAndPlanningStayUnchanged(temporary.resolve("defaults"));
                contentAndOutputReuse(temporary.resolve("local"));
                dedicatedOutputCleanupAndFailure(temporary.resolve("failure"));
                cacheRestorationAndWorkspaceBoundary(temporary.resolve("cache-case"));
                classpathContentInvalidates(temporary.resolve("classpath"));
                sourcePreferenceIgnoresTimestampChanges(temporary.resolve("source-preference"));
                unavailableInputProofStillExecutes(temporary.resolve("unhashable-input"));
                damagedOutputTreeRebuilds(temporary.resolve("unhashable-output"));
                providerGenerationAndDependentCache(temporary.resolve("dependent"));
            }
            System.out.println("PASS: " + checks + " provider reuse execution checks");
        } finally { erase(temporary); }
    }

    private static void rejectsInvalidCliOptions() throws Exception {
        for (List<String> suffix : List.of(List.of("--provider-reuse"),
                List.of("--provider-reuse", "--incremental", "--provider-reuse"))) {
            List<String> args = new ArrayList<>(List.of("run", ".", "absent.tsv", "compile"));
            args.addAll(suffix);
            expectArgument(() -> PolyglotCli.execute(args.toArray(String[]::new), ignored -> {}));
        }
        expectArgument(() -> PolyglotCli.execute(new String[] {
                "plan", ".", "absent.tsv", "compile", "--provider-reuse"}, ignored -> {}));
    }

    private static void additionalContentIdentities(Path root) throws Exception {
        Path tree = Files.createDirectories(root.resolve("toolchain"));
        Path input = Files.writeString(tree.resolve("compiler.bin"), "one");
        M3ToolReuseInputs proof = new M3ToolReuseInputs("test.content.v1", List.of(tree), List.of("classes"), false);
        String original = InputStamps.providerIdentity(proof, root);
        FileTime time = Files.getLastModifiedTime(input);
        Files.writeString(input, "two");
        Files.setLastModifiedTime(input, time);
        check(!original.equals(InputStamps.providerIdentity(proof, root)),
                "same-size same-mtime additional input edits change the proof");
        Files.writeString(input, "one");
        check(original.equals(InputStamps.providerIdentity(proof, root)), "restored content restores identity");
        Files.createDirectory(tree.resolve("added"));
        check(!original.equals(InputStamps.providerIdentity(proof, root)), "added empty directories change the proof");
        Files.delete(tree.resolve("added"));
        check(!original.equals(InputStamps.providerIdentity(new M3ToolReuseInputs(
                "test.content.v2", List.of(tree), List.of("classes"), false), root)), "contract revisions invalidate");
        check(!original.equals(InputStamps.providerIdentity(new M3ToolReuseInputs(
                "test.content.v1", List.of(tree), List.of(), false), root)), "cleanup policy changes invalidate");
        check(!original.equals(InputStamps.providerIdentity(new M3ToolReuseInputs(
                "test.content.v1", List.of(tree), List.of("classes"), true), root)), "relocation policy changes invalidate");
        try {
            Path alias = Files.createSymbolicLink(root.resolve("alias"), tree);
            expectIo(() -> InputStamps.providerIdentity(new M3ToolReuseInputs(
                    "test.content.v1", List.of(alias.resolve("compiler.bin")), List.of(), false), root));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("NOT_RUN: provider input symlink case unavailable on this filesystem");
        }
    }

    private static void defaultAndPlanningStayUnchanged(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        Path cache = root.resolveSibling("planning-cache");
        List<String> log = new ArrayList<>();
        TaskRunner.run(load(root), root, "compile", true, TIMEOUT, false, true,
                "embedded", cache, true, log::add);
        check(has(log, "PLAN compile") && !Files.exists(root.resolve(".m3"))
                && !Files.exists(cache) && !Files.exists(root.resolve("classes")), "planning creates no state or products");
        log = run(root, true, null, false);
        check(!has(log, "UP-TO-DATE") && value(root, "Hello").equals("one"), "legacy provider execution compiles");
        Files.writeString(root.resolve("classes/preserved.txt"), "untouched");
        log = run(root, true, null, false);
        check(!has(log, "UP-TO-DATE") && Files.exists(root.resolve("classes/preserved.txt")),
                "legacy overload neither skips providers nor cleans their outputs");
        log = run(root, true, null, true);
        check(!Files.exists(root.resolve("classes/preserved.txt")) && !has(log, "UP-TO-DATE"),
                "explicit eligible opt-in cleans the owned output before recompiling");
    }

    private static void contentAndOutputReuse(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        check(!has(run(root, true, null, true), "UP-TO-DATE"), "first eligible compilation executes");
        Path product = root.resolve("classes/Hello.class");
        FileTime sentinel = FileTime.fromMillis(123_456_000L);
        Files.setLastModifiedTime(product, sentinel);
        check(has(run(root, true, null, true), "UP-TO-DATE compile")
                && Files.getLastModifiedTime(product).equals(sentinel), "verified warm run avoids the compiler process");
        Path source = root.resolve("src/Hello.java");
        FileTime before = Files.getLastModifiedTime(source);
        Files.writeString(source, source("Hello", "two"));
        Files.setLastModifiedTime(source, before);
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Hello").equals("two"),
                "same-size same-mtime source changes recompile and change runtime behavior");
        Files.write(product, new byte[] {1, 2, 3});
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Hello").equals("two"),
                "corrupted output cannot authorize reuse");
        manifest(root, "Hello", "11", "empty-classpath");
        check(!has(run(root, true, null, true), "UP-TO-DATE"), "changed compiler arguments invalidate");
        byte[] bytes = Files.readAllBytes(product);
        check((bytes[6] & 255) * 256 + (bytes[7] & 255) == 55, "actual compiler honors the changed release target");
    }

    private static void dedicatedOutputCleanupAndFailure(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        run(root, true, null, true);
        Files.delete(root.resolve("src/Hello.java"));
        Files.writeString(root.resolve("src/Renamed.java"), source("Renamed", "two"));
        manifest(root, "Renamed", "17", "empty-classpath");
        check(!has(run(root, true, null, true), "UP-TO-DATE") && !Files.exists(root.resolve("classes/Hello.class"))
                && value(root, "Renamed").equals("two"), "renamed source removes stale class products");
        Files.writeString(root.resolve("src/Renamed.java"), "this is not Java\n");
        expectIo(() -> run(root, true, null, true));
        check(!Files.exists(root.resolve("classes/Renamed.class")), "failed compile leaves no previous successful product");
        check(receipt(root) == null, "failed compile durably revokes its success receipt");
        Files.writeString(root.resolve("src/Renamed.java"), source("Renamed", "two"));
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Renamed").equals("two"),
                "repairing input to its original bytes recompiles after failure");
    }

    private static void cacheRestorationAndWorkspaceBoundary(Path directory) throws Exception {
        Path root = workspace(directory.resolve("first"), "Hello", "one");
        Path cache = directory.resolve("cache");
        check(has(run(root, true, cache, true), "CACHED compile"), "qualified output enters the existing artifact cache");
        byte[] original = Files.readAllBytes(root.resolve("classes/Hello.class"));
        check(has(run(root, true, cache, true), "UP-TO-DATE compile"), "local receipt precedes artifact restoration");
        Files.delete(root.resolve("classes/Hello.class"));
        check(has(run(root, true, cache, true), "FROM-CACHE compile")
                && Arrays.equals(original, Files.readAllBytes(root.resolve("classes/Hello.class"))),
                "deleted provider output restores exact bytes in the same workspace");
        Files.writeString(root.resolve("classes/stale.txt"), "stale");
        check(has(run(root, true, cache, true), "FROM-CACHE compile") && !Files.exists(root.resolve("classes/stale.txt")),
                "restoration replaces the complete output tree");
        Path other = workspace(directory.resolve("second"), "Hello", "one");
        check(!has(run(other, true, cache, true), "FROM-CACHE") && value(other, "Hello").equals("one"),
                "nonrelocatable provider compiles in another workspace");
        Path cacheOnly = workspace(directory.resolve("cache-only"), "Hello", "one");
        check(has(run(cacheOnly, false, cache, true), "CACHED compile"), "provider cache does not require incremental state");
        check(has(run(cacheOnly, false, cache, true), "FROM-CACHE compile"), "cache-only provider reuse stays root-bound");
    }

    private static void classpathContentInvalidates(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        Files.createDirectories(root.resolve("dependency-source"));
        Files.createDirectories(root.resolve("dependencies"));
        compileDependency(root, "one");
        Files.writeString(root.resolve("src/Hello.java"),
                "public class Hello { public static void main(String[] a) { System.out.print(Dependency.VALUE); } }\n");
        manifest(root, "Hello", "17", "dependencies");
        run(root, true, null, true);
        check(value(root, "Hello").equals("one"), "declared classpath supplies actual inlined constant");
        Path product = root.resolve("dependencies/Dependency.class");
        FileTime time = Files.getLastModifiedTime(product);
        long size = Files.size(product);
        compileDependency(root, "two");
        Files.setLastModifiedTime(product, time);
        check(Files.size(product) == size, "dependency counterexample preserves size");
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Hello").equals("two"),
                "same-size same-mtime classpath changes invalidate javac reuse");
    }

    private static void compileDependency(Path root, String value) throws Exception {
        Files.writeString(root.resolve("dependency-source/Dependency.java"),
                "public class Dependency { public static final String VALUE = \"" + value + "\"; }\n");
        process(root, List.of(java(), "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none",
                "-encoding", "UTF-8", "--release", "17", "-d", "dependencies", "dependency-source/Dependency.java"));
    }

    private static void sourcePreferenceIgnoresTimestampChanges(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        Files.createDirectories(root.resolve("dependency-source"));
        Files.createDirectories(root.resolve("dependencies"));
        compileDependency(root, "bin");
        Path source = Files.writeString(root.resolve("src/Dependency.java"),
                "public class Dependency { public static final String VALUE = \"src\"; }\n");
        Files.writeString(root.resolve("src/Hello.java"),
                "public class Hello { public static void main(String[] a) { System.out.print(Dependency.VALUE); } }\n");
        Path binary = root.resolve("dependencies/Dependency.class");
        FileTime older = FileTime.fromMillis(1_000_000_000_000L);
        FileTime newer = FileTime.fromMillis(2_000_000_000_000L);
        Files.setLastModifiedTime(source, older);
        Files.setLastModifiedTime(binary, newer);
        manifest(root, "Hello", "17", "dependencies");
        run(root, true, null, true);
        byte[] original = Files.readAllBytes(root.resolve("classes/Hello.class"));
        check(value(root, "Hello").equals("src"), "explicit source preference overrides a newer conflicting class");
        Files.setLastModifiedTime(source, newer);
        Files.setLastModifiedTime(binary, older);
        check(has(run(root, true, null, true), "UP-TO-DATE compile"),
                "content-identical timestamp changes are reusable under explicit source preference");
        Files.delete(root.resolve("classes/Hello.class"));
        run(root, true, null, true);
        check(Arrays.equals(original, Files.readAllBytes(root.resolve("classes/Hello.class")))
                && value(root, "Hello").equals("src"), "real recompilation after mtime reversal preserves exact class bytes");
        Path manifest = root.resolve("m3-build.tsv");
        Files.writeString(manifest, Files.readString(manifest).replace("arg\tcompile\t-Xprefer:source\n", ""));
        check(M3ToolProviders.plan(load(root).order("compile").get(0), root, true).reuseInputs().isEmpty(),
                "timestamp-sensitive default source preference has no reuse contract");
        Files.setLastModifiedTime(source, older);
        Files.setLastModifiedTime(binary, newer);
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Hello").equals("bin"),
                "unqualified original javac command actually prefers its newer binary");
        Files.setLastModifiedTime(source, newer);
        Files.setLastModifiedTime(binary, older);
        check(!has(run(root, true, null, true), "UP-TO-DATE") && value(root, "Hello").equals("src"),
                "unqualified command reexecutes when only timestamp-selected behavior changes");
    }

    private static void unavailableInputProofStillExecutes(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        Path extra = Files.createDirectories(root.resolve("aux"));
        if (!link(extra.resolve("unused-link"), root.resolve("src/Hello.java"))) return;
        Files.writeString(root.resolve("m3-build.tsv"), "input\tcompile\taux\n", StandardOpenOption.APPEND);
        Files.createDirectories(root.resolve("classes"));
        Files.writeString(root.resolve("classes/preserved.txt"), "unqualified cleanup must not occur");
        List<String> log = run(root, true, null, true);
        check(has(log, "ALWAYS-RUN compile") && !has(log, "UP-TO-DATE") && value(root, "Hello").equals("one"),
                "unhashable optional declared input proof preserves actual compiler execution");
        check(Files.exists(root.resolve("classes/preserved.txt")) && receipt(root) == null,
                "incomplete proof grants neither cleanup nor reusable state");
    }

    private static void damagedOutputTreeRebuilds(Path directory) throws Exception {
        Path root = workspace(directory, "Hello", "one");
        run(root, true, null, true);
        Path outside = Files.writeString(root.resolveSibling("preserve-outside.txt"), "external");
        if (!link(root.resolve("classes/unexpected-link"), outside)) return;
        List<String> log = run(root, true, null, true);
        check(!has(log, "UP-TO-DATE") && value(root, "Hello").equals("one"),
                "unverifiable owned output tree causes recompilation");
        check(!Files.exists(root.resolve("classes/unexpected-link")) && Files.readString(outside).equals("external"),
                "owned output cleanup deletes the link without following it");
    }

    private static boolean link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("NOT_RUN: nested symlink fixture unavailable on this filesystem");
            return false;
        }
    }

    private static void providerGenerationAndDependentCache(Path directory) throws Exception {
        Path root = workspace(directory.resolve("module"), "Hello", "one");
        Path cache = directory.resolve("cache");
        StringBuilder body = new StringBuilder("task\tconsume\t").append(java()).append("\t.\n");
        for (String argument : List.of("-cp", classpath(), ProviderReuseExecutionTestMain.class.getName(),
                "consume", root.toString())) {
            body.append("arg\tconsume\t").append(argument).append('\n');
        }
        body.append("dep\tconsume\tcompile\ninput\tconsume\tclasses\noutput\tconsume\tconsumed.bin\n");
        Files.writeString(root.resolve("m3-build.tsv"), body, StandardOpenOption.APPEND);
        List<String> log = runTarget(root, "consume", true, cache, true);
        check(has(log, "CACHED compile") && !has(log, "CACHED consume")
                && Files.readAllLines(root.resolve("consumer-runs.txt")).size() == 1,
                "local-only provider generation never authorizes a portable dependent artifact");
        log = runTarget(root, "consume", true, cache, true);
        check(has(log, "UP-TO-DATE compile") && has(log, "UP-TO-DATE consume"),
                "verified provider generation authorizes local dependent reuse");
        Files.delete(root.resolve("consumed.bin"));
        log = runTarget(root, "consume", true, cache, true);
        check(!has(log, "FROM-CACHE consume") && Files.readAllLines(root.resolve("consumer-runs.txt")).size() == 2,
                "deleted dependent product executes without an unqualified portable cache hit");
        Files.writeString(root.resolve("src/Hello.java"), "this is not Java\n");
        expectIo(() -> run(root, true, cache, true));
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            check(state.lookup("task-" + InputStamps.combine(List.of("consume"))) == null,
                    "provider compile failure revokes an unselected dependent's earlier receipt");
        }
    }

    private static void changingProviderProofs(Path directory) throws Exception {
        Path services = Files.createDirectories(directory.resolve("service/META-INF/services"));
        Files.writeString(services.resolve(M3ToolProvider.class.getName()), FixtureProvider.class.getName() + "\n");
        for (String mode : List.of("mutate", "replan", "vanish")) {
            Path root = Files.createDirectories(directory.resolve(mode));
            String result = process(root, List.of(java(), "-cp", directory.resolve("service")
                    + File.pathSeparator + classpath(), ProviderReuseExecutionTestMain.class.getName(),
                    "adversarial", root.toString(), mode));
            check(result.contains("ADVERSARIAL-PASS " + mode), "real provider before/after proof refusal: " + mode);
        }
    }

    private static void adversarialRun(Path root, String mode) throws Exception {
        Files.writeString(root.resolve("input.txt"), "source");
        Files.writeString(root.resolve("additional.txt"), "tool");
        StringBuilder body = new StringBuilder(TaskManifest.MAGIC + "\ntask\tcompile\t@m3:reusefixture:interpreter:java\t.\n");
        body.append("arg\tcompile\t").append(mode).append('\n');
        body.append("input\tcompile\tinput.txt\noutput\tcompile\tresult\n");
        Files.writeString(root.resolve("m3-build.tsv"), body);
        List<String> first = run(root, true, root.resolveSibling(mode + "-cache"), true);
        check(has(first, "UNCACHED compile") && receipt(root) == null,
                "changed provider input/command/qualification cannot publish reusable state");
        check(Files.readString(root.resolve("result/value.txt")).equals("one"), "successful work remains successful");
        if (mode.equals("mutate")) {
            check(!has(run(root, true, null, true), "UP-TO-DATE"), "continually changing provider always runs");
            check(Files.readAllLines(root.resolve("runs.txt")).size() == 2, "both commands actually executed");
        }
        System.out.println("ADVERSARIAL-PASS " + mode);
    }

    /** Test provider: deliberately changes an assertion while a real child JVM executes. */
    public static final class FixtureProvider implements M3ToolProvider {
        @Override public String id() { return "reusefixture"; }
        @Override public Set<M3ToolCapability> capabilities() { return Set.of(M3ToolCapability.INTERPRETER); }
        @Override public Set<String> languages() { return Set.of("java"); }
        @Override public List<String> command(M3ToolCapability capability, String language,
                TaskManifest.Task task, Path root) throws IOException {
            String mode = task.arguments().get(0);
            String value = mode.equals("replan") && Files.exists(root.resolve("changed")) ? "two" : "one";
            return List.of(java(), "-cp", classpath(), ProviderReuseExecutionTestMain.class.getName(),
                    "fixture", root.toString(), mode, value);
        }
        @Override public Optional<M3ToolReuseInputs> reuseInputs(M3ToolCapability capability, String language,
                TaskManifest.Task task, Path root) {
            if (task.arguments().get(0).equals("vanish") && Files.exists(root.resolve("changed"))) {
                return Optional.empty();
            }
            return Optional.of(new M3ToolReuseInputs("test.changing-provider.v1",
                    List.of(root.resolve("additional.txt")), List.of("result"), false));
        }
    }

    private static void fixture(String[] args) throws IOException {
        Path root = Path.of(args[1]);
        Files.createDirectories(root.resolve("result"));
        Files.writeString(root.resolve("result/value.txt"), args[3]);
        Files.writeString(root.resolve("runs.txt"), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (args[2].equals("mutate")) {
            Files.writeString(root.resolve("additional.txt"), "*", StandardOpenOption.APPEND);
        } else {
            Files.writeString(root.resolve("changed"), "changed");
        }
    }

    private static Path workspace(Path root, String name, String value) throws IOException {
        root = root.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("empty-classpath"));
        Files.writeString(root.resolve("src/" + name + ".java"), source(name, value));
        manifest(root, name, "17", "empty-classpath");
        return root;
    }

    private static String source(String name, String value) {
        return "public class " + name + " { public static void main(String[] a) { System.out.print(\"" + value + "\"); } }\n";
    }

    private static void manifest(Path root, String name, String release, String classpath) throws IOException {
        StringBuilder body = new StringBuilder(TaskManifest.MAGIC + "\ntask\tcompile\t@m3:jdk:compiler:java\t.\n");
        for (String argument : List.of("-proc:none", "-implicit:none", "-Xprefer:source", "-encoding", "UTF-8", "--release", release,
                "-classpath", classpath, "-sourcepath", "src", "-d", "classes", "src/" + name + ".java")) {
            body.append("arg\tcompile\t").append(argument).append('\n');
        }
        body.append("input\tcompile\tsrc\ninput\tcompile\t").append(classpath).append('\n');
        Files.writeString(root.resolve("m3-build.tsv"), body.append("output\tcompile\tclasses\n"));
    }

    private static TaskManifest load(Path root) throws IOException { return TaskManifest.load(root.resolve("m3-build.tsv")); }

    private static List<String> run(Path root, boolean incremental, Path cache, boolean providers) throws Exception {
        return runTarget(root, "compile", incremental, cache, providers);
    }

    private static List<String> runTarget(Path root, String target, boolean incremental, Path cache,
            boolean providers) throws Exception {
        List<String> log = new ArrayList<>();
        if (!providers) {
            TaskRunner.run(load(root), root, target, false, TIMEOUT, false, incremental, "embedded", cache, log::add);
        } else {
            List<String> args = new ArrayList<>(List.of("run", root.toString(), root.resolve("m3-build.tsv").toString(),
                    target, "--provider-reuse"));
            if (incremental) args.add("--incremental");
            if (cache != null) args.addAll(List.of("--build-cache", cache.toString()));
            PolyglotCli.execute(args.toArray(String[]::new), log::add);
        }
        return log;
    }

    private static String[] receipt(Path root) throws IOException {
        try (ModuleStateDb state = ModuleStateDb.open(root, "embedded")) {
            return state.lookup("task-" + InputStamps.combine(List.of("compile")));
        }
    }

    private static String value(Path root, String name) throws Exception {
        return process(root, List.of(java(), "-cp", "classes", name));
    }

    private static String process(Path root, List<String> command) throws Exception {
        Path log = Files.createTempFile("m3-provider-process-", ".log");
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("provider test process timed out");
            }
            String output = Files.readString(log);
            if (process.exitValue() != 0) throw new AssertionError("provider test process failed: " + output);
            return output;
        } finally { Files.deleteIfExists(log); }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String classpath() throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try {
            for (Class<?> type : List.of(ProviderReuseExecutionTestMain.class, TaskRunner.class)) {
                paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            }
        } catch (java.net.URISyntaxException invalid) { throw new IOException(invalid); }
        return String.join(File.pathSeparator, paths);
    }

    private static boolean has(List<String> log, String prefix) { return log.stream().anyMatch(line -> line.startsWith(prefix)); }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void expectIo(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("expected IOException");
    }

    private static void expectArgument(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("expected invalid option refusal before input loading");
    }

    private static void erase(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
