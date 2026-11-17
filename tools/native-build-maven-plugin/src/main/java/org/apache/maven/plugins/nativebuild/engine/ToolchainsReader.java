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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Native tool entries of toolchains.xml: types cmake, ctest, make, ninja, meson, sh, cc and cxx, each with
 * {@code provides} (matched against requirements) and {@code configuration/executable} or
 * {@code configuration/installationPath}. Maven's toolchain manager returns nothing for types without a toolchain
 * factory, so the plugin reads the raw entries: from Maven's request in a build, from a file here.
 */
public final class ToolchainsReader {
    /**
     * One toolchain entry.
     *
     * @param type the toolchain type
     * @param provides its provides properties
     * @param configuration its configuration children (name to text)
     */
    public record Entry(String type, Map<String, String> provides, Map<String, String> configuration) {
        /** Copies the maps. */
        public Entry {
            provides = Map.copyOf(provides);
            configuration = Map.copyOf(configuration);
        }
    }

    private ToolchainsReader() {}

    /** All entries of a toolchains.xml file. */
    public static List<Entry> parse(Path file) throws IOException {
        Document document = XmlSupport.parse(file);
        return XmlSupport.elements(document, "toolchain").stream().map(ToolchainsReader::entry).toList();
    }

    /** The children of a serialized {@code <configuration>} element (Maven hands the configuration as a DOM). */
    public static Map<String, String> configuration(String xml) throws IOException {
        return xml == null || xml.isBlank() ? Map.of() : XmlSupport.childTexts(XmlSupport.parse(xml).getDocumentElement());
    }

    /**
     * The executable of the first entry of {@code type} whose provides contain every requirement: its configured
     * executable, or {@code <type>} in the bin folder (or the root) of its installationPath.
     */
    public static Optional<Path> executable(List<Entry> entries, String type, Map<String, String> requirements,
            Platform platform) {
        return entries.stream().filter(e -> type.equals(e.type()))
                .filter(e -> e.provides().entrySet().containsAll(requirements.entrySet())).findFirst()
                .flatMap(e -> locate(e, type, platform));
    }

    private static Optional<Path> locate(Entry entry, String type, Platform platform) {
        String executable = entry.configuration().getOrDefault("executable", "");
        if (!executable.isEmpty()) {
            return Optional.of(Path.of(executable));
        }
        String home = entry.configuration().getOrDefault("installationPath", "");
        String file = platform.windows() ? type + ".exe" : type;
        return home.isEmpty() ? Optional.empty() : Stream.of(Path.of(home, "bin", file), Path.of(home, file))
                .filter(Files::isRegularFile).findFirst();
    }

    private static Entry entry(Element toolchain) {
        Map<String, String> fields = XmlSupport.childTexts(toolchain);
        Element provides = child(toolchain, "provides");
        Element configuration = child(toolchain, "configuration");
        return new Entry(fields.getOrDefault("type", ""),
                provides == null ? Map.of() : XmlSupport.childTexts(provides),
                configuration == null ? Map.of() : XmlSupport.childTexts(configuration));
    }

    private static Element child(Element parent, String name) {
        return XmlSupport.children(parent).stream().filter(e -> name.equals(e.getTagName())).findFirst().orElse(null);
    }
}
