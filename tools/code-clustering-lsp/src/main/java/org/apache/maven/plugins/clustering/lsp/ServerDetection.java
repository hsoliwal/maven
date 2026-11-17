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
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.lsp.ServerSpec.Unresolved;

/**
 * Finds server programs in home directories and on the {@code PATH}, without a shell:
 * <ul>
 *   <li>a native executable ({@code clangd}, {@code rust-analyzer}, {@code sourcekit-lsp}) in {@code <home>/bin},
 *       {@code <home>} or on the PATH;</li>
 *   <li>a program that its language's own tool installs outside the PATH, looked for after the PATH: {@code gopls}
 *       from {@code go install} in {@code GOBIN}, else {@code <entry>/bin} of each {@code GOPATH} entry, else
 *       {@code ~/go/bin}; {@code csharp-ls} from {@code dotnet tool install --global} in
 *       {@code $DOTNET_CLI_HOME/.dotnet/tools}, else {@code ~/.dotnet/tools};</li>
 *   <li>an npm package's server script ({@code pyright}, {@code vscode-langservers-extracted}) under
 *       {@code <home>/node_modules} or {@code <home>/lib/node_modules} of an npm prefix, run with {@code node} from the
 *       PATH; or its launcher on the PATH: run directly elsewhere, and on Windows, where the launcher is a
 *       {@code .cmd} file that needs a shell, the script next to it is run with {@code node} instead;</li>
 *   <li>pylsp in a virtual environment ({@code <home>/Scripts/pylsp.exe} or {@code <home>/bin/pylsp});</li>
 *   <li>the LemMinX uber jar ({@code org.eclipse.lemminx-*-uber.jar}, run with this JVM's java) or a native
 *       {@code lemminx} binary;</li>
 *   <li>a server jar run with this JVM's java: groovy-language-server's {@code groovy-language-server*-all.jar} (the
 *       newest by version number), the Che4z COBOL Language Support {@code server.jar};</li>
 *   <li>a launcher with its arguments ({@code phpactor language-server}, {@code elp server}): a native executable in
 *       {@code <home>/bin}, {@code <home>} or on the PATH, and on Windows else a {@code .cmd} or {@code .bat} launcher
 *       there, run with {@code cmd.exe /c};</li>
 *   <li>alternatives in order: kotlin-language-server, else {@code kotlin-lsp --stdio}; phpactor, else intelephense
 *       (an npm package); ELP, else erlang_ls.</li>
 * </ul>
 */
final class ServerDetection {
    private static final Pattern LEMMINX_JAR = Pattern.compile("org\\.eclipse\\.lemminx.*uber\\.jar");
    private static final Pattern GROOVY_JAR = Pattern.compile("groovy-language-server.*-all\\.jar");
    /** The empty boundaries between a run of digits and other text. */
    private static final Pattern DIGIT_RUN_EDGE = Pattern.compile("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final List<String> WINDOWS_LAUNCHERS = List.of(".cmd", ".bat");
    private static final String STDIO = "--stdio";

    private ServerDetection() { }

    /** A native server program, run with no arguments. */
    static List<String> executable(Lookup lookup, List<Path> homes, String name) throws Unresolved {
        return executable(lookup, homes, name, List.of());
    }

    /** A native server program from the home directories, the PATH, then the directories its installer uses. */
    static List<String> executable(Lookup lookup, List<Path> homes, String name, List<Path> installed) throws Unresolved {
        return inHomes(lookup, homes, name).or(() -> lookup.onPath(name)).or(() -> inHomes(lookup, installed, name))
                .map(p -> List.of(p.toString())).orElseThrow(() -> missing(name + " is not on the PATH",
                        Stream.concat(homes.stream(), installed.stream()).toList()));
    }

    /** gopls: the home directories, the PATH, then where {@code go install} puts it (GOBIN, GOPATH, ~/go). */
    static List<String> gopls(Lookup lookup, List<Path> homes) throws Unresolved {
        List<Path> gopath = lookup.variablePaths("GOPATH");
        List<Path> installed = lookup.variablePath("GOBIN").map(List::of).orElseGet(() -> !gopath.isEmpty() ? gopath
                : lookup.userHome().map(h -> List.of(h.resolve("go"))).orElse(List.of()));
        return executable(lookup, homes, "gopls", installed);
    }

    /** csharp-ls: the home directories, the PATH, then the .NET global tools directory. */
    static List<String> csharpLs(Lookup lookup, List<Path> homes) throws Unresolved {
        List<Path> installed = lookup.variablePath("DOTNET_CLI_HOME").or(lookup::userHome)
                .map(h -> List.of(h.resolve(".dotnet").resolve("tools"))).orElse(List.of());
        return executable(lookup, homes, "csharp-ls", installed);
    }

    /** An npm package's server: {@code bin} is the launcher name, {@code script} the file inside the package. */
    static List<String> npm(Lookup lookup, List<Path> homes, String bin, String pkg, String script) throws Unresolved {
        Optional<Path> inPrefix = homes.stream().flatMap(h -> Stream.of(h.resolve("node_modules"),
                h.resolve("lib").resolve("node_modules"))).map(m -> m.resolve(pkg).resolve(script))
                .filter(Files::isRegularFile).findFirst();
        if (inPrefix.isPresent()) return node(lookup, inPrefix.get());
        if (!lookup.windows()) {
            return lookup.onPath(bin).map(p -> List.of(p.toString(), STDIO))
                    .orElseThrow(() -> missing(bin + " is not on the PATH", homes));
        }
        Optional<Path> beside = lookup.fileOnPath(bin + ".cmd")
                .map(cmd -> cmd.resolveSibling("node_modules").resolve(pkg).resolve(script)).filter(Files::isRegularFile);
        if (beside.isPresent()) return node(lookup, beside.get());
        throw missing(bin + ".cmd with node_modules/" + pkg + " beside it is not on the PATH", homes);
    }

    /** pylsp from a virtual environment or the PATH, else pyright from an npm prefix or the PATH. */
    static List<String> python(Lookup lookup, List<Path> homes) throws Unresolved {
        Optional<Path> pylsp = homes.stream().map(h -> h.resolve(lookup.windows() ? "Scripts" : "bin"))
                .map(dir -> lookup.executableIn(dir, "pylsp")).flatMap(Optional::stream).findFirst()
                .or(() -> lookup.onPath("pylsp"));
        if (pylsp.isPresent()) return List.of(pylsp.get().toString());
        try {
            return npm(lookup, homes, "pyright-langserver", "pyright", "langserver.index.js");
        } catch (Unresolved e) {
            throw missing("neither pylsp nor pyright-langserver is on the PATH", homes);
        }
    }

    /** LemMinX: the uber jar in a home directory (run with this JVM), else a native lemminx binary. */
    static List<String> lemminx(Lookup lookup, List<Path> homes) throws Unresolved {
        Optional<Path> jar = homes.stream().flatMap(ServerDetection::files)
                .filter(f -> LEMMINX_JAR.matcher(f.getFileName().toString()).matches()).sorted().reduce((a, b) -> b);
        if (jar.isPresent()) return List.of(lookup.runningJava().toString(), "-jar", jar.get().toString());
        return executable(lookup, homes, "lemminx");
    }

    /** groovy-language-server: its newest all-in-one jar in a home directory (run with this JVM), else its launcher. */
    static List<String> groovy(Lookup lookup, List<Path> homes) throws Unresolved {
        Optional<Path> jar = homes.stream().flatMap(h -> Stream.of(h, h.resolve("build").resolve("libs"), h.resolve("lib")))
                .flatMap(ServerDetection::files).filter(f -> GROOVY_JAR.matcher(f.getFileName().toString()).matches())
                .max(Comparator.comparing(f -> f.getFileName().toString(), ServerDetection::compareVersions));
        if (jar.isPresent()) return List.of(lookup.runningJava().toString(), "-jar", jar.get().toString());
        return launcher(lookup, homes, "groovy-language-server", List.of());
    }

    /**
     * File names in version order: runs of digits compare as numbers, so {@code 0.10} comes after {@code 0.9}; the
     * text between them compares as text; names equal so far compare as text.
     */
    static int compareVersions(String a, String b) {
        String[] x = DIGIT_RUN_EDGE.split(a);
        String[] y = DIGIT_RUN_EDGE.split(b);
        return IntStream.range(0, Math.min(x.length, y.length)).map(i -> comparePart(x[i], y[i])).filter(c -> c != 0)
                .findFirst().orElseGet(() -> a.compareTo(b));
    }

    private static int comparePart(String a, String b) {
        boolean numbers = DIGITS.matcher(a).matches() && DIGITS.matcher(b).matches();
        return numbers ? new BigInteger(a).compareTo(new BigInteger(b)) : a.compareTo(b);
    }

    /** kotlin-language-server (the launcher in the distribution's {@code bin}), else JetBrains' kotlin-lsp. */
    static List<String> kotlin(Lookup lookup, List<Path> homes) throws Unresolved {
        return first(lookup, homes, "neither kotlin-language-server nor kotlin-lsp is on the PATH", List.of(
                (l, h) -> launcher(l, h, "kotlin-language-server", List.of()),
                (l, h) -> launcher(l, h, "kotlin-lsp", List.of(STDIO))));
    }

    /** phpactor (MIT), else intelephense (closed source, free tier) from an npm prefix or the PATH. */
    static List<String> php(Lookup lookup, List<Path> homes) throws Unresolved {
        return first(lookup, homes, "neither phpactor nor intelephense is on the PATH", List.of(
                (l, h) -> launcher(l, h, "phpactor", List.of("language-server")),
                (l, h) -> npm(l, h, "intelephense", "intelephense", "lib/intelephense.js")));
    }

    /** The Che4z COBOL Language Support {@code server.jar} in a home directory (run with this JVM), else its launcher. */
    static List<String> cobol(Lookup lookup, List<Path> homes) throws Unresolved {
        Optional<Path> jar = homes.stream().flatMap(h -> Stream.of(h.resolve("extension").resolve("server").resolve("jar"),
                h.resolve("server").resolve("jar"), h.resolve("jar"), h)).map(dir -> dir.resolve("server.jar"))
                .filter(Files::isRegularFile).findFirst();
        if (jar.isPresent()) return List.of(lookup.runningJava().toString(), "-jar", jar.get().toString());
        return launcher(lookup, homes, "cobol-language-support", List.of());
    }

    /** ELP ({@code elp server}), else erlang_ls. */
    static List<String> erlang(Lookup lookup, List<Path> homes) throws Unresolved {
        return first(lookup, homes, "neither elp nor erlang_ls is on the PATH", List.of(
                (l, h) -> launcher(l, h, "elp", List.of("server")),
                (l, h) -> launcher(l, h, "erlang_ls", List.of())));
    }

    /**
     * A program and its arguments: a native executable in the home directories or on the PATH; on Windows, else a
     * {@code .cmd} or {@code .bat} launcher in the home directories or on the PATH, run with {@code cmd.exe /c}.
     */
    static List<String> launcher(Lookup lookup, List<Path> homes, String name, List<String> arguments) throws Unresolved {
        List<String> program = inHomes(lookup, homes, name).or(() -> lookup.onPath(name)).map(p -> List.of(p.toString()))
                .or(() -> windowsLauncher(lookup, homes, name))
                .orElseThrow(() -> missing(name + " is not on the PATH", homes));
        return Stream.concat(program.stream(), arguments.stream()).toList();
    }

    /** The command of the first alternative that is found; else unresolved with {@code none}. */
    static List<String> first(Lookup lookup, List<Path> homes, String none, List<ServerSpec.Detector> alternatives)
            throws Unresolved {
        for (ServerSpec.Detector alternative : alternatives) {
            try {
                return alternative.detect(lookup, homes);
            } catch (Unresolved e) {
                // not installed: try the next alternative
            }
        }
        throw missing(none, homes);
    }

    private static Optional<List<String>> windowsLauncher(Lookup lookup, List<Path> homes, String name) {
        if (!lookup.windows()) return Optional.empty();
        Stream<Path> inHomes = homes.stream().flatMap(h -> Stream.of(h.resolve("bin"), h))
                .flatMap(dir -> WINDOWS_LAUNCHERS.stream().map(ext -> dir.resolve(name + ext))).filter(Files::isRegularFile);
        Stream<Path> onPath = WINDOWS_LAUNCHERS.stream().map(ext -> lookup.fileOnPath(name + ext)).flatMap(Optional::stream);
        return Stream.concat(inHomes, onPath).findFirst().map(p -> List.of("cmd.exe", "/c", p.toAbsolutePath().toString()));
    }

    private static List<String> node(Lookup lookup, Path script) throws Unresolved {
        Path node = lookup.onPath("node").orElseThrow(() -> new Unresolved("node is not on the PATH (for " + script + ")"));
        return List.of(node.toString(), script.toString(), STDIO);
    }

    private static Optional<Path> inHomes(Lookup lookup, List<Path> homes, String name) {
        return homes.stream().flatMap(h -> Stream.of(h.resolve("bin"), h)).map(dir -> lookup.executableIn(dir, name))
                .flatMap(Optional::stream).findFirst();
    }

    private static Stream<Path> files(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static Unresolved missing(String what, List<Path> homes) {
        return new Unresolved(homes.isEmpty() ? what : what + ", and not found in " + homes);
    }
}
