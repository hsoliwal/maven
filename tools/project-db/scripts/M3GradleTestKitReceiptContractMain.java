/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Dependency-free Java 17 contract for the secured Gradle JUnit receipt parser. */
public final class M3GradleTestKitReceiptContractMain {
    private static final String PREFIX = "io.synexia.gradle.quality.";
    private static final String TASK = "M3ProjectDbTaskTest";
    private static final String REAL = "M3ProjectDbRealEngineTest";
    private static final String NATIVE = "M3ProjectDbGradleNativeRealEngineTest";
    private static final String SYMLINK = "M3ProjectDbPrunedSymlinkRealEngineTest";

    private static final List<String> ORIGINAL_TASKS = List.of(
            "refusesTamperedJarBeforeStartingExternalEngine()",
            "ingestsAndQueriesOnlyWhenExplicitlyExecuted()",
            "queryRequiresPriorIngestWithoutCreatingAnEmptyDatabase()",
            "refusesOutOfProjectStoreAndEmptyQuery()");
    private static final List<String> NEW_TASKS = List.of(
            "retainsOriginalBinaryTaskSurfaceWithOnlyConcreteLayoutInjection()",
            "directTaskRetainsCustomBuildDirectoryAndRechecksStorageEngineAfterReplay()");
    private static final Map<String, List<String>> SIX = Map.of(
            REAL, List.of("ingestsActualBuildLogAndRetrievesOriginalProvenance()"),
            NATIVE, List.of("nativeGradleTestFailureIsIndexedWithSourceProvenanceWithoutPom()"),
            TASK, ORIGINAL_TASKS);
    private static final Map<String, List<String>> ONE = Map.of(
            SYMLINK, List.of("sourcePinnedMavenRecognizesLinkedGradleModuleAndIgnoresVendorOutputs()"));
    private static final Map<String, List<String>> NINE = Map.of(
            REAL, SIX.get(REAL),
            NATIVE, SIX.get(NATIVE),
            TASK, concat(ORIGINAL_TASKS, NEW_TASKS),
            SYMLINK, ONE.get(SYMLINK));
    private static int checks;

    private M3GradleTestKitReceiptContractMain() {}

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("m3-gradle-receipt-contract-");
        try {
            Path six = root.resolve("six");
            Path one = root.resolve("one");
            Path nine = root.resolve("nine");
            writeSuites(six, SIX);
            writeSuites(one, ONE);
            writeSuites(nine, NINE);
            accept(six);
            accept(one, "pruned-symlink");
            accept(nine, "current-nine");

            refuse(six, "current-nine");
            refuse(one, "current-nine");
            refuse(nine);
            refuse(nine, "unknown-mode");

            damaged(root, "missing-suite", folder -> Files.delete(report(folder, SYMLINK)));
            damaged(root, "extra-suite", folder ->
                    Files.writeString(report(folder, "M3ProjectDbUnexpectedTest"), "<testsuite/>"));
            damaged(root, "nonzero-skip", folder -> edit(report(folder, TASK), "skipped=\"0\"", "skipped=\"1\""));
            damaged(root, "nonzero-failure", folder ->
                    edit(report(folder, TASK), "failures=\"0\"", "failures=\"1\""));
            damaged(root, "wrong-class", folder ->
                    edit(report(folder, SYMLINK), "classname=\"" + PREFIX + SYMLINK + "\"",
                            "classname=\"untrusted.SymlinkTest\""));
            damaged(root, "wrong-method", folder ->
                    edit(report(folder, TASK), NEW_TASKS.get(0), "inventedMethod()"));
            damaged(root, "duplicated-method", folder ->
                    edit(report(folder, TASK), NEW_TASKS.get(0), NEW_TASKS.get(1)));
            damaged(root, "zero-count", folder -> edit(report(folder, TASK), "tests=\"6\"", "tests=\"0\""));
            damaged(root, "doctype", folder -> edit(report(folder, SYMLINK),
                    "<testsuite", "<!DOCTYPE testsuite [<!ENTITY external SYSTEM \"file:///etc/passwd\">]>\n<testsuite"));
            damaged(root, "missing-new-ABI-case", folder -> edit(report(folder, TASK),
                    caseXml(TASK, NEW_TASKS.get(0)), ""));
            System.out.println("PASS: " + checks + " original/current Gradle JUnit acceptance and refusal contracts");
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
            }
        }
    }

    private static List<String> concat(List<String> old, List<String> newer) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(old);
        result.addAll(newer);
        return List.copyOf(result);
    }

    private static void writeSuites(Path root, Map<String, List<String>> suites) throws IOException {
        Files.createDirectories(root);
        for (Map.Entry<String, List<String>> entry : suites.entrySet()) {
            String name = entry.getKey();
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    .append("<testsuite name=\"").append(PREFIX).append(name)
                    .append("\" tests=\"").append(entry.getValue().size())
                    .append("\" skipped=\"0\" failures=\"0\" errors=\"0\">\n");
            for (String method : entry.getValue()) xml.append(caseXml(name, method));
            xml.append("</testsuite>\n");
            Files.writeString(report(root, name), xml.toString());
        }
    }

    private static String caseXml(String suite, String method) {
        return "  <testcase name=\"" + method + "\" classname=\"" + PREFIX + suite + "\"/>\n";
    }

    private static Path report(Path folder, String suite) {
        return folder.resolve("TEST-" + PREFIX + suite + ".xml");
    }

    private static void edit(Path file, String oldText, String newText) throws IOException {
        String before = Files.readString(file);
        if (!before.contains(oldText)) throw new IOException("test fixture mutation anchor missing");
        Files.writeString(file, before.replace(oldText, newText));
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Path folder) throws Exception;
    }

    private static void damaged(Path root, String name, Mutation mutation) throws Exception {
        Path folder = root.resolve("bad-" + name);
        writeSuites(folder, NINE);
        mutation.apply(folder);
        refuse(folder, "current-nine");
    }

    private static void accept(Path folder, String... mode) throws Exception {
        String[] arguments = mode.length == 0 ? new String[] {folder.toString()}
                : new String[] {folder.toString(), mode[0]};
        M3GradleTestKitReceiptMain.main(arguments);
        checks++;
    }

    private static void refuse(Path folder, String... mode) throws Exception {
        String[] arguments = mode.length == 0 ? new String[] {folder.toString()}
                : new String[] {folder.toString(), mode[0]};
        try {
            M3GradleTestKitReceiptMain.main(arguments);
            throw new AssertionError("accepted a mismatched or forged original Gradle JUnit receipt");
        } catch (AssertionError unexpectedlyAccepted) {
            throw unexpectedlyAccepted;
        } catch (Exception correctlyRefused) {
            checks++;
        }
    }
}
