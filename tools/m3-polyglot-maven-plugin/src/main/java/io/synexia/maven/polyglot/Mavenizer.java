/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mavenizer: turns a folder built by other tools into Maven projects. Each project folder gets a pom.xml
 * (packaging pom, its sub-projects as modules) and an m3-build.tsv that maps the native tools' commands to
 * targets named like Maven phases, which the polyglot lifecycle runs in those phases. Existing files are never
 * overwritten; a folder that already has a pom.xml is a Maven project and is only listed as a module.
 */
public final class Mavenizer {
    static final int MAX_DEPTH = 4;
    static final Set<String> SKIP = Set.of(".git", ".hg", ".svn", ".m3", ".gradle", ".idea", ".vscode",
            "node_modules", "target", "build", "dist", "out", "bin", "obj", "vendor", "venv", ".venv", "__pycache__");
    static final List<String> PHASES = List.of("validate", "compile", "test", "package");
    private static final Pattern SCRIPTS = Pattern.compile("\"scripts\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    static final String CMAKE_BUILD = "build/cmake";
    static final String CMAKE_CONFIG = "Debug";
    /** clangd's project configuration naming the compile database; cdt-lsp reads and writes the same key. */
    static final String CLANGD = "CompileFlags:\n  CompilationDatabase: " + CMAKE_BUILD + "\n";

    /** One native command bound to a phase. */
    public record Step(String phase, String name, String tool, List<String> args) {
        public Step {
            args = List.copyOf(args);
        }
    }

    /** A project folder: its detected tools and steps, its direct sub-projects, and whether it already is Maven. */
    public record Project(Path dir, List<String> tools, List<Step> steps, List<Path> modules, boolean maven) { }

    private Mavenizer() { }

    /** Writes (or, without write, plans) the files of every converted project; returns one line per decision. */
    public static List<String> mavenize(Path root, String groupId, String version, boolean write) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Project> projects = scan(base);
        List<String> lines = new ArrayList<>();
        if (projects.isEmpty()) {
            lines.add("NOTHING " + base + " (no build tool found)");
            return lines;
        }
        for (Project project : projects) {
            if (project.maven()) {
                lines.add("MAVEN " + relative(base, project.dir()) + " (already a Maven project)");
            } else {
                convert(base, project, groupId, version, write, lines);
            }
        }
        return lines;
    }

    /** The files of one converted folder: pom.xml (MISSING-MODULE lines for a kept one), m3-build.tsv, .clangd. */
    private static void convert(Path base, Project project, String groupId, String version, boolean write,
            List<String> lines) throws IOException {
        List<String> modules = new ArrayList<>();
        for (Path module : project.modules()) modules.add(relative(project.dir(), module));
        String pom = pom(groupId, artifactId(project.dir()), version, project.tools(), modules, project.steps());
        emit(base, project.dir().resolve("pom.xml"), pom, write, lines);
        if (mavenized(project.dir())) missingModules(base, project.dir(), modules, lines);
        if (!project.steps().isEmpty()) emit(base, project.dir().resolve("m3-build.tsv"), manifest(project.steps()), write, lines);
        // clangd, and CDT's cdt-lsp editor, find the CMake compile database through .clangd.
        if (project.tools().contains("cmake")) emit(base, project.dir().resolve(".clangd"), CLANGD, write, lines);
    }

    private static void missingModules(Path base, Path dir, List<String> modules, List<String> lines) throws IOException {
        String kept = read(dir, "pom.xml");
        for (String module : modules) {
            if (!kept.contains("<module>" + xml(module) + "</module>")) {
                lines.add("MISSING-MODULE " + module + " in " + relative(base, dir.resolve("pom.xml"))
                        + " (the pom is kept; add the module by hand)");
            }
        }
    }

    private static void emit(Path base, Path file, String content, boolean write, List<String> lines) throws IOException {
        String name = relative(base, file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            lines.add("KEEP " + name + " (exists)");
        } else if (!write) {
            lines.add("PLAN " + name);
        } else {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            lines.add("WRITE " + name);
        }
    }

    /** Project folders, parents before children; the root is a project whenever anything below it is one. */
    static List<Project> scan(Path root) throws IOException {
        List<Project> found = new ArrayList<>();
        visit(root, 0, true, found);
        return found;
    }

    private static boolean visit(Path dir, int depth, boolean root, List<Project> found) throws IOException {
        if (has(dir, "pom.xml") && !mavenized(dir)) {
            found.add(new Project(dir, List.of("maven"), List.of(), List.of(), true));
            return true;
        }
        if (gradleBuildRoot(dir)) {
            List<Step> steps = steps(dir);
            found.add(new Project(dir, tools(steps), List.copyOf(steps), List.of(), false));
            return true;
        }
        int slot = found.size();
        List<Path> modules = new ArrayList<>();
        if (depth < MAX_DEPTH) {
            for (Path child : children(dir)) {
                if (visit(child, depth + 1, false, found)) modules.add(child);
            }
        }
        List<Step> steps = steps(dir);
        if (steps.isEmpty() && modules.isEmpty()) return false;
        found.add(slot, new Project(dir, tools(steps), List.copyOf(steps), List.copyOf(modules), false));
        return true;
    }

    /**
     * A folder with settings.gradle(.kts) is one Gradle build: its included projects, buildSrc and every build
     * below it (test fixtures, samples with their own settings) are Gradle's business. It becomes one project whose
     * steps run the build's own wrapper once; nothing below it becomes a Maven module.
     */
    static boolean gradleBuildRoot(Path dir) {
        return has(dir, "settings.gradle") || has(dir, "settings.gradle.kts");
    }

    private static List<String> tools(List<Step> steps) {
        List<String> tools = new ArrayList<>();
        for (Step step : steps) {
            String tool = step.name().substring(0, step.name().indexOf('-'));
            if (!tools.contains(tool)) tools.add(tool);
        }
        if (tools.isEmpty()) tools.add("aggregator");
        return List.copyOf(tools);
    }

    private static List<Path> children(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !SKIP.contains(path.getFileName().toString()) && !path.getFileName().toString().startsWith("."))
                    .sorted().forEach(result::add);
        }
        return result;
    }

    /** The native steps of one folder, by the build descriptors it holds. */
    static List<Step> steps(Path dir) throws IOException {
        List<Step> steps = new ArrayList<>();
        gradle(dir, steps);
        node(dir, steps);
        cargo(dir, steps);
        if (has(dir, "go.mod")) {
            add(steps, "compile", "go", "build", "./...");
            add(steps, "test", "go", "test", "./...");
        }
        make(dir, steps);
        cmake(dir, steps);
        if (has(dir, "meson.build")) {
            add(steps, "validate", "meson", "setup", "build/meson");
            add(steps, "compile", "meson", "compile", "-C", "build/meson");
            add(steps, "test", "meson", "test", "-C", "build/meson");
        }
        python(dir, steps);
        DotnetSteps.add(dir, steps);
        if (has(dir, "MODULE.bazel") || has(dir, "WORKSPACE") || has(dir, "WORKSPACE.bazel")) {
            add(steps, "compile", "bazel", "build", "//...");
            add(steps, "test", "bazel", "test", "//...");
        }
        if (has(dir, "Package.swift")) {
            add(steps, "compile", "swift", "build");
            add(steps, "test", "swift", "test");
        }
        if (has(dir, "build.xml")) {
            add(steps, "compile", "ant");
            if (read(dir, "build.xml").matches("(?s).*<target[^>]*name\\s*=\\s*\"test\".*")) add(steps, "test", "ant", "test");
        }
        return steps;
    }

    private static void gradle(Path dir, List<Step> steps) {
        if (!has(dir, "settings.gradle") && !has(dir, "settings.gradle.kts") && !has(dir, "build.gradle")
                && !has(dir, "build.gradle.kts")) return;
        String tool = has(dir, "gradlew") || has(dir, "gradlew.bat") ? "gradlew" : "gradle";
        steps.add(new Step("compile", "gradle-compile", tool, List.of("assemble")));
        steps.add(new Step("test", "gradle-test", tool, List.of("test")));
    }

    private static void node(Path dir, List<Step> steps) throws IOException {
        if (!has(dir, "package.json")) return;
        String tool = has(dir, "pnpm-lock.yaml") ? "pnpm" : has(dir, "yarn.lock") ? "yarn"
                : has(dir, "bun.lock") || has(dir, "bun.lockb") ? "bun" : "npm";
        add(steps, "validate", tool, "npm".equals(tool) && has(dir, "package-lock.json") ? "ci" : "install");
        Matcher scripts = SCRIPTS.matcher(read(dir, "package.json"));
        String block = scripts.find() ? scripts.group(1) : "";
        if (block.matches("(?s).*\"build\"\\s*:.*")) add(steps, "compile", tool, "run", "build");
        // The script value is a JSON string: escaped quotes (\") belong to it.
        Matcher test = Pattern.compile("\"test\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block);
        if (test.find() && !test.group(1).contains("no test specified")) add(steps, "test", tool, "test");
    }

    /**
     * Cargo: with a Cargo.lock every step passes --locked; tests run through {@link CargoTestProvider}, which writes
     * surefire reports, and do not stop at the first failing test binary; a [package] (not a virtual workspace) is
     * also packaged as a .crate in target/package (uncommitted files allowed: the generated pom.xml is one).
     */
    private static void cargo(Path dir, List<Step> steps) throws IOException {
        if (!has(dir, "Cargo.toml")) return;
        String[] locked = has(dir, "Cargo.lock") ? new String[] {"--locked"} : new String[0];
        add(steps, "compile", "cargo", with(locked, "build"));
        steps.add(new Step("test", "cargo-test", CargoTestProvider.TOOL, List.of(with(locked, "test", "--no-fail-fast"))));
        add(steps, "package", "cargo", with(locked, "build", "--release"));
        if (read(dir, "Cargo.toml").matches("(?s)(.*\\n)?\\s*\\[package\\].*")) {
            add(steps, "package", "cargo", with(locked, "package", "--allow-dirty", "--no-verify"));
        }
    }

    private static String[] with(String[] tail, String... head) {
        String[] all = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static void make(Path dir, List<Step> steps) throws IOException {
        String file = has(dir, "GNUmakefile") ? "GNUmakefile" : has(dir, "Makefile") ? "Makefile" : null;
        if (file == null) return;
        add(steps, "compile", "make");
        if (read(dir, file).matches("(?s)(.*\\n)?test\\s*:.*")) add(steps, "test", "make", "test");
    }

    /**
     * CMake: configure exports compile_commands.json (Makefile and Ninja generators; a Visual Studio generator
     * ignores it); build and test name the configuration, which a multi-config generator needs (without -C, CTest
     * reports "Not Run"); CTest writes JUnit XML to the module's target/surefire-reports (a relative
     * --output-junit is resolved in the test directory).
     */
    private static void cmake(Path dir, List<Step> steps) throws IOException {
        if (!has(dir, "CMakeLists.txt")) return;
        add(steps, "validate", "cmake", "-S", ".", "-B", CMAKE_BUILD, "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON");
        add(steps, "compile", "cmake", "--build", CMAKE_BUILD, "--config", CMAKE_CONFIG);
        String text = read(dir, "CMakeLists.txt");
        if (text.contains("enable_testing") || text.contains("add_test")) {
            add(steps, "test", "ctest", "--test-dir", CMAKE_BUILD, "-C", CMAKE_CONFIG, "--output-on-failure",
                    "--output-junit", "../../target/surefire-reports/TEST-ctest.xml");
        }
    }

    private static void python(Path dir, List<Step> steps) throws IOException {
        if (!has(dir, "pyproject.toml") && !has(dir, "setup.py") && !has(dir, "setup.cfg")) return;
        add(steps, "compile", "python", "-m", "compileall", "-q", "-x", "(node_modules|venv|build|target)", ".");
        boolean pytest = has(dir, "pytest.ini") || has(dir, "pyproject.toml") && read(dir, "pyproject.toml").contains("[tool.pytest");
        if (pytest) {
            add(steps, "test", "python", "-m", "pytest");
        } else if (Files.isDirectory(dir.resolve("tests"), LinkOption.NOFOLLOW_LINKS)) {
            add(steps, "test", "python", "-m", "unittest", "discover", "-s", "tests", "-t", ".");
        }
    }

    /** Adds the step named tool-phase (a second step of the same tool and phase gets -2, -3, ...). */
    private static void add(List<Step> steps, String phase, String tool, String... args) {
        String base = tool + "-" + phase;
        String name = base;
        for (int n = 2; taken(steps, name); n++) name = base + "-" + n;
        steps.add(new Step(phase, name, tool, List.of(args)));
    }

    private static boolean taken(List<Step> steps, String name) {
        return steps.stream().anyMatch(step -> step.name().equals(name));
    }

    /** The m3-build.tsv text: one task per step and one aggregation target per phase that has steps. */
    static String manifest(List<Step> steps) {
        StringBuilder text = new StringBuilder(TaskManifest.MAGIC).append('\n');
        for (Step step : steps) {
            text.append("task\t").append(step.name()).append('\t').append(step.tool()).append("\t.\n");
            for (String arg : step.args()) text.append("arg\t").append(step.name()).append('\t').append(arg).append('\n');
        }
        for (String phase : PHASES) {
            List<Step> bound = steps.stream().filter(step -> step.phase().equals(phase)).toList();
            if (bound.isEmpty()) continue;
            text.append("task\t").append(phase).append("\t-\t.\n");
            for (Step step : bound) text.append("dep\t").append(phase).append('\t').append(step.name()).append('\n');
        }
        return text.toString();
    }

    /** An aggregator pom for a mavenized folder. */
    static String pom(String groupId, String artifactId, String version, List<String> tools, List<String> modules) {
        return pom(groupId, artifactId, version, tools, modules, List.of());
    }

    static String pom(String groupId, String artifactId, String version, List<String> tools,
            List<String> modules, List<Step> steps) {
        StringBuilder text = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
                .append("  <modelVersion>4.0.0</modelVersion>\n")
                .append("  <groupId>").append(xml(groupId)).append("</groupId>\n")
                .append("  <artifactId>").append(xml(artifactId)).append("</artifactId>\n")
                .append("  <version>").append(xml(version)).append("</version>\n")
                .append("  <packaging>pom</packaging>\n")
                .append("  <name>").append(xml(artifactId + " (mavenized: " + String.join(", ", tools) + ")")).append("</name>\n");
        if (!modules.isEmpty()) {
            text.append("  <modules>\n");
            for (String module : modules) text.append("    <module>").append(xml(module)).append("</module>\n");
            text.append("  </modules>\n");
        }
        if (!steps.isEmpty()) profile(text, steps);
        return text.append("</project>\n").toString();
    }

    /** The opt-in m3-polyglot profile: one run execution per phase with steps, then the attach execution. */
    private static void profile(StringBuilder text, List<Step> steps) {
        // Opt-in profile for stock Maven, which lacks the fork's lifecycle extension.
        // Merely generating a POM must never execute native tools or download packages.
        text.append("  <profiles>\n")
                .append("    <profile><id>m3-polyglot</id>\n")
                .append("      <build><plugins><plugin>\n")
                .append("        <groupId>io.synexia</groupId>\n")
                .append("        <artifactId>m3-polyglot-maven-plugin</artifactId>\n")
                .append("        <version>0.1.0-SNAPSHOT</version>\n")
                .append("        <executions>\n");
        runExecutions(text, steps);
        if (DotnetSteps.packs(steps)) attach(text);
        text.append("        </executions>\n")
                .append("      </plugin></plugins></build>\n")
                .append("    </profile>\n")
                .append("  </profiles>\n");
    }

    /** One run execution for each phase that has steps, with the phase as its target. */
    private static void runExecutions(StringBuilder text, List<Step> steps) {
        for (String phase : PHASES) {
            if (steps.stream().noneMatch(step -> phase.equals(step.phase()))) continue;
            text.append("          <execution>\n")
                    .append("            <id>m3-polyglot-").append(phase).append("</id>\n")
                    .append("            <phase>").append(phase).append("</phase>\n")
                    .append("            <goals><goal>run</goal></goals>\n")
                    .append("            <configuration><target>").append(phase).append("</target></configuration>\n")
                    .append("          </execution>\n");
        }
    }

    /** After the package run, attach the packages it wrote (declared later, so it runs later in the phase). */
    private static void attach(StringBuilder text) {
        text.append("          <execution>\n")
                .append("            <id>m3-polyglot-attach</id>\n")
                .append("            <phase>package</phase>\n")
                .append("            <goals><goal>attach</goal></goals>\n")
                .append("          </execution>\n");
    }

    static String artifactId(Path dir) {
        Path name = dir.getFileName();
        String id = name == null ? "" : name.toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return id.isEmpty() ? "project" : id;
    }

    private static String relative(Path base, Path path) {
        String value = base.relativize(path).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }

    /** A pom.xml this tool wrote: its name carries the "(mavenized: ...)" mark; a hand-written pom has none. */
    static boolean mavenized(Path dir) throws IOException {
        return has(dir, "pom.xml") && read(dir, "pom.xml").matches("(?s).*<name>[^<]*\\(mavenized: [^<]*\\)</name>.*");
    }

    static boolean has(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    static boolean any(Path dir, String suffix) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.anyMatch(path -> path.getFileName().toString().endsWith(suffix)
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        }
    }

    /** Descriptor text; bytes that are not UTF-8 become replacement characters instead of failing the scan. */
    private static String read(Path dir, String name) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
