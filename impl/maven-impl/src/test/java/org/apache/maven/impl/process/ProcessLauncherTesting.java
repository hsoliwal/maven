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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.services.OsService;
import org.apache.maven.api.services.ProcessLaunchRequest;
import org.apache.maven.api.services.ProcessLaunchRequest.ProcessLaunchRequestBuilder;
import org.apache.maven.api.toolchain.ToolchainModel;

import static org.mockito.Mockito.mock;

/** Shared fixtures of the process launcher tests; public because maven-shared-utils calls the toolchain reflectively. */
public final class ProcessLauncherTesting {

    static final ProtoSession SESSION = mock(ProtoSession.class);

    private ProcessLauncherTesting() {}

    /** An {@link OsService} that reports Windows or a Unix family. */
    record StubOs(boolean isWindows) implements OsService {
        @Override
        public String name() {
            return isWindows ? "windows 11" : "linux";
        }

        @Override
        public String arch() {
            return "amd64";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public String family() {
            return isWindows ? "windows" : "unix";
        }
    }

    /** A toolchain whose {@code findTool} answers from a map, as {@code JavaToolchain} does from its home. */
    public static final class StubToolchain implements Toolchain {
        private final Map<String, String> tools;

        StubToolchain(Map<String, String> tools) {
            this.tools = tools;
        }

        @Override
        public String getType() {
            return "jdk";
        }

        @Override
        public ToolchainModel getModel() {
            return ToolchainModel.newInstance();
        }

        @Override
        public String findTool(String toolName) {
            return tools.get(toolName);
        }

        @Override
        public boolean matchesRequirements(Map<String, String> requirements) {
            return true;
        }
    }

    static ProcessLaunchRequestBuilder request() {
        return ProcessLaunchRequest.builder().session(SESSION);
    }

    /** A request running {@link ProcessLauncherFixture} in a mode on the java of the running JVM. */
    static ProcessLaunchRequestBuilder fixture(String mode, String... arguments) {
        return request()
                .tool("java")
                .arguments("-cp", testClasses().toString(), ProcessLauncherFixture.class.getName(), mode)
                .arguments(arguments);
    }

    static Path testClasses() {
        try {
            return Path.of(ProcessLauncherFixture.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A thread-safe line collector. */
    static List<String> lines() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /** Writes a parity table under {@code target/process-launcher-parity}. */
    static void writeTable(String name, List<String> rows) {
        Path file = Path.of("target", "process-launcher-parity", name);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, rows, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Shows control characters of a parity cell. */
    static String cell(Object value) {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
