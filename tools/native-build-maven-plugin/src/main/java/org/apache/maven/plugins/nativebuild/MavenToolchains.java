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
package org.apache.maven.plugins.nativebuild;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.maven.plugins.nativebuild.engine.ToolchainsReader;
import org.apache.maven.toolchain.model.ToolchainModel;

/**
 * The raw toolchains.xml entries of the running build ({@code MavenExecutionRequest.getToolchains()}, since Maven
 * 3.3.0). Maven's toolchain manager answers only types that have a toolchain factory (jdk), so native types are read
 * from the models: type, provides and the configuration DOM, which serializes to XML.
 */
final class MavenToolchains {
    private MavenToolchains() {}

    /** All entries, or none when toolchains are switched off or the request has none. */
    static List<ToolchainsReader.Entry> entries(AbstractNativeMojo m) throws IOException {
        if (!m.toolchains || m.session == null || m.session.getRequest() == null) {
            return List.of();
        }
        Map<String, List<ToolchainModel>> byType = m.session.getRequest().getToolchains();
        List<ToolchainsReader.Entry> entries = new ArrayList<>();
        for (List<ToolchainModel> models : byType == null ? List.<List<ToolchainModel>>of() : byType.values()) {
            for (ToolchainModel model : models) {
                entries.add(entry(model));
            }
        }
        return entries;
    }

    private static ToolchainsReader.Entry entry(ToolchainModel model) throws IOException {
        Object configuration = model.getConfiguration();
        return new ToolchainsReader.Entry(model.getType() == null ? "" : model.getType(), provides(model.getProvides()),
                ToolchainsReader.configuration(configuration == null ? "" : configuration.toString()));
    }

    private static Map<String, String> provides(Properties properties) {
        Map<String, String> map = new TreeMap<>();
        if (properties != null) {
            properties.stringPropertyNames().forEach(name -> map.put(name, properties.getProperty(name)));
        }
        return map;
    }
}
