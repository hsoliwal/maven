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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Command quoting: double and single quotes, quoted parts joined to their neighbours, empty arguments, backslashes,
 * unclosed quotes, {@code ;} inside quotes, the servers setting, and no shell. The last check starts a real process
 * (this JVM's java, whose path may hold spaces) from a quoted setting and reads back the arguments it received.
 */
public final class CommandLineTest {
    private static final Checks CHECKS = new Checks("command line");

    private CommandLineTest() { }

    public static void main(String[] args) throws Exception {
        CHECKS.check("double quotes keep spaces", CommandLine.split("\"C:/Program Files/LLVM/bin/clangd.exe\" --log=error")
                .equals(List.of("C:/Program Files/LLVM/bin/clangd.exe", "--log=error")));
        CHECKS.check("single quotes keep spaces and double quotes", CommandLine.split("node 'J:/my servers/x.js' 'say \"hi\"'")
                .equals(List.of("node", "J:/my servers/x.js", "say \"hi\"")));
        CHECKS.check("double quotes keep a single quote", CommandLine.split("\"O'Brien\"").equals(List.of("O'Brien")));
        CHECKS.check("a quoted part joins its neighbours", CommandLine.split("-data=\"J:/my ws\" x\"y z\"w")
                .equals(List.of("-data=J:/my ws", "xy zw")));
        CHECKS.check("empty quotes are empty arguments", CommandLine.split("a \"\" '' b").equals(List.of("a", "", "", "b")));
        CHECKS.check("backslashes and tabs", CommandLine.split(" C:\\tools\\clangd.exe  --x\t-y ")
                .equals(List.of("C:\\tools\\clangd.exe", "--x", "-y")));
        CHECKS.check("blank is no argument", CommandLine.split("   ").isEmpty());
        CHECKS.check("no shell: metacharacters are plain arguments", CommandLine.split("clangd && echo $HOME | x > y")
                .equals(List.of("clangd", "&&", "echo", "$HOME", "|", "x", ">", "y")));
        CHECKS.check("an unclosed quote is an error", unclosed("\"C:/Program Files/x") && unclosed("a 'b"));
        CHECKS.check("entries split on ; outside quotes", CommandLine.entries("cpp=\"a;b\";py='c;d' e;;")
                .equals(List.of("cpp=\"a;b\"", "py='c;d' e", "", "")));
        servers();
        process();
        CHECKS.finish();
    }

    private static void servers() {
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> parsed = LspConfiguration.parse("c,CPP=\"C:/Program Files/LLVM/bin/clangd.exe\" --log=error;"
                + " rs = 'J:/rust tools/rust-analyzer.exe' ;java=\"J:/broken", warnings::add);
        List<String> clangd = List.of("C:/Program Files/LLVM/bin/clangd.exe", "--log=error");
        CHECKS.check("servers setting with quoted commands " + parsed, parsed.equals(Map.of("c", clangd, "cpp", clangd,
                "rs", List.of("J:/rust tools/rust-analyzer.exe"))));
        CHECKS.check("an entry with an unclosed quote is skipped with one warning " + warnings,
                warnings.size() == 1 && warnings.get(0).contains("unclosed"));
    }

    /** A quoted servers entry runs as a process that receives exactly the arguments written, with no shell between. */
    private static void process() throws IOException, InterruptedException {
        Path java = Lookup.system().runningJava();
        Path classes = Path.of(URI.create(ArgsEcho.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        String setting = "x=\"" + java + "\" -cp \"" + classes + "\" " + ArgsEcho.class.getName()
                + " \"a b\" '$HOME' \"&&\" 'x|y' \"semi;colon\" ''";
        List<String> command = LspConfiguration.parse(setting, m -> { }).get("x");
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        CHECKS.check("a quoted command runs without a shell (java at " + java + "): " + out.replace("\n", " "),
                exited && out.equals("[a b]\n[$HOME]\n[&&]\n[x|y]\n[semi;colon]\n[]\n"));
    }

    private static boolean unclosed(String command) {
        try {
            CommandLine.split(command);
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage().contains("unclosed");
        }
    }
}
