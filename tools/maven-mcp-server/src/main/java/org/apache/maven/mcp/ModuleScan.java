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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;

/**
 * The modules of the project. When {@code target/agent/project.json} exists (written by the agent plugin's
 * describe-project goal) its module list is returned; otherwise the POMs are read directly, without running Maven:
 * {@code <modules>} and Maven 4 {@code <subprojects>}, depth first in declaration order, with groupId and version
 * inherited from {@code <parent>} and the dependencies between reactor modules.
 */
final class ModuleScan {

    static final int MAX_MODULES = 500;

    private static final int MAX_SCAN = 5000;
    private static final int MAX_DEPTH = 50;

    /** One module as its POM declares it. */
    record Module(String id, String path, String packaging, String version, List<String> dependencies) {}

    private ModuleScan() {}

    static ObjectNode list(Path root) throws IOException {
        Path projectJson = root.resolve("target").resolve("agent").resolve("project.json");
        if (Files.isRegularFile(projectJson)) {
            return fromProjectJson(projectJson);
        }
        List<Module> modules = new ArrayList<>();
        visit(root, root.resolve("pom.xml"), 0, modules, new HashSet<>());
        return render(root, modules);
    }

    private static ObjectNode fromProjectJson(Path file) throws IOException {
        JsonNode project = CompactJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<JsonNode> modules = new ArrayList<>();
        project.path("modules").forEach(modules::add);
        ObjectNode out = CompactJson.object().put("source", "project.json").put("file", file.toString());
        out.put("count", modules.size());
        CompactJson.capped(out, "modules", modules, MAX_MODULES);
        Optional.ofNullable(project.get("commands")).ifPresent(commands -> out.set("commands", commands));
        return out;
    }

    private static void visit(Path root, Path pom, int depth, List<Module> found, Set<Path> seen) {
        Path real = realPath(pom);
        if (depth > MAX_DEPTH || found.size() >= MAX_SCAN || !Files.isRegularFile(pom) || !seen.add(real)) {
            return;
        }
        Element project = document(pom);
        found.add(module(root, pom, project));
        Stream.concat(
                        SecureXml.path(project, "modules", "module").stream(),
                        SecureXml.path(project, "subprojects", "subproject").stream())
                .map(element -> modulePom(pom.getParent(), element.getTextContent().trim()))
                .forEach(child -> visit(root, child, depth + 1, found, seen));
    }

    private static Module module(Path root, Path pom, Element project) {
        Optional<Element> parent = SecureXml.child(project, "parent");
        String groupId = inherited(project, parent, "groupId");
        String packaging = SecureXml.text(project, "packaging");
        return new Module(
                groupId + ":" + SecureXml.text(project, "artifactId"),
                relative(root, pom.getParent()),
                packaging.isEmpty() ? "jar" : packaging,
                inherited(project, parent, "version"),
                dependencies(project, groupId));
    }

    private static String inherited(Element project, Optional<Element> parent, String name) {
        String own = SecureXml.text(project, name);
        return own.isEmpty() ? parent.map(element -> SecureXml.text(element, name)).orElse("") : own;
    }

    private static List<String> dependencies(Element project, String groupId) {
        return SecureXml.path(project, "dependencies", "dependency").stream()
                .map(dependency -> SecureXml.text(dependency, "groupId")
                                .replace("${project.groupId}", groupId)
                                .replace("${pom.groupId}", groupId)
                        + ":" + SecureXml.text(dependency, "artifactId"))
                .collect(Collectors.toList());
    }

    private static ObjectNode render(Path root, List<Module> modules) {
        Set<String> ids = modules.stream().map(Module::id).collect(Collectors.toSet());
        List<JsonNode> rows = IntStream.range(0, modules.size())
                .mapToObj(index -> row(index + 1, modules.get(index), ids))
                .collect(Collectors.toList());
        ObjectNode out = CompactJson.object()
                .put("source", "pom-scan")
                .put("order", "declaration")
                .put("root", root.toString())
                .put("count", modules.size());
        return CompactJson.capped(out, "modules", rows, MAX_MODULES);
    }

    /** One module; {@code index} counts from 1, as in the build events and project.json. */
    private static JsonNode row(int index, Module module, Set<String> reactorIds) {
        ObjectNode row = CompactJson.object()
                .put("index", index)
                .put("id", module.id())
                .put("path", module.path())
                .put("packaging", module.packaging())
                .put("version", module.version());
        List<String> dependsOn = module.dependencies().stream()
                .filter(reactorIds::contains)
                .distinct()
                .collect(Collectors.toList());
        if (!dependsOn.isEmpty()) {
            dependsOn.forEach(row.putArray("dependsOn")::add);
        }
        return row;
    }

    private static Path modulePom(Path dir, String declared) {
        Path target = dir.resolve(declared).normalize();
        return Files.isDirectory(target) ? target.resolve("pom.xml") : target;
    }

    private static String relative(Path root, Path dir) {
        String path = root.relativize(dir).toString().replace('\\', '/');
        return path.isEmpty() ? "." : path;
    }

    private static Element document(Path pom) {
        try {
            return SecureXml.parse(pom).getDocumentElement();
        } catch (IOException e) {
            throw new UncheckedIOException(pom + ": " + e.getMessage(), e);
        }
    }

    private static Path realPath(Path pom) {
        try {
            return Files.exists(pom) ? pom.toRealPath() : pom.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
