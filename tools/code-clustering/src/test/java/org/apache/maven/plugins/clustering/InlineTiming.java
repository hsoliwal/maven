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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compile cost with and without the javac plugin, in one JVM: one warm-up compilation of each kind, then
 * {@code runs} pairs in alternating order over the same sources. Both kinds get the same processor path; only
 * {@code -Xplugin} differs. It reports wall time and the CPU time of the compiling thread (javac runs in the
 * calling thread), which a busy machine disturbs far less than wall time. Not part of the test run:
 * <pre>
 * java -cp target/test-classes:target/classes org.apache.maven.plugins.clustering.InlineTiming \
 *     &lt;source-root&gt; &lt;runs&gt; &lt;work-dir&gt; [javac argument...]
 * </pre>
 */
public final class InlineTiming {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private InlineTiming() { }

    /** One compilation's cost in milliseconds. */
    private record Cost(long wall, long cpu) { }

    public static void main(String[] args) throws IOException {
        Path src = Path.of(args[0]);
        int runs = Integer.parseInt(args[1]);
        Path work = Path.of(args[2]);
        List<String> extra = Arrays.asList(args).subList(3, args.length);
        List<Path> files = InlinePluginTest.sources(src);
        compile(files, work, extra, false);
        compile(files, work, extra, true);
        List<Cost> off = new ArrayList<>();
        List<Cost> on = new ArrayList<>();
        for (int i = 1; i <= runs; i++) pair(i, files, work, extra, off, on);
        System.out.println("files " + files.size() + " runs " + runs + " java " + Runtime.version()
                + " atoms " + (Files.readAllLines(work.resolve("tables/ATOM_HASHES.tsv")).size() - 1));
        System.out.println(summary("wall", off.stream().mapToLong(Cost::wall).toArray(), on.stream().mapToLong(Cost::wall).toArray()));
        System.out.println(summary("cpu", off.stream().mapToLong(Cost::cpu).toArray(), on.stream().mapToLong(Cost::cpu).toArray()));
    }

    private static void pair(int i, List<Path> files, Path work, List<String> extra, List<Cost> off, List<Cost> on)
            throws IOException {
        boolean offFirst = i % 2 == 1;
        Cost first = compile(files, work, extra, !offFirst);
        Cost second = compile(files, work, extra, offFirst);
        Cost without = offFirst ? first : second;
        Cost with = offFirst ? second : first;
        off.add(without);
        on.add(with);
        System.out.println("pair " + i + " off_wall_ms " + without.wall() + " on_wall_ms " + with.wall()
                + " off_cpu_ms " + without.cpu() + " on_cpu_ms " + with.cpu());
    }

    private static Cost compile(List<Path> files, Path work, List<String> extra, boolean plugin) throws IOException {
        Path out = work.resolve(plugin ? "classes-on" : "classes-off");
        Files.createDirectories(out);
        List<String> options = new ArrayList<>(List.of("-nowarn", "-XDsuppressNotes", "-d", out.toString(),
                "-processorpath", InlinePluginTest.processorPath(work)));
        options.addAll(extra);
        if (plugin) options.add("-Xplugin:" + InlineClustering.NAME + " out=" + work.resolve("tables"));
        long wall = System.nanoTime();
        long cpu = THREADS.getCurrentThreadCpuTime();
        run(options, files);
        return new Cost((System.nanoTime() - wall) / 1_000_000, (THREADS.getCurrentThreadCpuTime() - cpu) / 1_000_000);
    }

    private static void run(List<String> options, List<Path> files) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            StringWriter log = new StringWriter();
            if (!javac.getTask(log, fm, null, options, null, fm.getJavaFileObjectsFromPaths(files)).call()) {
                throw new IllegalStateException("javac failed: " + log.toString().lines().limit(5).toList());
            }
        }
    }

    private static String summary(String kind, long[] off, long[] on) {
        long a = median(off);
        long b = median(on);
        return "TIMING " + kind + " median off_ms " + a + " on_ms " + b + " overhead_ms " + (b - a)
                + " overhead_pct " + String.format("%.1f", 100.0 * (b - a) / a);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
