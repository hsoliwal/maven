/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Mavenizer detection, project layout, file safety, and the runner rules it relies on. No build tool runs. */
public final class MavenizerContractTestMain {
    private static int passed;

    private MavenizerContractTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temp = Files.createTempDirectory("mavenizer-test-");
        try {
            detectsEachToolFamily(temp.resolve("detect"));
            mavenizesANestedRepositoryWithoutOverwriting(temp.resolve("repo"));
            pointsClangdAtTheCMakeCompileDatabase(temp.resolve("clangd"));
            neverSkipsATaskWithoutDeclaredInputs(temp.resolve("noinputs"));
            resolvesBareToolNames(temp.resolve("tools"));
            System.out.println("PASS: " + passed + " Mavenizer contract cases");
        } finally {
            erase(temp);
        }
    }

    private static void detectsEachToolFamily(Path root) throws Exception {
        expect(dir(root, "npm", "package.json", "{\"scripts\": {\"build\": \"tsc\", \"test\": \"vitest\"}}", "package-lock.json", "{}"),
                "validate npm-validate npm [ci]", "compile npm-compile npm [run, build]", "test npm-test npm [test]");
        expect(dir(root, "npm-placeholder", "package.json",
                "{\"scripts\": {\"test\": \"echo \\\"Error: no test specified\\\" && exit 1\"}}"),
                "validate npm-validate npm [install]");
        expect(dir(root, "pnpm", "package.json", "{}", "pnpm-lock.yaml", ""), "validate pnpm-validate pnpm [install]");
        expect(dir(root, "cargo", "Cargo.toml", "[package]"),
                "compile cargo-compile cargo [build]", "test cargo-test @m3:cargo:interpreter:rust [test, --no-fail-fast]",
                "package cargo-package cargo [build, --release]",
                "package cargo-package-2 cargo [package, --allow-dirty, --no-verify]");
        // A lock file makes every step --locked; a virtual workspace has no crate of its own to package.
        expect(dir(root, "cargo-workspace", "Cargo.toml", "[workspace]\nmembers = [\"a\"]\n\n[workspace.package]\n",
                "Cargo.lock", ""),
                "compile cargo-compile cargo [build, --locked]",
                "test cargo-test @m3:cargo:interpreter:rust [test, --no-fail-fast, --locked]",
                "package cargo-package cargo [build, --release, --locked]");
        expect(dir(root, "go", "go.mod", "module x"), "compile go-compile go [build, ./...]", "test go-test go [test, ./...]");
        expect(dir(root, "make", "Makefile", "all:\n\tcc x.c\ntest: all\n\t./x\n"), "compile make-compile make []", "test make-test make [test]");
        expect(dir(root, "make-notest", "Makefile", "all:\n\tcc x.c\n"), "compile make-compile make []");
        expect(dir(root, "cmake", "CMakeLists.txt", "project(x)\nenable_testing()\n"),
                "validate cmake-validate cmake [-S, ., -B, build/cmake, -DCMAKE_EXPORT_COMPILE_COMMANDS=ON]",
                "compile cmake-compile cmake [--build, build/cmake, --config, Debug]",
                "test ctest-test ctest [--test-dir, build/cmake, -C, Debug, --output-on-failure, --output-junit, "
                        + "../../target/surefire-reports/TEST-ctest.xml]");
        expect(dir(root, "cmake-notest", "CMakeLists.txt", "project(x)\n"),
                "validate cmake-validate cmake [-S, ., -B, build/cmake, -DCMAKE_EXPORT_COMPILE_COMMANDS=ON]",
                "compile cmake-compile cmake [--build, build/cmake, --config, Debug]");
        Path python = dir(root, "python", "pyproject.toml", "[project]\nname='x'\n");
        Files.createDirectories(python.resolve("tests"));
        expect(python, "compile python-compile python [-m, compileall, -q, -x, (node_modules|venv|build|target), .]",
                "test python-test python [-m, unittest, discover, -s, tests, -t, .]");
        expect(dir(root, "pytest", "pyproject.toml", "[tool.pytest.ini_options]\n"),
                "compile python-compile python [-m, compileall, -q, -x, (node_modules|venv|build|target), .]",
                "test python-test python [-m, pytest]");
        expect(dir(root, "dotnet", "App.csproj", "<Project/>"), "compile dotnet-compile dotnet [build]", "test dotnet-test dotnet [test]");
        expect(dir(root, "gradle", "build.gradle.kts", "", "gradlew.bat", ""),
                "compile gradle-compile gradlew [assemble]", "test gradle-test gradlew [test]");
        expect(dir(root, "ant", "build.xml", "<project><target name=\"test\"/></project>"),
                "compile ant-compile ant []", "test ant-test ant [test]");
        require(Mavenizer.steps(Files.createDirectories(root.resolve("empty"))).isEmpty(), "no descriptor, no steps");
        require(Mavenizer.artifactId(Path.of("My App+2")).equals("my-app-2"), "artifactId sanitized");
        passed++;
    }

    private static void mavenizesANestedRepositoryWithoutOverwriting(Path root) throws Exception {
        dir(root, "web", "package.json", "{\"scripts\": {\"test\": \"node t.js\"}}");
        dir(root, "svc", "go.mod", "module svc");
        dir(root, "lib", "pom.xml", "<project/>");
        dir(root.resolve("tools"), "native", "Makefile", "all:\n\techo ok\n");
        dir(root.resolve("web/node_modules"), "dep", "package.json", "{}");
        dir(root, ".hidden", "Cargo.toml", "[package]");
        List<String> plan = Mavenizer.mavenize(root, "org.example", "1.0-SNAPSHOT", false);
        require(plan.equals(List.of("PLAN pom.xml", "MAVEN lib (already a Maven project)", "PLAN svc/pom.xml",
                "PLAN svc/m3-build.tsv", "PLAN tools/pom.xml", "PLAN tools/native/pom.xml", "PLAN tools/native/m3-build.tsv",
                "PLAN web/pom.xml", "PLAN web/m3-build.tsv")), "plan: " + plan);
        require(!Files.exists(root.resolve("pom.xml")) && !Files.exists(root.resolve("web/pom.xml")), "a dry run writes nothing");
        List<String> written = Mavenizer.mavenize(root, "org.example", "1.0-SNAPSHOT", true);
        require(written.stream().filter(line -> line.startsWith("WRITE ")).count() == 8, "written: " + written);
        require(modules(root.resolve("pom.xml")).equals(List.of("lib", "svc", "tools", "web")), "root modules");
        require(modules(root.resolve("tools/pom.xml")).equals(List.of("native")), "intermediate aggregator");
        Document web = xml(root.resolve("web/pom.xml"));
        require(web.getElementsByTagName("artifactId").item(0).getTextContent().equals("web")
                && web.getElementsByTagName("groupId").item(0).getTextContent().equals("org.example")
                && web.getElementsByTagName("packaging").item(0).getTextContent().equals("pom"), "module pom coordinates");
        // A generated POM becomes an actual stock-Maven build only when the
        // operator selects the profile; the default lifecycle remains unchanged.
        require(web.getElementsByTagName("profile").getLength() == 1
                && web.getElementsByTagName("id").item(0).getTextContent().equals("m3-polyglot"),
                "native-language profile exists but is opt-in");
        require(web.getElementsByTagName("activeByDefault").getLength() == 0,
                "native-language execution is never active by default");
        Element plugin = (Element) web.getElementsByTagName("plugin").item(0);
        require(plugin != null
                && plugin.getElementsByTagName("artifactId").item(0).getTextContent()
                        .equals("m3-polyglot-maven-plugin"),
                "generated executions delegate to the original Maven polyglot plugin");
        List<String> bound = new ArrayList<>();
        for (int i = 0; i < web.getElementsByTagName("execution").getLength(); i++) {
            Element execution = (Element) web.getElementsByTagName("execution").item(i);
            bound.add(execution.getElementsByTagName("phase").item(0).getTextContent() + "/"
                    + execution.getElementsByTagName("target").item(0).getTextContent());
        }
        require(bound.equals(List.of("validate/validate", "test/test")),
                "only native steps bind, in Maven lifecycle order: " + bound);
        require(xml(root.resolve("pom.xml")).getElementsByTagName("execution").getLength() == 0,
                "the root aggregator does not run duplicate native tasks");
        require(xml(root.resolve("tools/pom.xml")).getElementsByTagName("execution").getLength() == 0,
                "intermediate aggregators do not run duplicate native tasks");
        require(Files.readString(root.resolve("lib/pom.xml")).equals("<project/>"), "an existing Maven project is untouched");
        TaskManifest manifest = TaskManifest.load(root.resolve("web/m3-build.tsv"));
        require(names(manifest.order("test")).equals(List.of("npm-test", "test")), "phase target runs its own steps only");
        require(names(manifest.order("validate")).equals(List.of("npm-validate", "validate")), "validate target");
        TaskManifest.load(root.resolve("svc/m3-build.tsv"));
        TaskManifest.load(root.resolve("tools/native/m3-build.tsv"));
        require(!Files.exists(root.resolve("web/node_modules/dep/pom.xml")) && !Files.exists(root.resolve(".hidden/pom.xml")),
                "skipped folders are not converted");
        Files.writeString(root.resolve("web/m3-build.tsv"), "edited", StandardCharsets.UTF_8);
        List<String> again = Mavenizer.mavenize(root, "org.example", "1.0-SNAPSHOT", true);
        require(again.stream().noneMatch(line -> line.startsWith("WRITE ")) && again.contains("KEEP web/m3-build.tsv (exists)"),
                "a second run keeps every file: " + again);
        require(Files.readString(root.resolve("web/m3-build.tsv")).equals("edited"), "an edited file is never overwritten");
        require(again.contains("MAVEN lib (already a Maven project)") && again.contains("KEEP pom.xml (exists)"),
                "a mavenized pom is recognised and scanned again; a hand-written one stops the scan: " + again);
        dir(root, "rust", "Cargo.toml", "[package]");
        List<String> grown = Mavenizer.mavenize(root, "org.example", "1.0-SNAPSHOT", true);
        require(grown.contains("WRITE rust/pom.xml") && grown.contains("WRITE rust/m3-build.tsv")
                && grown.contains("MISSING-MODULE rust in pom.xml (the pom is kept; add the module by hand)"),
                "a new sub-project is written and the kept parent names it: " + grown);
        require(modules(root.resolve("pom.xml")).equals(List.of("lib", "svc", "tools", "web")), "the kept root pom is unchanged");
        Path nothing = Files.createDirectories(root.resolve("../nothing"));
        require(Mavenizer.mavenize(nothing, "g", "1", true).get(0).startsWith("NOTHING "), "no build tool found");
        passed++;
    }

    private static void pointsClangdAtTheCMakeCompileDatabase(Path root) throws Exception {
        dir(root, "native", "CMakeLists.txt", "project(x)\nenable_testing()\n");
        dir(root, "kept", "CMakeLists.txt", "project(y)\n", ".clangd", "CompileFlags:\n  Add: [-Wall]\n");
        dir(root, "crate", "Cargo.toml", "[package]\n");
        List<String> plan = Mavenizer.mavenize(root, "org.example", "1", false);
        require(plan.contains("PLAN native/.clangd") && plan.contains("KEEP kept/.clangd (exists)")
                && plan.stream().noneMatch(line -> line.startsWith("PLAN crate/.clangd")), "plan: " + plan);
        Mavenizer.mavenize(root, "org.example", "1", true);
        require(Files.readString(root.resolve("native/.clangd")).equals("CompileFlags:\n  CompilationDatabase: build/cmake\n"),
                "the .clangd pointer names the configure step's build folder");
        require(Files.readString(root.resolve("kept/.clangd")).equals("CompileFlags:\n  Add: [-Wall]\n"),
                "an existing .clangd (cdt-lsp writes one too) is never replaced");
        TaskManifest crate = TaskManifest.load(root.resolve("crate/m3-build.tsv"));
        require(names(crate.order("test")).equals(List.of("cargo-test", "test"))
                && crate.order("test").get(0).tool().equals(CargoTestProvider.TOOL), "cargo tests run through the provider");
        require(names(crate.order("package")).equals(List.of("cargo-package", "cargo-package-2", "package")),
                "release build and .crate both bind to package");
        passed++;
    }

    private static void neverSkipsATaskWithoutDeclaredInputs(Path project) throws Exception {
        Files.createDirectories(project);
        String jdk = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.writeString(project.resolve("Count.java"), "import java.nio.file.*;\npublic class Count { public static void main(String[] a)"
                + " throws Exception { Files.writeString(Path.of(\"runs.log\"), \"run\\n\", StandardOpenOption.CREATE,"
                + " StandardOpenOption.APPEND); } }\n", StandardCharsets.UTF_8);
        Path file = Files.writeString(project.resolve("m3-build.tsv"), TaskManifest.MAGIC + "\n"
                + "task\tcount\t" + jdk + "\t.\narg\tcount\tCount.java\n", StandardCharsets.UTF_8);
        TaskManifest manifest = TaskManifest.load(file);
        for (int i = 0; i < 2; i++) TaskRunner.run(manifest, project, "count", false, Duration.ofSeconds(60), false, true, line -> {});
        require(Files.readAllLines(project.resolve("runs.log")).size() == 2, "a task without declared inputs runs every time");
        require(!Files.exists(project.resolve(".m3/module.m3db")), "and records no reusable module state");
        passed++;
    }

    private static void resolvesBareToolNames(Path folder) throws Exception {
        Files.createDirectories(folder);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        require(TaskRunner.resolveTool(java, folder).equals(java), "a path is passed unchanged");
        require(TaskRunner.resolveTool("no-such-tool-anywhere", folder).equals("no-such-tool-anywhere"), "an unknown name is unchanged");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path wrapper = folder.resolve(windows ? "wrapped.bat" : "wrapped");
        Files.writeString(wrapper, windows ? "@echo off\r\n" : "#!/bin/sh\n", StandardCharsets.UTF_8);
        if (!windows) wrapper.toFile().setExecutable(true);
        require(TaskRunner.resolveTool("wrapped", folder).equals(wrapper.toString()), "a wrapper in the working folder is found first");
        if (windows) {
            Files.writeString(folder.resolve("bare"), "#!/bin/sh\n", StandardCharsets.UTF_8);
            require(TaskRunner.resolveTool("bare", folder).equals("bare"), "on Windows an extensionless script is not a tool");
        }
        passed++;
    }

    private static void expect(Path dir, String... rows) throws IOException {
        List<String> actual = new ArrayList<>();
        for (Mavenizer.Step step : Mavenizer.steps(dir)) actual.add(step.phase() + " " + step.name() + " " + step.tool() + " " + step.args());
        require(actual.equals(List.of(rows)), dir.getFileName() + ": " + actual);
    }

    private static Path dir(Path parent, String name, String... files) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(name));
        for (int i = 0; i < files.length; i += 2) Files.writeString(dir.resolve(files[i]), files[i + 1], StandardCharsets.UTF_8);
        return dir;
    }

    private static List<String> modules(Path pom) throws Exception {
        Document document = xml(pom);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < document.getElementsByTagName("module").getLength(); i++) {
            result.add(document.getElementsByTagName("module").item(i).getTextContent());
        }
        return result;
    }

    private static Document xml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static List<String> names(List<TaskManifest.Task> tasks) {
        return tasks.stream().map(TaskManifest.Task::name).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void erase(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
