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
package org.apache.maven.recipes.javac;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.source.util.JavacTask;

/** In-process javac for the tests, with the plugin initialised directly, through {@code -Xplugin}, or not at all. */
final class Javac {
    /** How the plugin takes part in a compile. */
    enum Mode {
        /** Plain javac: the oracle. */
        NONE,
        /** {@code LstCollector.register(task, ...)} before the task runs, keeping every tree. */
        DIRECT,
        /**
         * {@code -processorpath <the compile's classpath>;<boot entry> -Xplugin:MavenRecipes census=... root=...}: the
         * processor path holds the classpath's entries in the same order plus the boot entry (the wiring for an
         * explicit {@code -proc:} or no classpath processor); the implementation comes from the test class path.
         */
        XPLUGIN,
        /** The boot entry appended to {@code -classpath}, with {@code -Xplugin:MavenRecipes ...} (implicit processing). */
        XPLUGIN_CP
    }

    /** The name of the plugin, as {@code -Xplugin:} takes it. */
    static final String PLUGIN = "MavenRecipes";

    /** The plugin options of one compile, after the plugin name and separated by spaces as javac passes them. */
    interface PluginArgs {
        /** {@code census=<census> root=<src> check=true}: the README's configuration plus the tree tripwire. */
        PluginArgs DEFAULT = (census, src) -> "census=" + census + " root=" + src + " check=true";

        String of(Path census, Path src);
    }

    /**
     * What one compile produced.
     *
     * @param success javac's result
     * @param diagnostics every diagnostic: kind, file, line, column, code, message
     * @param classes class file path to SHA-256
     * @param collector the plugin's collector in {@link Mode#DIRECT}
     * @param census the census text in {@link Mode#XPLUGIN}
     * @param output what javac wrote to its output writer (not diagnostics)
     */
    record Compilation(
            boolean success,
            List<String> diagnostics,
            Map<String, String> classes,
            Optional<LstCollector> collector,
            String census,
            String output) {}

    static final Path WORK = Path.of(System.getProperty("recipes.workDir", "target/lst-tests"));

    private Javac() {}

    /** Compiles a fixture under {@code WORK/<group>/<fixture>}; sources are written once per directory. */
    static Compilation compile(String group, Fixture fixture, Mode mode, String... options) throws IOException {
        return compile(group, fixture, mode, PluginArgs.DEFAULT, options);
    }

    /** As {@link #compile(String, Fixture, Mode, String...)} with the given plugin options. */
    static Compilation compile(String group, Fixture fixture, Mode mode, PluginArgs pluginArgs, String... options)
            throws IOException {
        Path dir = WORK.resolve(group).resolve(fixture.name()).toAbsolutePath();
        String label = label(mode, options);
        Path classes = dir.resolve("classes-" + label);
        Path census = dir.resolve("census-" + label + ".tsv");
        deleteCensus(census);
        emptyDirectory(classes);
        Path src = fixture.write(dir);
        String pluginOptions = pluginArgs.of(census, src);
        List<String> args = arguments(fixture, dir, classes, options);
        args.addAll(plugin(mode, args, pluginOptions));
        Optional<String> direct = mode == Mode.DIRECT ? Optional.of(pluginOptions) : Optional.empty();
        return run(new Run(src, fixture, args, direct, classes, census));
    }

    private static String label(Mode mode, String... options) {
        return mode.name().toLowerCase(Locale.ROOT) + "-"
                + String.join("", options).replaceAll("\\W", "");
    }

    /** One prepared compile: the fixture's sources, javac's arguments and where its outputs go. */
    private record Run(
            Path src, Fixture fixture, List<String> args, Optional<String> direct, Path classes, Path census) {
        List<Path> sources() {
            return fixture.files().keySet().stream().map(src::resolve).toList();
        }

        String censusText() throws IOException {
            return Files.isRegularFile(census) ? Files.readString(census, StandardCharsets.UTF_8) : "";
        }
    }

    private static List<String> plugin(Mode mode, List<String> args, String pluginOptions) {
        if (mode == Mode.NONE || mode == Mode.DIRECT) {
            return List.of();
        }
        int classpath = args.lastIndexOf("-classpath") + 1;
        String withBoot = args.get(classpath) + java.io.File.pathSeparator + bootPath();
        if (mode == Mode.XPLUGIN_CP) {
            args.set(classpath, withBoot);
            return List.of("-Xplugin:" + PLUGIN + " " + pluginOptions);
        }
        return List.of("-processorpath", withBoot, "-Xplugin:" + PLUGIN + " " + pluginOptions);
    }

    /** Starts the plugin on a task as an in-process caller does, before the task runs, keeping every tree. */
    static void registerPlugin(JavacTask task, String pluginOptions) {
        LstCollector.register(task, path -> true, pluginOptions.split("\\s+"));
    }

    /** The boot entry's classes: the javac Plugin registration and the classes built for Java 8. */
    static String bootPath() {
        return Path.of(System.getProperty("recipes.bootClasses", "target/boot-classes"))
                .toAbsolutePath()
                .toString();
    }

    private static List<String> arguments(Fixture fixture, Path dir, Path classes, String... options)
            throws IOException {
        Files.createDirectories(dir.resolve("empty"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        if (!fixture.options().contains("-classpath")) {
            args.addAll(List.of("-classpath", dir.resolve("empty").toString()));
        }
        args.addAll(fixture.options());
        args.addAll(List.of(options));
        return args;
    }

    private static void deleteCensus(Path census) throws IOException {
        if (Files.isDirectory(census)) {
            emptyDirectory(census);
        }
        Files.deleteIfExists(census);
    }

    private static Compilation run(Run run) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        Charset charset = run.fixture().managerCharset().orElse(null);
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, charset)) {
            Iterable<? extends JavaFileObject> sources = files.getJavaFileObjectsFromPaths(run.sources());
            JavacTask task = (JavacTask) javac.getTask(output, files, diagnostics, run.args(), null, sources);
            run.direct().ifPresent(options -> registerPlugin(task, options));
            Optional<LstCollector> collector = LstCollector.of(task);
            boolean success = task.call();
            List<String> described = describe(diagnostics.getDiagnostics(), run.src());
            return new Compilation(success, described, hashes(run.classes()), collector, run.censusText(), "" + output);
        }
    }

    private static List<String> describe(List<Diagnostic<? extends JavaFileObject>> diagnostics, Path src) {
        return diagnostics.stream()
                .map(d -> String.join(
                        "|",
                        d.getKind().name(),
                        source(d, src),
                        Long.toString(d.getLineNumber()),
                        Long.toString(d.getColumnNumber()),
                        String.valueOf(d.getCode()),
                        d.getMessage(Locale.ROOT)))
                .toList();
    }

    private static String source(Diagnostic<? extends JavaFileObject> diagnostic, Path src) {
        return diagnostic.getSource() == null
                ? "-"
                : src.relativize(Path.of(diagnostic.getSource().toUri()))
                        .toString()
                        .replace('\\', '/');
    }

    /** Creates the directory or deletes what an earlier run left in it; returns it. */
    static Path emptyDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(Javac::delete);
        }
        return dir;
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Class file path to SHA-256 of its bytes. */
    static Map<String, String> hashes(Path classes) throws IOException {
        try (Stream<Path> walk = Files.walk(classes)) {
            return walk.filter(Files::isRegularFile)
                    .collect(Collectors.toMap(
                            p -> classes.relativize(p).toString().replace('\\', '/'),
                            Javac::sha256,
                            (a, b) -> a,
                            TreeMap::new));
        }
    }

    static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The plugin's runtime classpath: its classes and the jars Maven resolved (maven-dependency-plugin output). */
    static String pluginPath() throws IOException {
        Path jars = Path.of(System.getProperty("recipes.pluginClasspath", "target/plugin-classpath.txt"));
        String classes = System.getProperty("recipes.pluginClasses", "target/classes");
        return classes
                + java.io.File.pathSeparator
                + Files.readString(jars, StandardCharsets.UTF_8).trim();
    }
}
