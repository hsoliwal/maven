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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Visual Studio facts without running vcvarsall: whether the Maven process already has a developer environment
 * (needed by nmake and a bare cl), and where vswhere finds the C++ build tools (CMake's Visual Studio generator and
 * clang-cl find them themselves).
 */
public final class MsvcLocator {
    private MsvcLocator() {}

    /** True inside a Visual Studio developer prompt. */
    public static boolean developerEnvironment(Map<String, String> environment) {
        return PlanContext.environment(environment).containsKey("VCINSTALLDIR");
    }

    /** The installation path vswhere reports for the latest instance with the x86/x64 C++ tools. */
    public static Optional<String> installation(Platform platform, Map<String, String> environment) {
        if (!platform.windows()) {
            return Optional.empty();
        }
        String root = PlanContext.environment(environment).getOrDefault("ProgramFiles(x86)", "C:/Program Files (x86)");
        Path vswhere = Path.of(root, "Microsoft Visual Studio", "Installer", "vswhere.exe");
        return Files.isRegularFile(vswhere) ? query(vswhere) : Optional.empty();
    }

    private static Optional<String> query(Path vswhere) {
        try {
            CapturedProcess.Result result = CapturedProcess.run(List.of(vswhere.toString(), "-latest", "-products", "*",
                    "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"),
                    vswhere.getParent(), Duration.ofSeconds(30));
            return result.exit() == 0 && !result.out().isBlank()
                    ? Optional.of(result.out().strip().replace('\\', '/')) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
