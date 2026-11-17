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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
 *       {@code lemminx} binary.</li>
 * </ul>
 */
final class ServerDetection {
    private static final Pattern LEMMINX_JAR = Pattern.compile("org\\.eclipse\\.lemminx.*uber\\.jar");
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
