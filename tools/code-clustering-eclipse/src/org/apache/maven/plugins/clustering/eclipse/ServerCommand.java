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
package org.apache.maven.plugins.clustering.eclipse;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The command that starts the code-clustering language server in a JVM of its own. It uses no Eclipse types, so it
 * can be checked without a running Eclipse.
 */
final class ServerCommand {
    static final String MAIN_CLASS = "org.apache.maven.plugins.clustering.lsp.ClusteringServerMain";
    /** System property: the java executable for the server (default: the running JVM's). */
    static final String JAVA = "clustering.eclipse.java";
    /** System property: extra JVM options for the server, separated by whitespace. */
    static final String VM_ARGS = "clustering.eclipse.vmargs";
    /** System properties passed on to the server as {@code -D} options when they are set. */
    static final List<String> FORWARDED = List.of("clustering.minTokens", "clustering.lsp.servers",
            "clustering.lsp.timeoutSeconds");

    private ServerCommand() { }

    /** {@code java [vmargs] [-Dforwarded=value...] -cp <jars> ClusteringServerMain}. */
    static List<String> of(Properties settings, Path javaHome, List<Path> jars) {
        List<String> command = new ArrayList<>();
        command.add(java(settings, javaHome));
        command.addAll(words(settings.getProperty(VM_ARGS, "")));
        FORWARDED.stream().filter(key -> settings.getProperty(key) != null)
                .map(key -> "-D" + key + "=" + settings.getProperty(key)).forEach(command::add);
        command.add("-cp");
        command.add(classPath(jars));
        command.add(MAIN_CLASS);
        return List.copyOf(command);
    }

    /** The {@code clustering.eclipse.java} property, else {@code bin/java} (or {@code java.exe}) under the home. */
    static String java(Properties settings, Path javaHome) {
        String given = settings.getProperty(JAVA, "").strip();
        if (!given.isEmpty()) return given;
        String name = File.separatorChar == '\\' ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(name).toString();
    }

    /** The jars in name order, joined with the platform's path separator. */
    static String classPath(List<Path> jars) {
        return jars.stream().sorted().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.strip().split("\\s+")).filter(word -> !word.isEmpty()).toList();
    }
}
