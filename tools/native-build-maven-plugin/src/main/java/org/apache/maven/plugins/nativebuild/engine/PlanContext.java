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

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything a {@link StepPlanner} needs; planners read files only to decide (makefile targets, presets), never
 * write or run anything.
 *
 * @param detection the detected build system
 * @param layout the project-relative directories
 * @param options the user settings
 * @param tools the resolved tools
 * @param platform the host platform
 * @param environment the environment of the Maven process (read-only)
 * @param fingerprintInput whether configure declares the toolchain fingerprint as an input (goals do; a root
 *     manifest does not, because its fingerprint would be missing after {@code mvn clean})
 */
public record PlanContext(
        Detection detection,
        Layout layout,
        NativeOptions options,
        ToolSet tools,
        Platform platform,
        Map<String, String> environment,
        boolean fingerprintInput) {
    /** The fingerprint file, project-relative. */
    public static final String FINGERPRINT = "toolchain.properties";

    /** Copies the environment. */
    public PlanContext {
        environment = environment(environment);
    }

    /** A read-only copy whose keys compare ignoring case, as Windows environment names do. */
    public static Map<String, String> environment(Map<String, String> raw) {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(raw);
        return Collections.unmodifiableMap(copy);
    }

    /** The build system. */
    public BuildSystem system() {
        return detection.system();
    }

    /** The resolved path of a tool, or its bare name. */
    public String tool(String name) {
        return tools.path(name);
    }
}
