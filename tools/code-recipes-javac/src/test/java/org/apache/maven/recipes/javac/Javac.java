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
        /** {@code new MavenRecipesPlugin().init(task, ...)} before the task runs. */
        DIRECT,
        /** {@code -processorpath <plugin classpath> -Xplugin:MavenRecipes census=... root=...}. */
        XPLUGIN
    }

    /**
     * What one compile produced.
     *
     * @param success javac's result
     * @param diagnostics every diagnostic: kind, file, line, column, code, message
     * @param classes class file path to SHA-256
     * @param collector the plugin's collector in {@link Mode#DIRECT}
     * @param census the census text in {@link Mode#XPLUGIN}
     */
    record Compilation(
            boolean success,
            List<String> diagnostics,
            Map<String, String> classes,
            Optional<LstCollector> collector,
            String census) {}

    static final Path WORK = Path.of(System.getProperty("recipes.workDir", "target/lst-tests"));

    private Javac() {}

    /** Compiles a fixture under {@code WORK/<group>/<fixture>}; sources are written once per directory. */
    static Compilation compile(String group, Fixture fixture, Mode mode, String... options) throws IOException {
        Path dir = WORK.resolve(group).resolve(fixture.name()).toAbsolutePath();
        Path src = fixture.write(dir);
        String label = mode.name().toLowerCase(Locale.ROOT) + "-"
                + String.join("", options).replaceAll("\\W", "");
        Path classes = dir.resolve("classes-" + label);
        Path census = dir.resolve("census-" + label + ".tsv");
        Files.deleteIfExists(census);
        emptyDirectory(classes);
        Files.createDirectories(dir.resolve("empty"));
        List<String> args = new ArrayList<>(List.of(
                "-d", classes.toString(), "-classpath", dir.resolve("empty").toString()));
        args.addAll(fixture.options());
        args.addAll(List.of(options));
        if (mode == Mode.XPLUGIN) {
            args.addAll(List.of(
                    "-processorpath",
                    pluginPath(),
                    "-Xplugin:" + MavenRecipesPlugin.NAME + " census=" + census + " root=" + src));
        }
        return run(src, fixture, args, mode, classes, census);
    }

    private static Compilation run(Path src, Fixture fixture, List<String> args, Mode mode, Path classes, Path census)
            throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                javac.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<Path> sources =
                    fixture.files().keySet().stream().map(src::resolve).toList();
            JavacTask task = (JavacTask) javac.getTask(
                    new StringWriter(), files, diagnostics, args, null, files.getJavaFileObjectsFromPaths(sources));
            if (mode == Mode.DIRECT) {
                new MavenRecipesPlugin().init(task, "root=" + src);
            }
            Optional<LstCollector> collector = LstCollector.of(task);
            boolean success = task.call();
            String censusText = Files.exists(census) ? Files.readString(census, StandardCharsets.UTF_8) : "";
            return new Compilation(
                    success, describe(diagnostics.getDiagnostics(), src), hashes(classes), collector, censusText);
        }
    }

    private static List<String> describe(List<Diagnostic<? extends JavaFileObject>> diagnostics, Path src) {
        return diagnostics.stream()
                .map(d -> String.join(
                        "|",
                        d.getKind().name(),
                        d.getSource() == null
                                ? "-"
                                : src.relativize(Path.of(d.getSource().toUri()))
                                        .toString()
                                        .replace('\\', '/'),
                        Long.toString(d.getLineNumber()),
                        Long.toString(d.getColumnNumber()),
                        String.valueOf(d.getCode()),
                        d.getMessage(Locale.ROOT)))
                .toList();
    }

    /** Creates the directory or deletes what an earlier run left in it. */
    static void emptyDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(Javac::delete);
        }
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
