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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;

/**
 * A compact view of the file that maven-help-plugin's effective-pom goal writes: per project its coordinates,
 * parent, modules, build plugins with their executions, and dependencies, as short strings.
 */
final class EffectivePom {

    static final int MAX_PROJECTS = 50;
    static final int MAX_PLUGINS = 60;
    static final int MAX_DEPENDENCIES = 200;

    private EffectivePom() {}

    static ObjectNode summarize(Path file) throws IOException {
        Element root = SecureXml.parse(file).getDocumentElement();
        List<Element> projects = "projects".equals(root.getTagName())
                ? SecureXml.children(root, "project")
                : List.of(root);
        ObjectNode out = CompactJson.object().put("file", file.toString()).put("sha256", Digests.sha256(file));
        List<JsonNode> rows = projects.stream().map(EffectivePom::project).collect(Collectors.toList());
        return CompactJson.capped(out, "projects", rows, MAX_PROJECTS);
    }

    private static JsonNode project(Element project) {
        ObjectNode out = CompactJson.object()
                .put("id", coordinates(project))
                .put("packaging", SecureXml.text(project, "packaging"));
        SecureXml.child(project, "parent").ifPresent(parent -> out.put("parent", coordinates(parent)));
        List<JsonNode> modules = Stream.concat(
                        SecureXml.path(project, "modules", "module").stream(),
                        SecureXml.path(project, "subprojects", "subproject").stream())
                .map(module -> CompactJson.MAPPER.getNodeFactory().textNode(module.getTextContent().trim()))
                .collect(Collectors.toList());
        if (!modules.isEmpty()) {
            out.putArray("modules").addAll(modules);
        }
        CompactJson.capped(out, "plugins", plugins(project), MAX_PLUGINS);
        return CompactJson.capped(out, "dependencies", dependencies(project), MAX_DEPENDENCIES);
    }

    private static List<JsonNode> plugins(Element project) {
        return SecureXml.path(project, "build", "plugins", "plugin").stream()
                .map(EffectivePom::plugin)
                .collect(Collectors.toList());
    }

    private static JsonNode plugin(Element plugin) {
        ObjectNode row = CompactJson.object().put("id", coordinates(plugin));
        List<String> executions = SecureXml.path(plugin, "executions", "execution").stream()
                .map(EffectivePom::execution)
                .collect(Collectors.toList());
        if (!executions.isEmpty()) {
            executions.forEach(row.putArray("executions")::add);
        }
        return row;
    }

    /** "id@phase:goal,goal", the phase left out when the execution has none. */
    private static String execution(Element execution) {
        String goals = SecureXml.path(execution, "goals", "goal").stream()
                .map(goal -> goal.getTextContent().trim())
                .collect(Collectors.joining(","));
        String phase = SecureXml.text(execution, "phase");
        return SecureXml.text(execution, "id") + "@" + (phase.isEmpty() ? "" : phase + ":") + goals;
    }

    /** "groupId:artifactId:version[:type][:classifier] scope[ optional]". */
    private static List<JsonNode> dependencies(Element project) {
        return SecureXml.path(project, "dependencies", "dependency").stream()
                .map(dependency -> CompactJson.MAPPER.getNodeFactory().textNode(dependency(dependency)))
                .collect(Collectors.toList());
    }

    private static String dependency(Element dependency) {
        String type = SecureXml.text(dependency, "type");
        String classifier = SecureXml.text(dependency, "classifier");
        String scope = SecureXml.text(dependency, "scope");
        return coordinates(dependency)
                + (type.isEmpty() || "jar".equals(type) ? "" : ":" + type)
                + (classifier.isEmpty() ? "" : ":" + classifier)
                + " " + (scope.isEmpty() ? "compile" : scope)
                + ("true".equals(SecureXml.text(dependency, "optional")) ? " optional" : "");
    }

    private static String coordinates(Element element) {
        String groupId = SecureXml.text(element, "groupId");
        return (groupId.isEmpty() ? "org.apache.maven.plugins" : groupId) + ":"
                + SecureXml.text(element, "artifactId") + ":" + SecureXml.text(element, "version");
    }
}
