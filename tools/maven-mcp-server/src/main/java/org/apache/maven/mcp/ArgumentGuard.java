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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks every value a client sends before it becomes a Maven argument. Each value must match a narrow character
 * set and must not start with "-", so a client can never add an option of its own. The sets leave out every
 * character that cmd.exe, a POSIX shell or PowerShell treats specially (white space, quotes, {@code % ! ^ & | < >
 * ( ) $ ; `}), which keeps the arguments inert even though Windows runs {@code mvn.cmd} through cmd.exe. Properties
 * the server sets itself, and properties that redirect which configuration Maven reads or where it connects
 * ({@link #REDIRECTING}: local repository, settings, toolchains, extensions, JVM and network properties, the build
 * hook's switches), are refused.
 *
 * <p>When the launcher is a {@code .cmd} script ({@code mvn.cmd}, {@code mvnw.cmd}), cmd.exe also splits the script's
 * parameters at {@code =} and {@code ,}, and {@code mvn.cmd} scans those pieces for {@code -f}/{@code --file} to find
 * the project's {@code .mvn} directory. Property values and test selections then may hold neither character and may
 * not start with "-" (the {@code cmd} flag of {@link #properties} and {@link #selection}); {@link CmdArguments}
 * checks the final argument vector once more before the launch.
 */
final class ArgumentGuard {

    static final int MAX_GOALS = 20;
    static final int MAX_ITEMS = 50;
    static final int MAX_TOKEN = 200;

    /** Properties that only the server sets. */
    static final Set<String> SERVER_KEYS = Set.of(
            "maven.ext.class.path",
            "maven.agent",
            "maven.agent.dir",
            "maven.events.file",
            "maven.events.log",
            "maven.events.stdout",
            "maven.home",
            "maven.multiModuleProjectDirectory",
            "classworlds.conf",
            "library.jansi.path",
            "style.color");

    /**
     * Prefixes of properties that redirect Maven: the equivalents of {@code -s}, {@code -gs}, {@code -t},
     * {@code -Dmaven.repo.local}, the resolver, the JVM ({@code user.home} moves {@code ~/.m2}), TLS and proxies, and
     * the build hook ({@code chromellm.}, {@code m3.smart.}: programs it starts).
     */
    static final List<String> REDIRECTING = List.of("maven.repo.", "maven.settings", "maven.user.",
            "maven.installation.", "maven.project.", "maven.resolver.", "maven.config", "maven.mainClass", "maven.boot",
            "settings.", "org.apache.maven.", "classworlds.", "aether.", "user.", "java.", "javax.", "jdk.", "sun.",
            "http.", "https.", "ftp.", "socksProxy", "chromellm.", "m3.smart.");

    private static final Pattern GOAL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._@-]*");
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9:.][A-Za-z0-9:._/-]*");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9:._/,#*@=+-]*");
    private static final Pattern SELECTION = Pattern.compile("[A-Za-z0-9*][A-Za-z0-9._#*,+/-]*");
    /** A value through a .cmd launcher: no '=' or ',' (cmd.exe parameter delimiters) and no leading '-'. */
    private static final Pattern CMD_VALUE = Pattern.compile("(?:[A-Za-z0-9:._/#*@+][A-Za-z0-9:._/#*@+-]*)?");
    /** A selection through a .cmd launcher: no ','; join methods with '+' or use a pattern such as {@code *Test}. */
    private static final Pattern CMD_SELECTION = Pattern.compile("[A-Za-z0-9*][A-Za-z0-9._#*+/-]*");
    private static final Pattern RUN = Pattern.compile("latest|[1-9][0-9]{0,8}");
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z-]{0,40}");
    private static final Pattern UNSAFE_IN_PATH = Pattern.compile("[%!^&|<>\"`\\p{Cntrl}]");

    private ArgumentGuard() {}

    static List<String> goals(List<String> goals) {
        if (goals.isEmpty() || goals.size() > MAX_GOALS) {
            throw new IllegalArgumentException("goals must hold 1 to " + MAX_GOALS + " phases or goals");
        }
        return goals.stream().map(goal -> token("goal", goal, GOAL)).collect(Collectors.toList());
    }

    /** A plugin goal named by prefix or coordinates, such as {@code compiler:compile}. */
    static String goal(String goal) {
        return token("goal", goal, GOAL);
    }

    static List<String> projects(List<String> projects) {
        if (projects.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("projects may hold at most " + MAX_ITEMS + " entries");
        }
        return projects.stream().map(project -> token("project", project, PROJECT)).collect(Collectors.toList());
    }

    static String project(String project) {
        return token("project", project, PROJECT);
    }

    /**
     * The {@code -Dkey=value} arguments for the client's properties, in key order; {@code cmd} when the launcher is a
     * {@code .cmd} script, which narrows the values (see the class comment).
     */
    static List<String> properties(Map<String, String> properties, boolean cmd) {
        if (properties.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("properties may hold at most " + MAX_ITEMS + " entries");
        }
        String kind = cmd ? "property value (through mvn.cmd: no '=', ',' or leading '-')" : "property value";
        return properties.entrySet().stream()
                .map(entry -> "-D" + key(entry.getKey()) + "=" + token(kind, entry.getValue(), cmd ? CMD_VALUE : VALUE))
                .collect(Collectors.toList());
    }

    /**
     * A Surefire or Failsafe test selection, such as {@code BTest#adds} or {@code fix.*Test}; through a {@code .cmd}
     * launcher without ',' (several methods as {@code Class#a+b}, several classes as a pattern).
     */
    static String selection(String selection, boolean cmd) {
        return cmd ? token("test selection (through mvn.cmd: no ',')", selection, CMD_SELECTION)
                : token("test selection", selection, SELECTION);
    }

    /** "latest" or a run number. */
    static String run(String run) {
        return token("run", run, RUN);
    }

    /** An event type of the build event stream, such as {@code diagnostic} or {@code mojo-end}. */
    static String eventType(String type) {
        return token("event type", type, EVENT_TYPE);
    }

    /** A path the server itself puts on the command line; refused when it holds a character cmd.exe expands. */
    static String path(Path path) {
        String text = path.toString();
        if (UNSAFE_IN_PATH.matcher(text).find()) {
            throw new IllegalStateException("path holds a character that is unsafe on a command line: " + text);
        }
        return text;
    }

    private static String key(String key) {
        String checked = token("property name", key, KEY);
        if (SERVER_KEYS.contains(checked)) {
            throw new IllegalArgumentException("property " + checked + " is set by the server and cannot be passed");
        }
        if (REDIRECTING.stream().anyMatch(checked::startsWith)) {
            throw new IllegalArgumentException("property " + checked + " redirects which configuration Maven reads or"
                    + " where it connects, and cannot be passed");
        }
        return checked;
    }

    private static String token(String kind, String value, Pattern allowed) {
        if (value.length() > MAX_TOKEN || !allowed.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    kind + " is not allowed: \"" + printable(value) + "\" (allowed: " + allowed.pattern() + ")");
        }
        return value;
    }

    private static String printable(String value) {
        return CompactJson.cap(value.replaceAll("\\p{Cntrl}", "?"), 80);
    }
}
