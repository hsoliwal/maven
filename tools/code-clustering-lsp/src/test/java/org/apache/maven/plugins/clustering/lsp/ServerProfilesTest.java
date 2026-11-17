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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.maven.plugins.clustering.lsp.ServerSpec.Unresolved;

/**
 * Profiles without real servers: selection and aliases; the command order (system property, then environment
 * variable, then detection, with home directories before the PATH); skipping a profile whose server is missing with
 * one warning; explicit {@code clustering.lsp.servers} entries winning; timeouts; and detection of each kind of
 * install (native executable, npm package, virtual environment, LemMinX jar, JDT LS install with its Java) in fake
 * directories under the scratch directory given as the argument.
 */
public final class ServerProfilesTest {
    private static final Checks CHECKS = new Checks("server profiles");
    private static final boolean WINDOWS = Lookup.system().windows();

    private ServerProfilesTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/profiles-test").toAbsolutePath();
        Files.createDirectories(dir);
        selection();
        order(dir);
        configured(dir);
        timeouts();
        detection(dir);
        jdt(dir);
        CHECKS.finish();
    }

    private static void selection() {
        List<String> warnings = new ArrayList<>();
        CHECKS.check("aliases java, C, web, corrosion", ServerProfiles.names("java, C ,web,corrosion", warnings::add)
                .equals(List.of("jdt", "cdt", "wtp", "rust")) && warnings.isEmpty());
        CHECKS.check("all", ServerProfiles.names("ALL", warnings::add).equals(ServerProfiles.NAMES));
        CHECKS.check("aliases cpp, python, xml, html, css, json, rustdt; repeats once",
                ServerProfiles.names("cpp python xml html css json rustdt pydev", warnings::add)
                        .equals(List.of("cdt", "pydev", "wtp", "rust")) && warnings.isEmpty());
        CHECKS.check("unknown name warns once and is ignored", ServerProfiles.names("bogus,rust", warnings::add)
                .equals(List.of("rust")) && warnings.size() == 1 && warnings.get(0).contains("'bogus'"));
        CHECKS.check("empty setting selects nothing", ServerProfiles.names(" ", warnings::add).isEmpty());
        CHECKS.check("wtp has the XML, HTML, CSS and JSON servers", ServerProfiles.select("web", warnings::add).stream()
                .map(ServerSpec::id).toList().equals(List.of("wtp.xml", "wtp.html", "wtp.css", "wtp.json")));
    }

    /** property > environment > detection; home before PATH; the home property before the home variable. */
    private static void order(Path dir) throws Exception {
        Path bin = exe(dir.resolve("order/bin"), "clangd").getParent();
        Path home = exe(dir.resolve("order/llvm/bin"), "clangd").getParent().getParent();
        Path otherHome = exe(dir.resolve("order/llvm2"), "clangd").getParent();
        Map<String, String> props = new HashMap<>(Map.of("clustering.lsp.cdt.command",
                "\"C:/Program Files/LLVM/bin/clangd.exe\" --log=error"));
        Map<String, String> env = new HashMap<>(Map.of("CLUSTERING_LSP_CDT_COMMAND", "env-clangd --x", "PATH", bin.toString()));
        ServerSpec cdt = ServerProfiles.server("cdt");
        CHECKS.check("1. the system property wins, quoted", command(cdt, props, env)
                .equals(List.of("C:/Program Files/LLVM/bin/clangd.exe", "--log=error")));
        props.clear();
        CHECKS.check("2. then the environment variable", command(cdt, props, env).equals(List.of("env-clangd", "--x")));
        env.remove("CLUSTERING_LSP_CDT_COMMAND");
        CHECKS.check("3. then detection on the PATH", command(cdt, props, env).equals(List.of(exeName(bin, "clangd"))));
        env.put("CDT_HOME", home.toString());
        CHECKS.check("3a. a home directory (CDT_HOME, bin/) before the PATH", command(cdt, props, env)
                .equals(List.of(exeName(home.resolve("bin"), "clangd"))));
        props.put("clustering.lsp.cdt.home", otherHome.toString());
        CHECKS.check("3b. the home property before the home variable", command(cdt, props, env)
                .equals(List.of(exeName(otherHome, "clangd"))));
        props.clear();
        env.clear();
        CHECKS.check("4. nothing found: unresolved, saying where it looked", unresolved(cdt, props, env).contains("PATH"));
    }

    private static void configured(Path dir) throws Exception {
        Path bin = exe(dir.resolve("configured/bin"), "clangd").getParent();
        List<String> warnings = new ArrayList<>();
        Map<String, LspServer> table = LspConfiguration.configured(lookup(Map.of(LspConfiguration.PROFILES, "cdt,rust"),
                Map.of("PATH", bin.toString())), warnings::add);
        CHECKS.check("a missing server skips its profile with one warning " + warnings, warnings.size() == 1
                && warnings.get(0).contains("rust") && !table.containsKey("rs") && table.get("cpp").name().equals("cdt"));
        CHECKS.check("profile languageIds: h and c as c, hpp as cpp", table.get("h").languageId("h").equals("c")
                && table.get("hpp").languageId("hpp").equals("cpp"));
        warnings.clear();
        table = LspConfiguration.configured(lookup(Map.of(LspConfiguration.PROFILES, "cdt,rust", LspConfiguration.SERVERS,
                "cpp=my-clangd --x;rs=my-ra"), Map.of("PATH", bin.toString())), warnings::add);
        CHECKS.check("explicit entries win over profiles", table.get("cpp").command().equals(List.of("my-clangd", "--x"))
                && table.get("c").command().equals(List.of(exeName(bin, "clangd"))));
        CHECKS.check("a profile whose extensions are all explicit is not looked for (no warning) " + warnings,
                warnings.isEmpty() && table.get("rs").command().equals(List.of("my-ra")));
        CHECKS.check("no settings, no servers", LspConfiguration.configured(lookup(Map.of(), Map.of()), warnings::add).isEmpty());
    }

    private static void timeouts() throws Unresolved {
        Map<String, String> env = Map.of("CLUSTERING_LSP_CDT_COMMAND", "c", "CLUSTERING_LSP_JDT_COMMAND", "j");
        Lookup none = lookup(Map.of(), env);
        Lookup global = lookup(Map.of("clustering.lsp.timeoutSeconds", "30"), env);
        Lookup own = lookup(Map.of("clustering.lsp.timeoutSeconds", "30", "clustering.lsp.cdt.timeoutSeconds", "90"), env);
        CHECKS.check("timeouts: default 60 and 180 for jdt, then the global setting, then the server's own",
                timeout("cdt", none) == 60 && timeout("jdt", none) == 180 && timeout("jdt", global) == 30
                && timeout("cdt", own) == 90 && timeout("jdt", own) == 30);
    }

    private static void detection(Path dir) throws Exception {
        Path bin = exe(dir.resolve("detect/bin"), "node").getParent();
        String node = exeName(bin, "node");
        Path npm = file(dir.resolve("detect/npm/node_modules/vscode-langservers-extracted/bin/vscode-html-language-server"))
                .getParent().getParent().getParent().getParent();
        Path script = npm.resolve("node_modules/vscode-langservers-extracted/bin/vscode-html-language-server");
        CHECKS.check("wtp.html from the profile home WTP_HOME (an npm prefix), run with node from the PATH",
                command(ServerProfiles.server("wtp.html"), Map.of(), Map.of("PATH", bin.toString(), "WTP_HOME", npm.toString()))
                        .equals(List.of(node, script.toString(), "--stdio")));
        cssOnPath(dir, bin, node);
        Path jar = file(dir.resolve("detect/lemminx/org.eclipse.lemminx-0.31.2-uber.jar"));
        CHECKS.check("wtp.xml: the LemMinX uber jar, run with this JVM", command(ServerProfiles.server("wtp.xml"),
                Map.of("clustering.lsp.wtp.xml.home", jar.getParent().toString()), Map.of())
                .equals(List.of(Lookup.system().runningJava().toString(), "-jar", jar.toString())));
        Path venv = exe(dir.resolve("detect/venv").resolve(WINDOWS ? "Scripts" : "bin"), "pylsp").getParent().getParent();
        CHECKS.check("pydev: pylsp in a virtual environment", command(ServerProfiles.server("pydev"),
                Map.of("clustering.lsp.pydev.home", venv.toString()), Map.of())
                .equals(List.of(exeName(venv.resolve(WINDOWS ? "Scripts" : "bin"), "pylsp"))));
        Path pyright = file(dir.resolve("detect/npm/node_modules/pyright/langserver.index.js"));
        CHECKS.check("pydev: else pyright from an npm prefix", command(ServerProfiles.server("pydev"),
                Map.of("clustering.lsp.pydev.home", npm.toString()), Map.of("PATH", bin.toString()))
                .equals(List.of(node, pyright.toString(), "--stdio")));
        Path ra = exe(dir.resolve("detect/ra"), "rust-analyzer").getParent();
        CHECKS.check("rust: rust-analyzer in its home directory", command(ServerProfiles.server("rust"),
                Map.of("clustering.lsp.rust.home", ra.toString()), Map.of()).equals(List.of(exeName(ra, "rust-analyzer"))));
        CHECKS.check("npm server without node: unresolved", unresolved(ServerProfiles.server("wtp.html"), Map.of(),
                Map.of("WTP_HOME", npm.toString())).contains("node"));
    }

    /** The CSS server's launcher on the PATH: run directly, or on Windows the script beside the .cmd with node. */
    private static void cssOnPath(Path dir, Path nodeBin, String node) throws IOException, Unresolved {
        Path global = dir.resolve("detect/global");
        String name = "vscode-css-language-server";
        List<String> expected;
        if (WINDOWS) {
            file(global.resolve(name + ".cmd"));
            expected = List.of(node, file(global.resolve("node_modules/vscode-langservers-extracted/bin/" + name)).toString(), "--stdio");
        } else {
            expected = List.of(exeName(exe(global, name).getParent(), name), "--stdio");
        }
        String path = global + (WINDOWS ? ";" : ":") + nodeBin;
        CHECKS.check("wtp.css: the launcher on the PATH " + expected, command(ServerProfiles.server("wtp.css"), Map.of(),
                Map.of("PATH", path)).equals(expected));
    }

    private static void jdt(Path dir) throws Exception {
        Path install = jdtls(dir.resolve("jdt/jdtls-new"), "JavaSE-25");
        Path older = jdtls(dir.resolve("jdt/jdtls-old"), "JavaSE-17");
        Path jdk17 = jdk(dir.resolve("jdt/jdk17"), "17.0.19");
        Path jdk21 = jdk(dir.resolve("jdt/jdk21"), "21.0.9");
        Path jdk25 = jdk(dir.resolve("jdt/jdk25"), "25.0.4.1");
        CHECKS.check("required Java: the JDT core bundle's execution environment", JdtlsCommand.requiredJava(install) == 25
                && JdtlsCommand.requiredJava(older) == 17);
        Map<String, String> props = new HashMap<>(Map.of("clustering.lsp.jdt.home", install.toString(),
                "clustering.lsp.jdt.java", jdk21.toString(), "clustering.lsp.jdt.data", dir.resolve("jdt/data").toString(),
                "java.home", jdk17.toString()));
        Map<String, String> env = Map.of("JAVA_HOME", jdk25.toString());
        List<String> command = command(ServerProfiles.server("jdt"), props, env);
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String config = WINDOWS ? "config_win" : os.contains("mac") ? "config_mac" : "config_linux";
        CHECKS.check("jdt 1.63-like install: JAVA_HOME (25) chosen over the older clustering.lsp.jdt.java (21) " + command,
                command.get(0).equals(exeName(jdk25.resolve("bin"), "java")) && command.contains("-Djdk.xml.totalEntitySizeLimit=0")
                && command.get(command.indexOf("-jar") + 1).endsWith("org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar")
                && command.get(command.indexOf("-configuration") + 1).equals(install.resolve(config).toString())
                && command.get(command.indexOf("-data") + 1).equals(dir.resolve("jdt/data").toString()));
        props.put("clustering.lsp.jdt.home", older.toString());
        command = command(ServerProfiles.server("jdt"), props, env);
        CHECKS.check("jdt 1.61-like install: the first Java 21+ (clustering.lsp.jdt.java), no jdk.xml flags",
                command.get(0).equals(exeName(jdk21.resolve("bin"), "java")) && !command.contains("-Djdk.xml.totalEntitySizeLimit=0"));
        props.put("clustering.lsp.jdt.home", install.toString());
        CHECKS.check("jdt: no Java new enough is unresolved", unresolved(ServerProfiles.server("jdt"), props, Map.of())
                .contains("needs Java 25"));
        props.remove("clustering.lsp.jdt.home");
        file(older.resolve("bin").resolve(WINDOWS ? "jdtls.bat" : "jdtls"));
        command = command(ServerProfiles.server("jdt"), props, Map.of("PATH", older.resolve("bin").toString()));
        CHECKS.check("jdt: the install whose bin/jdtls is on the PATH", command.get(command.indexOf("-jar") + 1)
                .startsWith(older.toRealPath().toString()));
        CHECKS.check("jdt: no install is unresolved", unresolved(ServerProfiles.server("jdt"), props, Map.of())
                .contains("no jdtls install"));
    }

    private static Path jdtls(Path home, String executionEnvironment) throws IOException {
        file(home.resolve("plugins/org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar"));
        file(home.resolve("plugins/org.eclipse.equinox.launcher.win32.win32.x86_64_1.3.200.v20260913-1737.jar"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-RequiredExecutionEnvironment", executionEnvironment);
        try (OutputStream out = Files.newOutputStream(home.resolve("plugins/org.eclipse.jdt.core_3.48.0.v20261007-1246.jar"));
                JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        for (String config : List.of("config_win", "config_linux", "config_mac")) Files.createDirectories(home.resolve(config));
        return home;
    }

    private static Path jdk(Path home, String version) throws IOException {
        exe(home.resolve("bin"), "java");
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"test\"\nJAVA_VERSION=\"" + version + "\"\n", StandardCharsets.UTF_8);
        return home;
    }

    private static List<String> command(ServerSpec spec, Map<String, String> props, Map<String, String> env) throws Unresolved {
        return spec.command(lookup(props, env));
    }

    private static String unresolved(ServerSpec spec, Map<String, String> props, Map<String, String> env) {
        try {
            return "resolved " + spec.command(lookup(props, env));
        } catch (Unresolved e) {
            System.err.println("  (" + spec.id() + " unresolved: " + e.getMessage() + ")");
            return e.getMessage();
        }
    }

    private static long timeout(String id, Lookup lookup) throws Unresolved {
        Duration timeout = ServerProfiles.server(id).resolve(lookup).timeout();
        return timeout.getSeconds();
    }

    /** Only the given settings: real system properties are used for the JVM's own keys, never for clustering.*. */
    static Lookup lookup(Map<String, String> props, Map<String, String> env) {
        return new Lookup(k -> props.containsKey(k) || k.startsWith("clustering.") ? props.get(k) : System.getProperty(k),
                env::get);
    }

    /** A runnable file {@code dir/name} ({@code name.exe} on Windows); returns its path. */
    private static Path exe(Path dir, String name) throws IOException {
        Path file = file(dir.resolve(WINDOWS ? name + ".exe" : name));
        if (!file.toFile().setExecutable(true)) throw new IOException("cannot make " + file + " executable");
        return file;
    }

    private static String exeName(Path dir, String name) {
        return dir.resolve(WINDOWS ? name + ".exe" : name).toAbsolutePath().toString();
    }

    private static Path file(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file, "", StandardCharsets.UTF_8);
        return file;
    }
}
