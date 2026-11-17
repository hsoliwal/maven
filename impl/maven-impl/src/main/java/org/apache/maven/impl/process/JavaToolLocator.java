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
package org.apache.maven.impl.process;

import java.io.File;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.apache.maven.api.Toolchain;

/**
 * Finds the executable of a Java tool.
 * <p>
 * Adapted from maven-shared-utils 3.5.0 {@code AbstractJavaTool.findJavaToolExecutable} (Apache-2.0), in the same
 * order: {@link Toolchain#findTool(String)}; {@code ../bin}, {@code bin} and {@code ../sh} under {@code java.home};
 * {@code bin} and {@code sh} under {@code JDK_HOME}, then {@code JAVA_HOME}; else the bare command name.
 * The toolchain is called directly instead of through reflection.
 */
final class JavaToolLocator {

    private JavaToolLocator() {}

    /**
     * Finds a Java tool.
     *
     * @param tool the platform independent tool name
     * @param toolchain the toolchain asked first, may be empty
     * @param windows whether the command gets the {@code .exe} suffix
     * @param javaHome the {@code java.home} of the running JVM, may be null
     * @param environment the environment lookup
     * @return the absolute path of the tool, or the command name to look up on {@code PATH}
     */
    static String locate(
            String tool,
            Optional<Toolchain> toolchain,
            boolean windows,
            String javaHome,
            UnaryOperator<String> environment) {
        String command = tool + (windows ? ".exe" : "");
        return toolchain
                .map(chain -> chain.findTool(tool))
                .or(() -> find(command, javaHome, "../bin", "bin", "../sh"))
                .or(() -> find(command, environment.apply("JDK_HOME"), "bin", "sh"))
                .or(() -> find(command, environment.apply("JAVA_HOME"), "bin", "sh"))
                .orElse(command);
    }

    private static Optional<String> find(String command, String home, String... subDirectories) {
        if (home == null || home.isEmpty()) {
            return Optional.empty();
        }
        return Stream.of(subDirectories)
                .map(subDirectory -> new File(new File(home, subDirectory), command))
                .filter(File::isFile)
                .map(File::getAbsolutePath)
                .findFirst();
    }
}
