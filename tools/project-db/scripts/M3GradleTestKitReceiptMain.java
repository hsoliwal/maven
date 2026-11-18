/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Dependency-free JDK17 admission gate for original source-pinned Gradle TestKit reports. */
public final class M3GradleTestKitReceiptMain {
    private static final String PACKAGE = "io.synexia.gradle.quality.";
    private static final String FILE_PREFIX = "TEST-" + PACKAGE;
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "M3ProjectDbRealEngineTest", List.of("ingestsActualBuildLogAndRetrievesOriginalProvenance()"),
            "M3ProjectDbGradleNativeRealEngineTest",
                    List.of("nativeGradleTestFailureIsIndexedWithSourceProvenanceWithoutPom()"),
            "M3ProjectDbTaskTest", List.of("refusesTamperedJarBeforeStartingExternalEngine()",
                    "ingestsAndQueriesOnlyWhenExplicitlyExecuted()",
                    "queryRequiresPriorIngestWithoutCreatingAnEmptyDatabase()",
                    "refusesOutOfProjectStoreAndEmptyQuery()"));

    private static final Map<String, List<String>> PRUNED_SYMLINK = Map.of(
            "M3ProjectDbPrunedSymlinkRealEngineTest",
            List.of("sourcePinnedMavenRecognizesLinkedGradleModuleAndIgnoresVendorOutputs()"));

    /** Latest reviewed Gradle bridge: the six historical cases plus two config-cache
     *  ABI/replay cases and one independently source-owned symlink/pruning case. */
    private static final Map<String, List<String>> CURRENT_NINE = Map.of(
            "M3ProjectDbRealEngineTest", REQUIRED.get("M3ProjectDbRealEngineTest"),
            "M3ProjectDbGradleNativeRealEngineTest", REQUIRED.get("M3ProjectDbGradleNativeRealEngineTest"),
            "M3ProjectDbTaskTest", List.of("refusesTamperedJarBeforeStartingExternalEngine()",
                    "ingestsAndQueriesOnlyWhenExplicitlyExecuted()",
                    "queryRequiresPriorIngestWithoutCreatingAnEmptyDatabase()",
                    "refusesOutOfProjectStoreAndEmptyQuery()",
                    "retainsOriginalBinaryTaskSurfaceWithOnlyConcreteLayoutInjection()",
                    "directTaskRetainsCustomBuildDirectoryAndRechecksStorageEngineAfterReplay()"),
            "M3ProjectDbPrunedSymlinkRealEngineTest",
                    PRUNED_SYMLINK.get("M3ProjectDbPrunedSymlinkRealEngineTest"));

    private M3GradleTestKitReceiptMain() {}

    public static void main(String[] arguments) throws Exception {
        boolean symlink = arguments.length == 2 && "pruned-symlink".equals(arguments[1]);
        boolean current = arguments.length == 2 && "current-nine".equals(arguments[1]);
        if (arguments.length != 1 && !symlink && !current) {
            throw new IllegalArgumentException("JUnit directory and optional approved mode required");
        }
        Map<String, List<String>> suites = current ? CURRENT_NINE : symlink ? PRUNED_SYMLINK : REQUIRED;
        Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing original Gradle TestKit results: " + directory);
        }
        for (Map.Entry<String, List<String>> suite : suites.entrySet()) {
            verify(directory.resolve(FILE_PREFIX + suite.getKey() + ".xml"), suite.getKey(), suite.getValue());
        }
        try (Stream<Path> files = Files.list(directory)) {
            long count = files.filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX + "M3ProjectDb"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml")).count();
            if (count != suites.size()) throw new IOException("unexpected Gradle project-DB suite count " + count);
        }
        System.out.println(current ? "PASS: nine current Gradle project-DB TestKit cases, zero skips/failures/errors"
                : symlink ? "PASS: source-pinned Gradle symlink/pruning TestKit case, no skips"
                : "PASS: six original Gradle project-DB TestKit cases, zero skips/failures/errors");
    }

    private static void verify(Path path, String name, List<String> expected) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024) {
            throw new IOException("missing, linked, or oversized Gradle TestKit XML " + name);
        }
        Document document = parse(path);
        Element suite = document.getDocumentElement();
        if (!"testsuite".equals(suite.getTagName()) || !suite.getAttribute("name").equals(PACKAGE + name)
                || number(suite, "tests") != expected.size() || number(suite, "skipped") != 0
                || number(suite, "failures") != 0 || number(suite, "errors") != 0) {
            throw new IOException("Gradle TestKit suite missing cases or reports unsuccessful tests: " + name);
        }
        NodeList cases = suite.getElementsByTagName("testcase");
        if (cases.getLength() != expected.size()) throw new IOException("Gradle testcase count mismatch: " + name);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            String caseName = test.getAttribute("name");
            if (test.getParentNode() != suite || !test.getAttribute("classname").equals(PACKAGE + name)
                    || !expected.contains(caseName) || !seen.add(caseName)) {
                throw new IOException("unknown, duplicated, or incorrectly scoped TestKit testcase: " + name);
            }
        }
        for (String failed : List.of("failure", "error", "skipped")) {
            if (suite.getElementsByTagName(failed).getLength() != 0) {
                throw new IOException("unsuccessful original Gradle testcase: " + name);
            }
        }
    }

    private static int number(Element element, String attribute) throws IOException {
        String value = element.getAttribute(attribute);
        if (!value.matches("[0-9]{1,4}")) throw new IOException("invalid Gradle testcase count: " + attribute);
        return Integer.parseInt(value);
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }
}
