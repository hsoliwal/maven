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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * toolchains.xml native entries, and the resolver order: tools parameter, toolchains, provisioned, PATH; compilers
 * never from PATH; ctest beside a configured cmake; a fingerprint that is stable and changes with the toolchain.
 */
final class ToolchainsContract {
    private static final Platform HOST = Platform.current();

    private ToolchainsContract() {}

    static void run(Checks c) throws Exception {
        Path root = c.dir("toolchains", Map.of());
        Path explicit = tool(root.resolve("explicit"), "cmake");
        Path chained = tool(root.resolve("chain/bin"), "cmake");
        tool(root.resolve("chain/bin"), "ctest");
        Path onPath = tool(root.resolve("path"), "cmake");
        tool(root.resolve("path"), "gcc");
        tool(root.resolve("make-home/bin"), "make");
        Path xml = root.resolve("toolchains.xml");
        Files.writeString(xml, toolchainsXml(chained, root.resolve("make-home")));
        List<ToolchainsReader.Entry> entries = ToolchainsReader.parse(xml);
        reader(c, entries, chained, root);
        resolver(c, entries, explicit, chained, onPath, root);
        fingerprint(c);
    }

    private static void reader(Checks c, List<ToolchainsReader.Entry> entries, Path chained, Path root)
            throws Exception {
        c.equal("toolchains entries", List.of("jdk", "cmake", "make"), entries.stream().map(ToolchainsReader.Entry::type)
                .toList());
        c.equal("cmake executable by provides", chained, ToolchainsReader.executable(entries, "cmake",
                Map.of("version", "3.28"), HOST).orElseThrow());
        c.check("unmatched requirement gives nothing", ToolchainsReader.executable(entries, "cmake", Map.of("version",
                "9"), HOST).isEmpty());
        c.equal("installationPath/bin", root.resolve("make-home/bin").resolve(exe("make")), ToolchainsReader.executable(
                entries, "make", Map.of(), HOST).orElseThrow());
        c.equal("serialized configuration DOM", Map.of("executable", "/x/cmake"),
                ToolchainsReader.configuration("<configuration>\n  <executable>/x/cmake</executable>\n</configuration>"));
    }

    private static void resolver(Checks c, List<ToolchainsReader.Entry> entries, Path explicit, Path chained,
            Path onPath, Path root) {
        Map<String, String> env = Map.of("PATH", onPath.getParent().toString() + File.pathSeparator
                + root.resolve("nothing"), "PATHEXT", ".COM;.EXE");
        ToolSet.Tool fromTools = new ToolResolver(Map.of("cmake", explicit.toString()), entries, Map.of(), Map.of(), HOST,
                env).resolve("cmake").orElseThrow();
        c.equal("tools parameter first", List.of(WindowsPaths.mixed(explicit), ToolSet.Origin.TOOLS),
                List.of(fromTools.path(), fromTools.origin()));
        ToolResolver standard = new ToolResolver(Map.of(), entries, Map.of(), Map.of(), HOST, env);
        c.equal("toolchains second", ToolSet.Origin.TOOLCHAINS, standard.resolve("cmake").orElseThrow().origin());
        c.equal("ctest beside the toolchain cmake", WindowsPaths.mixed(chained.resolveSibling(exe("ctest"))),
                standard.resolve("ctest").orElseThrow().path());
        pathLookup(c, onPath, env);
    }

    private static void pathLookup(Checks c, Path onPath, Map<String, String> env) {
        ToolResolver plain = new ToolResolver(Map.of(), List.of(), Map.of(), Map.of(), HOST, env);
        c.equal("PATH last", List.of(WindowsPaths.mixed(onPath), ToolSet.Origin.PATH), List.of(
                plain.resolve("cmake").orElseThrow().path(), plain.resolve("cmake").orElseThrow().origin()));
        c.check("compilers are never taken from PATH", plain.resolve("cc").isEmpty());
        ToolSet.Tool gcc = new ToolResolver(Map.of("cc", "gcc"), List.of(), Map.of(), Map.of(), HOST, env)
                .resolve("cc").orElseThrow();
        c.equal("configured compiler name looked up on PATH", WindowsPaths.mixed(onPath.resolveSibling(exe("gcc"))),
                gcc.path());
        c.check("unknown tool is absent", plain.resolve("ninja-not-installed").isEmpty());
    }

    private static void fingerprint(Checks c) {
        ToolSet tools = ToolSet.of(List.of(new ToolSet.Tool("cmake", "/opt/cmake", ToolSet.Origin.PATH),
                new ToolSet.Tool("sh", "/bin/sh", ToolSet.Origin.PATH)));
        String one = ToolchainFingerprint.render(PlanFixtures.LINUX, tools, p -> "cmake version 3.28.3",
                Map.of("CFLAGS", "-O2", "HOME", "/home/u"));
        c.equal("fingerprint text", "# native toolchain fingerprint (configure input)\nplatform=linux-x86_64\n"
                + "tool.cmake.path=/opt/cmake\ntool.cmake.version=cmake version 3.28.3\ntool.sh.path=/bin/sh\n"
                + "tool.sh.version=-\nenv.CFLAGS=-O2\n", one);
        c.check("fingerprint changes with the tool version", !one.equals(ToolchainFingerprint.render(PlanFixtures.LINUX,
                tools, p -> "cmake version 4.3.1", Map.of("CFLAGS", "-O2"))));
    }

    private static Path tool(Path folder, String name) throws Exception {
        Files.createDirectories(folder);
        Path file = folder.resolve(exe(name));
        Files.writeString(file, "#!/bin/sh\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static String exe(String name) {
        return HOST.windows() ? name + ".exe" : name;
    }

    private static String toolchainsXml(Path cmake, Path makeHome) {
        return "<?xml version=\"1.0\"?>\n<toolchains>\n  <toolchain><type>jdk</type><provides><version>21</version>"
                + "</provides><configuration><jdkHome>/jdk</jdkHome></configuration></toolchain>\n"
                + "  <toolchain><type>cmake</type><provides><version>3.28</version></provides>"
                + "<configuration><executable>" + cmake + "</executable></configuration></toolchain>\n"
                + "  <toolchain><type>make</type><configuration><installationPath>" + makeHome
                + "</installationPath></configuration></toolchain>\n</toolchains>\n";
    }
}
