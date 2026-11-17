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
package org.apache.maven.plugins.clustering.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.lsp.ServerSpec.Unresolved;

/**
 * The command of an Eclipse JDT Language Server install, the way its own {@code bin/jdtls} script builds it:
 * <pre>java [-Djdk.xml.*=0] -Declipse.application=org.eclipse.jdt.ls.core.id1 ... -jar
 *     plugins/org.eclipse.equinox.launcher_*.jar -configuration config_win|config_linux|config_mac -data DIR</pre>
 * The install is a home directory with {@code plugins/org.eclipse.equinox.launcher_*.jar}, or the install whose
 * {@code bin/jdtls} (Windows: {@code bin/jdtls.bat}) is on the PATH. The Java it needs is at least 21, or the
 * {@code Bundle-RequiredExecutionEnvironment} of its {@code org.eclipse.jdt.core} bundle when that is newer (1.63
 * requires 25). The first Java that is new enough is used, from {@code clustering.lsp.jdt.java}
 * ({@code CLUSTERING_LSP_JDT_JAVA}: a JDK directory or its java executable), {@code JAVA_HOME}, and the Java running
 * this code; a JDK's version is read from its {@code release} file, and one without that file is assumed new enough.
 * The workspace data directory is {@code clustering.lsp.jdt.data} ({@code CLUSTERING_LSP_JDT_DATA}), else
 * {@code code-clustering-jdtls-data} in the temporary directory; a running server locks it, so give each concurrent
 * build its own, and keep it between builds for a faster start.
 */
final class JdtlsCommand {
    static final int MINIMUM_JAVA = 21;
    private static final Pattern LAUNCHER = Pattern.compile("org\\.eclipse\\.equinox\\.launcher_\\d.*\\.jar");
    private static final Pattern CORE = Pattern.compile("org\\.eclipse\\.jdt\\.core_\\d.*\\.jar");
    private static final Pattern VERSION = Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)");
    private static final Pattern REQUIRED = Pattern.compile("JavaSE-(?:1\\.)?(\\d+)");

    /** A Java installation and its feature version ({@code -1} when unknown). */
    record Java(Path executable, int version) { }

    private JdtlsCommand() { }

    static List<String> detect(Lookup lookup, List<Path> homes) throws Unresolved {
        Path home = install(lookup, homes);
        int required = Math.max(MINIMUM_JAVA, requiredJava(home));
        Java java = java(lookup, required);
        List<String> command = new ArrayList<>(List.of(java.executable().toString()));
        if (java.version() >= 24) command.addAll(List.of("-Djdk.xml.maxGeneralEntitySizeLimit=0", "-Djdk.xml.totalEntitySizeLimit=0"));
        command.addAll(List.of("-Declipse.application=org.eclipse.jdt.ls.core.id1", "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product", "-Xms256m", "-Xmx1G", "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", launcher(home).orElseThrow().toString(), "-configuration", configuration(lookup, home).toString(),
                "-data", data(lookup).toString()));
        return command;
    }

    static Path install(Lookup lookup, List<Path> homes) throws Unresolved {
        Optional<Path> home = homes.stream().filter(h -> launcher(h).isPresent()).findFirst();
        if (home.isPresent()) return home.get();
        Optional<Path> script = lookup.fileOnPath(lookup.windows() ? "jdtls.bat" : "jdtls");
        return script.flatMap(JdtlsCommand::realParent).map(Path::getParent).filter(h -> launcher(h).isPresent())
                .orElseThrow(() -> new Unresolved("no jdtls install (plugins/org.eclipse.equinox.launcher_*.jar) "
                        + (homes.isEmpty() ? "and no bin/jdtls on the PATH" : "in " + homes + " or on the PATH")));
    }

    /** The Java feature version the install's JDT core bundle requires, or 0 when it does not say. */
    static int requiredJava(Path home) {
        Optional<Path> core = plugin(home, CORE);
        if (core.isEmpty()) return 0;
        try (JarFile jar = new JarFile(core.get().toFile())) {
            String ee = jar.getManifest().getMainAttributes().getValue("Bundle-RequiredExecutionEnvironment");
            Matcher m = REQUIRED.matcher(ee == null ? "" : ee);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static Java java(Lookup lookup, int required) throws Unresolved {
        List<Java> candidates = Stream.of(lookup.path("clustering.lsp.jdt.java", "CLUSTERING_LSP_JDT_JAVA"),
                lookup.variablePath("JAVA_HOME"), Optional.of(lookup.runningJava()))
                .flatMap(Optional::stream).map(p -> java(lookup, p)).flatMap(Optional::stream).distinct().toList();
        return candidates.stream().filter(j -> j.version() < 0 || j.version() >= required).findFirst()
                .orElseThrow(() -> new Unresolved("jdtls needs Java " + required + " or later; found "
                        + candidates.stream().map(j -> j.executable() + " (" + j.version() + ")").toList()
                        + "; set clustering.lsp.jdt.java to a newer JDK"));
    }

    /** A JDK directory or a java executable to the launcher and its version. */
    private static Optional<Java> java(Lookup lookup, Path given) {
        Path home = Files.isDirectory(given) ? given : realParent(given).map(Path::getParent).orElse(given);
        Optional<Path> executable = lookup.executableIn(home.resolve("bin"), "java");
        return executable.map(e -> new Java(e, version(home)));
    }

    private static int version(Path javaHome) {
        try {
            Matcher m = VERSION.matcher(Files.readString(javaHome.resolve("release"), StandardCharsets.UTF_8));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static Path configuration(Lookup lookup, Path home) throws Unresolved {
        String os = String.valueOf(lookup.properties().apply("os.name")).toLowerCase(Locale.ROOT);
        String arch = String.valueOf(lookup.properties().apply("os.arch")).toLowerCase(Locale.ROOT);
        String base = lookup.windows() ? "config_win" : os.contains("mac") ? "config_mac" : "config_linux";
        Path arm = home.resolve(base + "_arm");
        Path config = (arch.contains("aarch64") || arch.contains("arm")) && Files.isDirectory(arm) ? arm : home.resolve(base);
        if (!Files.isDirectory(config)) throw new Unresolved("jdtls install " + home + " has no " + config.getFileName());
        return config;
    }

    private static Path data(Lookup lookup) {
        return lookup.path("clustering.lsp.jdt.data", "CLUSTERING_LSP_JDT_DATA")
                .orElseGet(() -> Path.of(lookup.properties().apply("java.io.tmpdir"), "code-clustering-jdtls-data"));
    }

    static Optional<Path> launcher(Path home) {
        return plugin(home, LAUNCHER);
    }

    private static Optional<Path> plugin(Path home, Pattern name) {
        try (Stream<Path> list = Files.list(home.resolve("plugins"))) {
            return list.filter(p -> name.matcher(p.getFileName().toString()).matches()).sorted().reduce((a, b) -> b);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> realParent(Path file) {
        try {
            return Optional.ofNullable(file.toRealPath().getParent());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
