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
package org.apache.maven.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Accepted and refused client values for every kind of Maven argument the server builds. */
public final class ArgumentGuardTest {

    private static final List<String> SHELL_CHARACTERS = List.of(
            " ", "\t", "\"", "'", "%", "!", "^", "&", "|", "<", ">", "(", ")", "$", ";", "`", "\n", "\r", "\\");

    private ArgumentGuardTest() {}

    public static void main(String[] args) {
        Checks checks = new Checks("ArgumentGuard");
        accepted(checks);
        refused(checks);
        shellCharacters(checks);
        windowsLauncher(checks);
        cmdBackstop(checks);
        checks.finish();
    }

    private static void accepted(Checks checks) {
        checks.that("phases and full goal coordinates", ArgumentGuard.goals(List.of("clean", "verify",
                "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom", "compiler:compile@default"))
                .size() == 4);
        checks.that("projects by path and by :artifactId",
                ArgumentGuard.projects(List.of("a", "sub/b", ":c", "fix:d")).size() == 4);
        Map<String, String> properties = new TreeMap<>(Map.of("skipTests", "true", "test", "fix.ATest#adds+x"));
        checks.that("properties sorted by key", ArgumentGuard.properties(properties, false)
                .equals(List.of("-DskipTests=true", "-Dtest=fix.ATest#adds+x")));
        checks.that("test selections", ArgumentGuard.selection("fix.*Test#ad*,BTest", false)
                .equals("fix.*Test#ad*,BTest"));
        checks.that("run numbers", ArgumentGuard.run("latest").equals("latest")
                && ArgumentGuard.run("12").equals("12"));
        checks.that("event types", ArgumentGuard.eventType("session-end").equals("session-end"));
        checks.that("plain path", ArgumentGuard.path(Path.of("runs", "1", "build.log")).endsWith("build.log"));
    }

    private static void refused(Checks checks) {
        checks.that("empty goal list", refusedArgument(() -> ArgumentGuard.goals(List.of())));
        checks.that("option as goal", refusedArgument(() -> ArgumentGuard.goals(List.of("-X"))));
        checks.that("option as project", refusedArgument(() -> ArgumentGuard.projects(List.of("-am"))));
        checks.that("option as selection", refusedArgument(() -> ArgumentGuard.selection("-Dfoo=1", false)));
        checks.that("extension class path property", refusedArgument(
                () -> ArgumentGuard.properties(Map.of("maven.ext.class.path", "x.jar"), false)));
        checks.that("server-owned agent directory", refusedArgument(
                () -> ArgumentGuard.properties(Map.of("maven.agent.dir", "x"), false)));
        checks.that("property name with '='", refusedArgument(
                () -> ArgumentGuard.properties(Map.of("a=b", "c"), false)));
        checks.that("run zero", refusedArgument(() -> ArgumentGuard.run("0")));
        checks.that("run path", refusedArgument(() -> ArgumentGuard.run("../1")));
        checks.that("too long goal", refusedArgument(() -> ArgumentGuard.goals(List.of("a".repeat(201)))));
        checks.that("21 goals", refusedArgument(() -> ArgumentGuard.goals(java.util.Collections.nCopies(21, "a"))));
        checks.that("server path with %", refusedState(() -> ArgumentGuard.path(Path.of("a%PATH%b"))));
    }

    /** Every character a shell treats specially is refused in each kind of value. */
    private static void shellCharacters(Checks checks) {
        SHELL_CHARACTERS.forEach(character -> {
            String label = "character U+" + Integer.toHexString(character.charAt(0));
            checks.that(label + " in goal", refusedArgument(() -> ArgumentGuard.goals(List.of("clean" + character))));
            checks.that(label + " in project", refusedArgument(() -> ArgumentGuard.project("a" + character)));
            List.of(false, true).forEach(cmd -> {
                checks.that(label + " in value, cmd " + cmd, refusedArgument(
                        () -> ArgumentGuard.properties(Map.of("k", "v" + character), cmd)));
                checks.that(label + " in selection, cmd " + cmd,
                        refusedArgument(() -> ArgumentGuard.selection("T" + character, cmd)));
            });
        });
    }

    /**
     * Through mvn.cmd or mvnw.cmd, cmd.exe splits the script's parameters at '=' and ',' too, and mvn.cmd's own
     * {@code -f}/{@code --file} scan sees the pieces: values and selections may hold neither, nor start with '-'.
     */
    private static void windowsLauncher(Checks checks) {
        List.of("-f=C:/evil", "x,-f,C:/evil", "a=b", "a,b", "-f", "--file").forEach(value -> checks.that(
                "cmd value " + value, refusedArgument(() -> ArgumentGuard.properties(Map.of("a", value), true))));
        List.of("A,-f,../x", "ATest,BTest", "A=-f").forEach(selection -> checks.that("cmd selection " + selection,
                refusedArgument(() -> ArgumentGuard.selection(selection, true))));
        checks.that("cmd value accepted", ArgumentGuard.properties(Map.of("a", "x.y/z#1+2"), true)
                .equals(List.of("-Da=x.y/z#1+2")));
        checks.that("cmd empty value accepted", ArgumentGuard.properties(Map.of("a", ""), true)
                .equals(List.of("-Da=")));
        checks.that("cmd selection accepted", ArgumentGuard.selection("fix.*Test#ad*+x", true)
                .equals("fix.*Test#ad*+x"));
        checks.that("comma and '=' still accepted through bin/mvn", ArgumentGuard.properties(
                Map.of("a", "x,-y=z"), false).equals(List.of("-Da=x,-y=z")));
    }

    /** The launcher's own check of the final argument vector of a .cmd launcher, after the guard. */
    private static void cmdBackstop(Checks checks) {
        List.of("-Da=-f=C:/evil", "-Dtest=A,-f,../x", "-Dx=1;--file;y", "%PATH%", "-Dx=a!b!", "x^y").forEach(arg ->
                checks.that("cmd backstop refuses " + arg, refusedState(() -> CmdArguments.check(List.of("-B", arg)))));
        checks.that("cmd backstop accepts server arguments", CmdArguments.check(List.of("-B", "-V", "-ntp",
                "-Dstyle.color=never", "-l", "C:\\Users\\a b\\runs\\1\\build.log", "-Dmaven.agent.dir=C:\\r\\x-y\\1",
                "-pl", "a,sub/b,:c", "-Da=x.y", "-Dtest=fix.*Test#ad*+x", "verify")).size() == 12);
    }

    private static boolean refusedArgument(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean refusedState(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
