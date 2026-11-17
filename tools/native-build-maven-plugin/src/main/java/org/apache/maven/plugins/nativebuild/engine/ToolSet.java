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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The resolved tools of one build, by logical name (cmake, ctest, make, ninja, meson, sh, cc, cxx, ...). */
public final class ToolSet {
    /** Where a tool path came from. */
    public enum Origin {
        /** The plugin's {@code tools} parameter. */
        TOOLS,
        /** A toolchains.xml entry. */
        TOOLCHAINS,
        /** A CMake release provisioned through the local repository. */
        PROVISIONED,
        /** Found on PATH. */
        PATH
    }

    /**
     * One resolved tool.
     *
     * @param name logical name
     * @param path absolute path in the mixed spelling
     * @param origin where it came from
     */
    public record Tool(String name, String path, Origin origin) {}

    private final Map<String, Tool> tools;

    private ToolSet(Map<String, Tool> tools) {
        this.tools = tools;
    }

    /** A set of resolved tools; a later tool of the same name replaces an earlier one. */
    public static ToolSet of(Collection<Tool> resolved) {
        Map<String, Tool> map = new TreeMap<>();
        resolved.forEach(tool -> map.put(tool.name(), tool));
        return new ToolSet(map);
    }

    /** The resolved path, or the bare name when the tool was not found (the start then fails with that name). */
    public String path(String name) {
        Tool tool = tools.get(name);
        return tool == null ? name : tool.path();
    }

    /** True when the tool was resolved. */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** The path of a tool the user configured (tools, toolchains, provisioning), not one merely found on PATH. */
    public Optional<String> configured(String name) {
        Tool tool = tools.get(name);
        return tool == null || tool.origin() == Origin.PATH ? Optional.empty() : Optional.of(tool.path());
    }

    /** All resolved tools, sorted by name. */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }
}
