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
        checks.finish();
    }

    private static void accepted(Checks checks) {
        checks.that("phases and full goal coordinates", ArgumentGuard.goals(List.of("clean", "verify",
                "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom", "compiler:compile@default"))
                .size() == 4);
        checks.that("projects by path and by :artifactId",
                ArgumentGuard.projects(List.of("a", "sub/b", ":c", "fix:d")).size() == 4);
        Map<String, String> properties = new TreeMap<>(Map.of("skipTests", "true", "test", "fix.ATest#adds+x"));
        checks.that("properties sorted by key", ArgumentGuard.properties(properties)
                .equals(List.of("-DskipTests=true", "-Dtest=fix.ATest#adds+x")));
        checks.that("test selections", ArgumentGuard.selection("fix.*Test#ad*,BTest").equals("fix.*Test#ad*,BTest"));
        checks.that("run numbers", ArgumentGuard.run("latest").equals("latest")
                && ArgumentGuard.run("12").equals("12"));
        checks.that("event types", ArgumentGuard.eventType("session-end").equals("session-end"));
        checks.that("plain path", ArgumentGuard.path(Path.of("runs", "1", "build.log")).endsWith("build.log"));
    }

    private static void refused(Checks checks) {
        checks.that("empty goal list", refusedArgument(() -> ArgumentGuard.goals(List.of())));
        checks.that("option as goal", refusedArgument(() -> ArgumentGuard.goals(List.of("-X"))));
        checks.that("option as project", refusedArgument(() -> ArgumentGuard.projects(List.of("-am"))));
        checks.that("option as selection", refusedArgument(() -> ArgumentGuard.selection("-Dfoo=1")));
        checks.that("extension class path property", refusedArgument(
                () -> ArgumentGuard.properties(Map.of("maven.ext.class.path", "x.jar"))));
        checks.that("server-owned agent directory", refusedArgument(
                () -> ArgumentGuard.properties(Map.of("maven.agent.dir", "x"))));
        checks.that("property name with '='", refusedArgument(() -> ArgumentGuard.properties(Map.of("a=b", "c"))));
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
            checks.that(label + " in value", refusedArgument(
                    () -> ArgumentGuard.properties(Map.of("k", "v" + character))));
            checks.that(label + " in selection", refusedArgument(() -> ArgumentGuard.selection("T" + character)));
        });
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
