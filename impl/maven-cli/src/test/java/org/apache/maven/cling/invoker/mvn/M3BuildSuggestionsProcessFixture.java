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
package org.apache.maven.cling.invoker.mvn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Scripted OS-pipe fixture; this is not a Maven API, MCP client or native-model substitute. */
public final class M3BuildSuggestionsProcessFixture {
    private M3BuildSuggestionsProcessFixture() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("hold")) {
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("stall")) {
            Files.writeString(
                    Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("descendant")) {
            Path java = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            Process child = new ProcessBuilder(List.of(
                            java.toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            M3BuildSuggestionsProcessFixture.class.getName(),
                            "hold"))
                    .inheritIO()
                    .start();
            Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\n" + child.pid());
            Thread.sleep(60_000);
            return;
        }
        if (mode.equals("overflow-before-input")) {
            System.out.write(new byte[32_769]);
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        byte[] input = System.in.readAllBytes();
        switch (mode) {
            case "echo" -> System.out.write(input);
            case "unicode" -> System.out.write("Review the 🛠 diagnostic.".getBytes(StandardCharsets.UTF_8));
            case "exact" -> System.out.write("x".repeat(32_768).getBytes(StandardCharsets.UTF_8));
            case "malformed" -> System.out.write(new byte[] {(byte) 0xc3, 0x28});
            case "nul" -> System.out.write(new byte[] {'x', 0, 'y'});
            case "c1" -> System.out.write("unsafe\u009b31m".getBytes(StandardCharsets.UTF_8));
            case "blank" -> System.out.write(new byte[] {' ', '\n'});
            case "nonzero" -> {
                System.err.print("PRIVATE_PROVIDER_STDERR");
                System.out.print("UNACCEPTED_PROVIDER_STDOUT");
                System.exit(27);
            }
            default -> throw new IllegalArgumentException("unknown scripted fixture mode");
        }
    }
}
