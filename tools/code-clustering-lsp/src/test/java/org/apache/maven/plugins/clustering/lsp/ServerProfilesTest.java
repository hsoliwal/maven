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
    private static final List<String> LANGUAGE_PLUGINS = List.of("groovy", "kotlin", "php", "cobol", "erlang");

    private ServerProfilesTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/profiles-test").toAbsolutePath();
        Files.createDirectories(dir);
        selection();
        languagePluginSelection();
        order(dir);
        configured(dir);
        explicitLanguageIds();
        timeouts();
        detection(dir);
        languageServerDetection(dir);
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
        warnings.clear();
        newProfileSelection(warnings);
        newProfileLanguageIds();
    }

    /** The go, typescript, csharp and swift profiles: aliases, their place in all, one server each. */
    private static void newProfileSelection(List<String> warnings) {
        CHECKS.check("aliases ts, js, javascript, cs; go and swift by name", ServerProfiles.names("swift,ts js JavaScript CS go",
                warnings::add).equals(List.of("go", "typescript", "csharp", "swift")) && warnings.isEmpty());
        CHECKS.check("all has fourteen profiles: go to swift, then the Eclipse language plug-in ones",
                ServerProfiles.NAMES.size() == 14 && ServerProfiles.NAMES.subList(5, 9).equals(List.of("go", "typescript",
                "csharp", "swift")) && ServerProfiles.NAMES.subList(9, 14).equals(LANGUAGE_PLUGINS));
        CHECKS.check("go, typescript, csharp and swift: one server each, with the profile's id",
                ServerProfiles.select("go,typescript,csharp,swift", warnings::add).stream().map(ServerSpec::id).toList()
                        .equals(List.of("go", "typescript", "csharp", "swift")) && warnings.isEmpty());
    }

    /** The languageIds of the go, typescript, csharp and swift profiles. */
    private static void newProfileLanguageIds() {
        Map<String, String> ts = ServerProfiles.server("typescript").languageIds();
        CHECKS.check("typescript languageIds: ts, mts, cts; tsx as typescriptreact; js, mjs, cjs; jsx as javascriptreact",
                ts.get("ts").equals("typescript") && ts.get("mts").equals("typescript") && ts.get("cts").equals("typescript")
                && ts.get("tsx").equals("typescriptreact") && ts.get("js").equals("javascript") && ts.get("mjs")
                .equals("javascript") && ts.get("cjs").equals("javascript") && ts.get("jsx").equals("javascriptreact")
                && ts.size() == 8);
        CHECKS.check("go, csharp, swift languageIds", ServerProfiles.server("go").languageIds().equals(Map.of("go", "go"))
                && ServerProfiles.server("csharp").languageIds().equals(Map.of("cs", "csharp", "csx", "csharp"))
                && ServerProfiles.server("swift").languageIds().equals(Map.of("swift", "swift")));
    }

    /** The profiles of Groovy-Eclipse, Kotlin for Eclipse, PDT, Che4z COBOL and Erlide: aliases and languageIds. */
    private static void languagePluginSelection() {
        List<String> warnings = new ArrayList<>();
        CHECKS.check("aliases groovy-eclipse, kt, pdt, che4z, erlide; lemminx is wtp", ServerProfiles.names(
                "erlide che4z,PDT kt groovy-eclipse lemminx", warnings::add).equals(List.of("wtp", "groovy", "kotlin", "php",
                "cobol", "erlang")) && warnings.isEmpty());
        CHECKS.check("one server each, with the profile's id", ServerProfiles.select(String.join(",", LANGUAGE_PLUGINS),
                warnings::add).stream().map(ServerSpec::id).toList().equals(LANGUAGE_PLUGINS) && warnings.isEmpty());
        CHECKS.check("groovy, kotlin, php, cobol, erlang languageIds", ServerProfiles.server("groovy").languageIds()
                .equals(Map.of("groovy", "groovy", "gvy", "groovy", "gy", "groovy", "gsh", "groovy"))
                && ServerProfiles.server("kotlin").languageIds().equals(Map.of("kt", "kotlin", "kts", "kotlin"))
                && ServerProfiles.server("php").languageIds().equals(Map.of("php", "php", "phtml", "php"))
                && ServerProfiles.server("cobol").languageIds().equals(Map.of("cbl", "cobol", "cob", "cobol", "cpy", "cobol",
                "cobol", "cobol")) && ServerProfiles.server("erlang").languageIds().equals(Map.of("erl", "erlang", "hrl",
                "erlang")));
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

    /** Explicit {@code clustering.lsp.servers} entries take the languageIds of the new profiles' extensions. */
    private static void explicitLanguageIds() {
        Map<String, LspServer> table = LspConfiguration.explicit(
                LspConfiguration.parse("swift=sk;mjs,cts=tls;csx=cls", m -> { }), Duration.ofSeconds(60));
        CHECKS.check("explicit entries: languageIds of swift, mjs, cts, csx", table.get("swift").languageId("swift")
                .equals("swift") && table.get("mjs").languageId("mjs").equals("javascript") && table.get("cts")
                .languageId("cts").equals("typescript") && table.get("csx").languageId("csx").equals("csharp"));
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

    /** The servers of the go to swift profiles and of the Eclipse language plug-in profiles. */
    private static void languageServerDetection(Path dir) throws Exception {
        Path nodeBin = exe(dir.resolve("detect/bin"), "node").getParent();
        String node = exeName(nodeBin, "node");
        goHomeAndPath(dir);
        goInstallDirs(dir);
        csharp(dir);
        typescript(dir, nodeBin, node);
        swift(dir);
        groovy(dir);
        cobol(dir);
        kotlin(dir);
        phpAndErlang(dir, nodeBin, node);
    }

    /** gopls: a home directory (GO_HOME, bin/) first, then the PATH before the go install directories. */
    private static void goHomeAndPath(Path dir) throws Exception {
        ServerSpec go = ServerProfiles.server("go");
        Map<String, String> noHome = Map.of("user.home", Files.createDirectories(dir.resolve("go/home-empty")).toString());
        Path home = exe(dir.resolve("go/gohome/bin"), "gopls").getParent().getParent();
        Path gobin = exe(dir.resolve("go/gobin"), "gopls").getParent();
        Path pathBin = exe(dir.resolve("go/path"), "gopls").getParent();
        CHECKS.check("go: gopls in GO_HOME/bin first", command(go, noHome, Map.of("GO_HOME", home.toString(), "PATH",
                pathBin.toString())).equals(List.of(exeName(home.resolve("bin"), "gopls"))));
        CHECKS.check("go: the PATH before the go install directories", command(go, noHome, Map.of("PATH", pathBin.toString(),
                "GOBIN", gobin.toString())).equals(List.of(exeName(pathBin, "gopls"))));
    }

    /** gopls in the go install directories: GOBIN, else each GOPATH entry's bin, else ~/go/bin. */
    private static void goInstallDirs(Path dir) throws Exception {
        ServerSpec go = ServerProfiles.server("go");
        String empty = Files.createDirectories(dir.resolve("go/home-empty")).toString();
        Map<String, String> noHome = Map.of("user.home", empty);
        Path gobin = exe(dir.resolve("go/gobin"), "gopls").getParent();
        Path gopath2 = exe(dir.resolve("go/gopath2/bin"), "gopls").getParent().getParent();
        Path userGo = exe(dir.resolve("go/home-go/go/bin"), "gopls").getParent().getParent().getParent();
        String gopath = Files.createDirectories(dir.resolve("go/gopath1")) + (WINDOWS ? ";" : ":") + gopath2;
        CHECKS.check("go: GOBIN before GOPATH", command(go, noHome, Map.of("GOBIN", gobin.toString(), "GOPATH", gopath))
                .equals(List.of(exeName(gobin, "gopls"))));
        CHECKS.check("go: else the bin directory of any GOPATH entry", command(go, noHome, Map.of("GOPATH", gopath))
                .equals(List.of(exeName(gopath2.resolve("bin"), "gopls"))));
        CHECKS.check("go: else ~/go/bin", command(go, Map.of("user.home", userGo.toString()), Map.of())
                .equals(List.of(exeName(userGo.resolve("go/bin"), "gopls"))));
        String missing = unresolved(go, noHome, Map.of());
        CHECKS.check("go: nothing found names ~/go", missing.contains("gopls") && missing.contains(Path.of(empty, "go").toString()));
    }

    /** csharp-ls: a home directory (a --tool-path directory), the PATH, then the .NET global tools directory. */
    private static void csharp(Path dir) throws Exception {
        ServerSpec cs = ServerProfiles.server("csharp");
        String empty = Files.createDirectories(dir.resolve("cs/home-empty")).toString();
        Path toolPath = exe(dir.resolve("cs/tool-path"), "csharp-ls").getParent();
        Path user = exe(dir.resolve("cs/home/.dotnet/tools"), "csharp-ls").getParent().getParent().getParent();
        Path cli = exe(dir.resolve("cs/cli/.dotnet/tools"), "csharp-ls").getParent().getParent().getParent();
        CHECKS.check("csharp: CSHARP_HOME, a dotnet tool --tool-path directory", command(cs, Map.of("user.home", empty),
                Map.of("CSHARP_HOME", toolPath.toString())).equals(List.of(exeName(toolPath, "csharp-ls"))));
        CHECKS.check("csharp: ~/.dotnet/tools", command(cs, Map.of("user.home", user.toString()), Map.of())
                .equals(List.of(exeName(user.resolve(".dotnet/tools"), "csharp-ls"))));
        CHECKS.check("csharp: DOTNET_CLI_HOME before user.home", command(cs, Map.of("user.home", user.toString()),
                Map.of("DOTNET_CLI_HOME", cli.toString())).equals(List.of(exeName(cli.resolve(".dotnet/tools"), "csharp-ls"))));
        CHECKS.check("csharp: nothing found names the tools directory", unresolved(cs, Map.of("user.home", empty), Map.of())
                .contains(Path.of(empty, ".dotnet", "tools").toString()));
    }

    /** typescript-language-server from an npm prefix, or its launcher on the PATH. */
    private static void typescript(Path dir, Path nodeBin, String node) throws Exception {
        ServerSpec ts = ServerProfiles.server("typescript");
        Path prefix = dir.resolve("ts/npm");
        Path cli = file(prefix.resolve("node_modules/typescript-language-server/lib/cli.mjs"));
        CHECKS.check("typescript: TYPESCRIPT_HOME (an npm prefix), run with node from the PATH", command(ts, Map.of(),
                Map.of("TYPESCRIPT_HOME", prefix.toString(), "PATH", nodeBin.toString()))
                .equals(List.of(node, cli.toString(), "--stdio")));
        typescriptLauncher(dir, nodeBin, node);
    }

    /** typescript-language-server's launcher on the PATH: run directly, or on Windows the script beside the .cmd. */
    private static void typescriptLauncher(Path dir, Path nodeBin, String node) throws Exception {
        ServerSpec ts = ServerProfiles.server("typescript");
        Path global = dir.resolve("ts/global");
        String name = "typescript-language-server";
        List<String> expected;
        if (WINDOWS) {
            file(global.resolve(name + ".cmd"));
            expected = List.of(node, file(global.resolve("node_modules/" + name + "/lib/cli.mjs")).toString(), "--stdio");
        } else {
            expected = List.of(exeName(exe(global, name).getParent(), name), "--stdio");
        }
        CHECKS.check("typescript: the launcher on the PATH " + expected, command(ts, Map.of(), Map.of("PATH", global
                + (WINDOWS ? ";" : ":") + nodeBin)).equals(expected));
    }

    /** sourcekit-lsp from a toolchain's usr directory, or on the PATH. */
    private static void swift(Path dir) throws Exception {
        ServerSpec swift = ServerProfiles.server("swift");
        Path usr = exe(dir.resolve("swift/toolchain/usr/bin"), "sourcekit-lsp").getParent().getParent();
        Path onPath = exe(dir.resolve("swift/path"), "sourcekit-lsp").getParent();
        CHECKS.check("swift: SWIFT_HOME (a toolchain's usr directory) before the PATH", command(swift, Map.of(), Map.of(
                "SWIFT_HOME", usr.toString(), "PATH", onPath.toString())).equals(List.of(exeName(usr.resolve("bin"),
                "sourcekit-lsp"))));
        CHECKS.check("swift: sourcekit-lsp on the PATH", command(swift, Map.of(), Map.of("PATH", onPath.toString()))
                .equals(List.of(exeName(onPath, "sourcekit-lsp"))));
    }

    /** groovy-language-server: the newest -all jar in a home, run with this JVM, before the launcher on the PATH. */
    private static void groovy(Path dir) throws Exception {
        String java = Lookup.system().runningJava().toString();
        Path libs = dir.resolve("groovy/home/build/libs");
        file(libs.resolve("groovy-language-server-0.9-all.jar"));
        Path newest = file(libs.resolve("groovy-language-server-1.0-all.jar"));
        file(libs.resolve("groovy-language-server-1.1.jar"));
        Path onPath = exe(dir.resolve("groovy/path"), "groovy-language-server").getParent();
        CHECKS.check("groovy: the newest -all jar in GROOVY_HOME/build/libs, before the PATH", command(ServerProfiles.server(
                "groovy"), Map.of(), Map.of("GROOVY_HOME", dir.resolve("groovy/home").toString(), "PATH", onPath.toString()))
                .equals(List.of(java, "-jar", newest.toString())));
        CHECKS.check("groovy: else groovy-language-server on the PATH", command(ServerProfiles.server("groovy"), Map.of(),
                Map.of("PATH", onPath.toString())).equals(List.of(exeName(onPath, "groovy-language-server"))));
    }

    /** The Che4z COBOL Language Support server.jar in a home, run with this JVM; else the launcher. */
    private static void cobol(Path dir) throws Exception {
        Path jar = file(dir.resolve("cobol/vsix/extension/server/jar/server.jar"));
        file(dir.resolve("cobol/vsix/server.jar"));
        CHECKS.check("cobol: an unpacked VSIX's extension/server/jar/server.jar first", command(ServerProfiles.server(
                "cobol"), Map.of("clustering.lsp.cobol.home", dir.resolve("cobol/vsix").toString()), Map.of())
                .equals(List.of(Lookup.system().runningJava().toString(), "-jar", jar.toString())));
        Path launcher = exe(dir.resolve("cobol/path"), "cobol-language-support").getParent();
        CHECKS.check("cobol: else cobol-language-support on the PATH", command(ServerProfiles.server("cobol"), Map.of(),
                Map.of("PATH", launcher.toString())).equals(List.of(exeName(launcher, "cobol-language-support"))));
        CHECKS.check("cobol: nothing found names the launcher", unresolved(ServerProfiles.server("cobol"), Map.of(),
                Map.of()).contains("cobol-language-support"));
    }

    /** kotlin-language-server before kotlin-lsp; on Windows the distribution's .bat launcher through cmd.exe /c. */
    private static void kotlin(Path dir) throws Exception {
        ServerSpec kotlin = ServerProfiles.server("kotlin");
        Path lsp = exe(dir.resolve("kotlin/lsp"), "kotlin-lsp").getParent();
        Path kls = exe(dir.resolve("kotlin/kls"), "kotlin-language-server").getParent();
        String path = lsp + (WINDOWS ? ";" : ":") + kls;
        CHECKS.check("kotlin: kotlin-language-server first, though kotlin-lsp comes first on the PATH", command(kotlin,
                Map.of(), Map.of("PATH", path)).equals(List.of(exeName(kls, "kotlin-language-server"))));
        CHECKS.check("kotlin: else kotlin-lsp --stdio", command(kotlin, Map.of(), Map.of("PATH", lsp.toString()))
                .equals(List.of(exeName(lsp, "kotlin-lsp"), "--stdio")));
        CHECKS.check("kotlin: nothing found names both", unresolved(kotlin, Map.of(), Map.of()).contains("neither "
                + "kotlin-language-server nor kotlin-lsp"));
        kotlinLauncher(dir, lsp);
    }

    /** The distribution's kotlin-language-server.bat: through cmd.exe /c on Windows, never run elsewhere. */
    private static void kotlinLauncher(Path dir, Path lsp) throws Exception {
        ServerSpec kotlin = ServerProfiles.server("kotlin");
        Path server = dir.resolve("kotlin/server");
        Path bat = file(server.resolve("bin/kotlin-language-server.bat"));
        List<String> expected = WINDOWS ? List.of("cmd.exe", "/c", bat.toAbsolutePath().toString())
                : List.of(exeName(lsp, "kotlin-lsp"), "--stdio");
        CHECKS.check("kotlin: KOTLIN_HOME/bin/kotlin-language-server.bat " + (WINDOWS ? "through cmd.exe /c"
                : "is not run outside Windows"), command(kotlin, Map.of(), Map.of("KOTLIN_HOME", server.toString(), "PATH",
                lsp.toString())).equals(expected));
    }

    /** phpactor before intelephense (an npm package); elp before erlang_ls; each with its own arguments. */
    private static void phpAndErlang(Path dir, Path nodeBin, String node) throws Exception {
        Path npm = dir.resolve("php/npm");
        Path script = file(npm.resolve("node_modules/intelephense/lib/intelephense.js"));
        Path phpactor = exe(dir.resolve("php/phpactor"), "phpactor").getParent();
        Map<String, String> env = Map.of("PHP_HOME", npm.toString(), "PATH", nodeBin + (WINDOWS ? ";" : ":") + phpactor);
        CHECKS.check("php: phpactor language-server first", command(ServerProfiles.server("php"), Map.of(), env)
                .equals(List.of(exeName(phpactor, "phpactor"), "language-server")));
        CHECKS.check("php: else intelephense from PHP_HOME (an npm prefix), run with node", command(ServerProfiles.server(
                "php"), Map.of(), Map.of("PHP_HOME", npm.toString(), "PATH", nodeBin.toString()))
                .equals(List.of(node, script.toString(), "--stdio")));
        Path elp = exe(dir.resolve("erlang/elp"), "elp").getParent();
        Path els = exe(dir.resolve("erlang/els"), "erlang_ls").getParent();
        CHECKS.check("erlang: elp server first", command(ServerProfiles.server("erlang"), Map.of(), Map.of("PATH",
                els + (WINDOWS ? ";" : ":") + elp)).equals(List.of(exeName(elp, "elp"), "server")));
        CHECKS.check("erlang: else erlang_ls", command(ServerProfiles.server("erlang"), Map.of(), Map.of("PATH",
                els.toString())).equals(List.of(exeName(els, "erlang_ls"))));
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
