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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the plugin writes outside {@code target}, recorded in {@code <project>/.m3/native-build} (which
 * {@code mvn clean} keeps) so the {@code clean} goal can remove exactly that: the files and folders autoreconf
 * created in the source tree, and the hashes of published compile databases (see {@link CompileCommandsPublisher}).
 */
public final class GeneratedFiles {
    /** The record folder, project-relative. */
    public static final String RECORDS = ".m3/native-build";
    /** The record of files a bootstrap step created, project-relative. */
    public static final String BOOTSTRAP = RECORDS + "/bootstrap-files.txt";

    private static final Set<String> SKIPPED = Set.of("target", ".git", ".m3", ".svn", ".hg");

    private GeneratedFiles() {}

    /** Every file and folder below the source directory, project-relative, outside target and VCS folders. */
    public static Set<String> list(Layout layout) throws IOException {
        Path source = layout.path(layout.source());
        try (Stream<Path> walk = Files.walk(source)) {
            return walk.filter(p -> !p.equals(source) && !skipped(source, p))
                    .map(p -> WindowsPaths.relative(layout.basedir(), p)).collect(Collectors.toCollection(TreeSet::new));
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** Adds what exists now but not in {@code before} to the bootstrap record. */
    public static void record(Layout layout, Set<String> before) throws IOException {
        Set<String> all = new TreeSet<>(recorded(layout));
        list(layout).stream().filter(p -> !before.contains(p)).forEach(all::add);
        if (!all.isEmpty()) {
            TaskFileWriter.write(layout.path(BOOTSTRAP), all.stream().collect(Collectors.joining("\n", "", "\n")));
        }
    }

    /**
     * The recorded paths, project-relative, that are safe to delete: relative, without {@code .} or {@code ..}
     * segments, strictly below the source directory, outside target and VCS folders, and with no link (symbolic link,
     * junction) on the way, the entry itself included. The record lives in {@code .m3}, which {@code mvn clean}
     * keeps and which can be committed or copied, so its lines are never trusted further; see {@link #refused}.
     */
    public static List<String> recorded(Layout layout) throws IOException {
        return lines(layout).stream().filter(l -> confined(layout, l)).toList();
    }

    /** The recorded lines that {@link #recorded} leaves out, for a warning. */
    public static List<String> refused(Layout layout) throws IOException {
        return lines(layout).stream().filter(l -> !confined(layout, l)).toList();
    }

    private static List<String> lines(Layout layout) throws IOException {
        Path file = layout.path(BOOTSTRAP);
        return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of();
    }

    private static boolean confined(Layout layout, String entry) {
        boolean plain = !entry.startsWith("/") && entry.indexOf('\\') < 0 && entry.indexOf(':') < 0
                && Arrays.stream(entry.split("/")).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."));
        if (!plain) {
            return false;
        }
        Path source = layout.path(layout.source());
        Path path = layout.path(entry);
        return FileTrees.strictlyBelow(path, source) && !skipped(source, path) && noLink(source, path);
    }

    /** True when neither {@code path} nor a folder between it and {@code source} is a link of any kind. */
    private static boolean noLink(Path source, Path path) {
        return Stream.iterate(path, p -> p != null && !p.equals(source), Path::getParent)
                .noneMatch(GeneratedFiles::link);
    }

    private static boolean link(Path path) {
        try {
            return FileTrees.linkLike(path);
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static boolean skipped(Path source, Path path) {
        Path relative = source.relativize(path);
        return relative.getNameCount() > 0 && SKIPPED.contains(relative.getName(0).toString());
    }
}
