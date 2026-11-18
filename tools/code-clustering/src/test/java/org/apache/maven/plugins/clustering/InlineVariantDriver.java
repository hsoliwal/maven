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
package org.apache.maven.plugins.clustering;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs javac in this process once per plugin jar variant and reports, per variant, whether javac stayed green, how many
 * warnings the plugin logged and what the table directory holds afterwards. It uses JDK classes only: it runs in a new
 * virtual machine whose class path holds the test classes but not the plugin's, so javac loads the plugin from each
 * variant jar alone. Arguments: a file listing the variant jars, a file listing the sources, the source root, a
 * directory with the tables of an earlier compilation (copied into the output first), the reference tables of this
 * compilation, and a work directory. One line per variant:
 * {@code VARIANT <jar name> javac=<green|red|thrown:...> warnings=<n> tables=<new|absent|mixed:<n>|earlier>}.
 */
public final class InlineVariantDriver {
    static final List<String> TABLES = List.of("ATOM_HASHES.tsv", "CLUSTERS.tsv", "PLANE_SUMMARY.tsv",
            "FILE_PAIRS.tsv", "FILE_CLUSTERS.tsv", "JNI.tsv", "ATOM_SOURCES.tsv");
    private static final Logger PLUGIN_LOGS = Logger.getLogger("org.apache.maven.plugins.clustering");
    private static final AtomicInteger WARNINGS = new AtomicInteger();

    private InlineVariantDriver() { }

    public static void main(String[] args) throws IOException {
        PLUGIN_LOGS.setUseParentHandlers(false);
        PLUGIN_LOGS.addHandler(new Counter());
        List<Path> sources = paths(Path.of(args[1]));
        Path root = Path.of(args[2]);
        Path earlier = Path.of(args[3]);
        Path reference = Path.of(args[4]);
        Path work = Path.of(args[5]);
        paths(Path.of(args[0])).forEach(jar -> System.out.println(variant(jar, sources, root, earlier, reference, work)));
    }

    /** One variant: the earlier tables copied into a new output, javac run with the variant, the result described. */
    private static String variant(Path jar, List<Path> sources, Path root, Path earlier, Path reference, Path work) {
        String name = jar.getFileName().toString();
        Path out = work.resolve("out-" + name);
        TABLES.forEach(t -> copy(earlier.resolve(t), out.resolve(t)));
        WARNINGS.set(0);
        String javac = javac(jar, sources, root, out, work.resolve("classes-" + name));
        return "VARIANT " + name + " javac=" + javac + " warnings=" + WARNINGS.get() + " tables=" + tables(out, reference);
    }

    private static String javac(Path jar, List<Path> sources, Path root, Path out, Path classes) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> options = List.of("-encoding", "UTF-8", "-d", classes.toString(), "--processor-path", jar.toString(),
                "-Xplugin:CodeClustering out=" + out.toUri() + " roots=" + root.toUri() + " full=true");
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Files.createDirectories(classes);
            StringWriter log = new StringWriter();
            boolean ok = compiler.getTask(log, fm, null, options, null, fm.getJavaFileObjectsFromPaths(sources)).call();
            return ok ? "green" : "red:" + log.toString().lines().findFirst().orElse("");
        } catch (Throwable thrown) {
            return "thrown:" + thrown;
        }
    }

    /** new: every table, equal to the reference; absent: none; mixed: some; earlier: all, not the reference. */
    private static String tables(Path out, Path reference) {
        List<String> present = TABLES.stream().filter(t -> Files.isRegularFile(out.resolve(t))).collect(Collectors.toList());
        if (present.isEmpty()) return "absent";
        if (present.size() < TABLES.size()) return "mixed:" + present.size();
        return TABLES.stream().allMatch(t -> same(out.resolve(t), reference.resolve(t))) ? "new" : "earlier";
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.mismatch(a, b) == -1L;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> paths(Path list) throws IOException {
        try (Stream<String> lines = Files.lines(list, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).map(Path::of).collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Counts the warnings the plugin logs (java.util.logging, the default System.Logger back end). */
    private static final class Counter extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) WARNINGS.incrementAndGet();
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }
}
