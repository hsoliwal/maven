/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Offline, no-Maven-runtime contracts for Eclipse Core/JDT/CDT/Rust/PyDev inspection. */
public final class EclipseProjectModelContractTestMain {
    private static int passed;

    private EclipseProjectModelContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("eclipse-maven-model-");
        try {
            mixedEclipseProjectPreservesOriginalOwners(temp.resolve("mixed"));
            allOptionalMetadataMayBeAbsent(temp.resolve("minimal"));
            invalidXmlIsNotTrusted(temp.resolve("invalid"));
            linkedMetadataIsNotFollowed(temp.resolve("linked"));
            hashesRepresentActualByteChanges(temp.resolve("hashes"));
            crossLanguageBuildDescriptorsRemainReadOnly(temp.resolve("polyglot"));
            refusesSymlinkedOrOversizedLanguageDocuments(temp.resolve("refusals"));
            System.out.println("PASS: " + passed + " Eclipse project model contract cases");
        } finally {
            try (Stream<Path> entries = Files.walk(temp)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void mixedEclipseProjectPreservesOriginalOwners(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", """
                <?xml version="1.0" encoding="UTF-8"?>
                <projectDescription><name>Mixed&amp;Project</name><comment></comment>
                <projects><project>OtherProject</project></projects>
                <buildSpec>
                  <buildCommand><name>org.eclipse.jdt.core.javabuilder</name><triggers>clean,full,incremental</triggers>
                    <arguments><dictionary><key>flag</key><value>keep-original</value></dictionary></arguments></buildCommand>
                  <buildCommand><name>org.eclipse.cdt.managedbuilder.core.genmakebuilder</name><arguments/></buildCommand>
                  <buildCommand><name>org.python.pydev.PyDevBuilder</name><arguments/></buildCommand>
                </buildSpec>
                <natures>
                  <nature>org.eclipse.jdt.core.javanature</nature>
                  <nature>org.eclipse.cdt.core.cnature</nature>
                  <nature>org.python.pydev.pythonNature</nature>
                  <nature>vendor.rustdt.nature</nature>
                  <nature>vendor.future.nature</nature>
                </natures><linkedResources><link><name>external</name><type>2</type>
                  <location>/this/location/is/not/accessed</location></link></linkedResources>
                </projectDescription>
                """);
        put(root, ".classpath", """
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                 <classpathentry kind="src" path="src/main/java" output="target/classes" including="**/*.java">
                   <attributes><attribute name="test" value="false"/></attributes>
                 </classpathentry>
                 <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER" exported="true">
                   <accessrules><accessrule kind="accessible" pattern="**/public/**"/></accessrules>
                 </classpathentry>
                </classpath>
                """);
        put(root, ".cproject", """
                <?xml version="1.0" encoding="UTF-8"?>
                <cproject><storageModule moduleId="org.eclipse.cdt.core.settings">
                 <cconfiguration id="c.1"><storageModule moduleId="org.eclipse.cdt.core.settings" name="Debug"/></cconfiguration>
                 <cconfiguration id="c.2"><storageModule moduleId="org.eclipse.cdt.core.settings" name="Release"/></cconfiguration>
                </storageModule></cproject>
                """);
        put(root, ".pydevproject", """
                <?xml version="1.0" encoding="UTF-8"?>
                <pydev_project>
                 <pydev_property name="org.python.pydev.PYTHON_PROJECT_INTERPRETER">Default</pydev_property>
                 <pydev_pathproperty name="org.python.pydev.PROJECT_SOURCE_PATH">
                  <path>/Mixed&amp;Project/src</path><path>/Mixed&amp;Project/tests</path>
                 </pydev_pathproperty>
                </pydev_project>
                """);
        put(root, "Cargo.toml", "[package]\nname = 'rust-native'\n");
        put(root, ".settings/org.eclipse.jdt.core.prefs", "org.eclipse.jdt.core.compiler.source=21\n");
        EclipseProjectModel.Snapshot snap = EclipseProjectModel.inspect(root);
        require(snap.name().equals("Mixed&Project"), "original project name");
        require(snap.natureIds().equals(List.of("org.eclipse.jdt.core.javanature", "org.eclipse.cdt.core.cnature",
                "org.python.pydev.pythonNature", "vendor.rustdt.nature", "vendor.future.nature")), "all natures in original order");
        require(snap.builders().equals(List.of("org.eclipse.jdt.core.javabuilder",
                "org.eclipse.cdt.managedbuilder.core.genmakebuilder", "org.python.pydev.PyDevBuilder")), "original builder order");
        require(snap.buildCommands().get(0).arguments().equals(Map.of("flag", "keep-original"))
                && snap.buildCommands().get(0).triggers().equals("clean,full,incremental"),
                "original builder arguments and triggers preserved without execution");
        require(snap.projectReferences().equals(List.of("OtherProject")), "project references");
        require(snap.linkedResources().get(0).location().equals("/this/location/is/not/accessed"), "link is a string only");
        require(snap.classpath().size() == 2, "two original JDT classpath entries");
        require(snap.classpath().get(0).attributes().get("output").equals("target/classes"), "per-entry output retained");
        require(snap.classpath().get(0).extraAttributes().equals(List.of(Map.of("name", "test", "value", "false"))),
                "JDT extra attribute retained");
        require(snap.classpath().get(1).accessRules().equals(List.of(Map.of("kind", "accessible", "pattern", "**/public/**"))),
                "JDT access rules retained");
        require(snap.cdtConfigurations().equals(List.of(new EclipseProjectModel.CdtConfiguration("c.1", "Debug"),
                new EclipseProjectModel.CdtConfiguration("c.2", "Release"))), "CDT config identity preserved");
        require(snap.pydevPaths().equals(List.of(
                new EclipseProjectModel.PydevPath("org.python.pydev.PROJECT_SOURCE_PATH", "/Mixed&Project/src"),
                new EclipseProjectModel.PydevPath("org.python.pydev.PROJECT_SOURCE_PATH", "/Mixed&Project/tests"))),
                "PyDev source folder order preserved");
        require(snap.pydevProperties().get("org.python.pydev.PYTHON_PROJECT_INTERPRETER").equals("Default"),
                "PyDev interpreter preserved");
        require(snap.descriptorSha256().size() == 6, "every present recognized descriptor stamped");
        require(snap.descriptorSha256().values().stream().allMatch(s -> s.matches("[a-f0-9]{64}")), "SHA-256 evidence");
        String json = snap.toJson();
        require(json.equals(EclipseProjectModel.inspect(root).toJson()), "deterministic serialization");
        require(json.startsWith("{\"schema\":\"m3-eclipse-project-v1\",\"name\":\"Mixed&Project\""), "versioned JSON");
        require(json.contains("\"classpath\":[{\"attributes\":{\"including\":\"**/*.java\""), "JSON classpath fidelity");
        require(json.contains("\"accessRules\":[{\"kind\":\"accessible\""), "JSON nested list is well formed");
        require(!Files.exists(root.resolve("pom.xml")) && !Files.exists(root.resolve("m3-build.tsv")),
                "inspection did not introduce Maven artifacts");
        passed++;
    }

    private static void allOptionalMetadataMayBeAbsent(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", "<projectDescription><name>Only-Core</name></projectDescription>");
        EclipseProjectModel.Snapshot snap = EclipseProjectModel.inspect(root);
        require(snap.name().equals("Only-Core") && snap.descriptorSha256().size() == 1
                && snap.classpath().isEmpty() && snap.cdtConfigurations().isEmpty()
                && snap.pydevPaths().isEmpty(), "minimal Eclipse Core project is inspectable");
        passed++;
    }

    private static void invalidXmlIsNotTrusted(Path root) throws Exception {
        Files.createDirectories(root);
        expectIOException(() -> EclipseProjectModel.inspect(root), "missing .project rejected");
        put(root, ".project", "<!DOCTYPE projectDescription [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]>"
                + "<projectDescription><name>&steal;</name></projectDescription>");
        expectIOException(() -> EclipseProjectModel.inspect(root), "external entity and DTD rejected");
        put(root, ".project", "<projectDescription><name>OK</name></projectDescription>");
        put(root, ".classpath", "<wrongRoot/>");
        expectIOException(() -> EclipseProjectModel.inspect(root), "incorrect optional descriptor rejected");
        put(root, ".classpath", "<classpath>");
        expectIOException(() -> EclipseProjectModel.inspect(root), "malformed optional descriptor rejected");
        Files.delete(root.resolve(".classpath"));
        put(root, ".pydevproject", "<pydev_project/>");
        require(EclipseProjectModel.inspect(root).descriptorSha256().containsKey(".pydevproject"), "valid optional descriptor");
        passed++;
    }

    private static void linkedMetadataIsNotFollowed(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", """
                <projectDescription><name>Linked</name><linkedResources><link>
                 <name>source</name><type>2</type><locationURI>file:///secret/directory</locationURI>
                </link></linkedResources></projectDescription>
                """);
        EclipseProjectModel.Snapshot snap = EclipseProjectModel.inspect(root);
        require(snap.linkedResources().get(0).locationUri().equals("file:///secret/directory"),
                "linked resource URI preserved and not traversed");
        Path target = root.resolve(".project-real");
        put(root, ".project-real", "<projectDescription><name>Other</name></projectDescription>");
        Files.delete(root.resolve(".project"));
        try {
            Files.createSymbolicLink(root.resolve(".project"), target);
            expectIOException(() -> EclipseProjectModel.inspect(root), "symlinked root descriptor rejected");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            // Platforms without symlinks still enforce NOFOLLOW_LINKS on files.
        }
        passed++;
    }

    private static void hashesRepresentActualByteChanges(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", "<projectDescription><name>Hashes</name></projectDescription>");
        put(root, "Cargo.toml", "[package]\nname='a'\n");
        String before = EclipseProjectModel.inspect(root).descriptorSha256().get("Cargo.toml");
        put(root, "Cargo.toml", "[package]\nname='b'\n");
        String after = EclipseProjectModel.inspect(root).descriptorSha256().get("Cargo.toml");
        require(!before.equals(after), "native Rust build descriptor SHA-256 reflects change");
        require(EclipseProjectModel.inspect(root).descriptorSha256().get(".project").equals(
                EclipseProjectModel.inspect(root).descriptorSha256().get(".project")), "identical inputs yield identical hashes");
        passed++;
    }

    private static void crossLanguageBuildDescriptorsRemainReadOnly(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", "<projectDescription><name>Universal</name></projectDescription>");
        put(root, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\\nBundle-SymbolicName: universal\\n");
        put(root, "plugin.xml", "<plugin/>");
        put(root, "build.properties", "bin.includes = plugin.xml");
        put(root, "universal.target", "<target name=\\\"universal\\\"/>");
        put(root, "feature.xml", "<feature/>");
        put(root, "CMakeLists.txt", "project(universal)");
        put(root, "Cargo.lock", "# Cargo lock");
        put(root, "Package.swift", "// swift-tools-version:5.9");
        put(root, "go.mod", "module example.org/universal");
        put(root, "pyproject.toml", "[project]\\nname = 'universal'");
        put(root, "package.json", "{\\\"name\\\":\\\"universal\\\"}");
        put(root, "tsconfig.json", "{}");
        put(root, "settings.gradle.kts", "rootProject.name = \\\"universal\\\"");
        put(root, "m3-build.tsv", "M3-POLYGLOT-TASKS-V1\\n");
        put(root, "target/generated.txt", "must not fingerprint generated outputs");
        var result = EclipseProjectModel.inspect(root);
        for (String key : List.of("META-INF/MANIFEST.MF", "plugin.xml", "build.properties",
                "universal.target", "feature.xml", "CMakeLists.txt", "Cargo.lock",
                "Package.swift", "go.mod", "pyproject.toml", "package.json",
                "tsconfig.json", "settings.gradle.kts", "m3-build.tsv")) {
            require(result.descriptorSha256().containsKey(key), "missing native descriptor: " + key);
            require(result.descriptorSha256().get(key).matches("[a-f0-9]{64}"),
                    "descriptor hash is not canonical: " + key);
        }
        require(!result.descriptorSha256().containsKey("target/generated.txt"),
                "generated files must not be mistaken for authoring metadata");
        require(result.toJson().equals(EclipseProjectModel.inspect(root).toJson()),
                "multi-language project model must be deterministic");
        String oldTarget = result.descriptorSha256().get("universal.target");
        put(root, "universal.target", "<target name=\\\"new\\\"/>");
        String newTarget = EclipseProjectModel.inspect(root).descriptorSha256().get("universal.target");
        require(!oldTarget.equals(newTarget), "PDE target changes must have distinct digests");
        require(result.descriptorSha256().get("Cargo.lock").equals(
                EclipseProjectModel.inspect(root).descriptorSha256().get("Cargo.lock")),
                "unmodified Rust dependency metadata retains its digest");
        require(!Files.exists(root.resolve("m3-build-new.tsv")),
                "inspection is read-only, not a Mavenizer rewrite");
        passed++;
    }

    private static void refusesSymlinkedOrOversizedLanguageDocuments(Path root) throws Exception {
        Files.createDirectories(root);
        put(root, ".project", "<projectDescription><name>Refusals</name></projectDescription>");
        Path document = root.resolve("linked.target");
        Path actual = root.resolve("ordinary.txt");
        Files.writeString(actual, "not-a-trusted-descriptor");
        try {
            Files.createSymbolicLink(document, actual);
            expectIOException(() -> EclipseProjectModel.inspect(root),
                    "linked target definition must not be followed");
            Files.delete(document);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            // Windows hosts without symlink privileges still enforce NOFOLLOW_LINKS.
        }
        for (int index = 0; index <= 128; index++) {
            put(root, "generated" + index + ".target", "<target/>");
        }
        expectIOException(() -> EclipseProjectModel.inspect(root),
                "unbounded PDE target files must fail closed");
        passed++;
    }

    private static void put(Path root, String name, String contents) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    private static void expectIOException(Checked action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Fail closed: never fabricate a partial Eclipse model.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }
}
