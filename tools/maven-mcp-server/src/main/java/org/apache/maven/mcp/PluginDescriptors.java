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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;

/**
 * Describes one plugin goal from the plugin's own descriptor, {@code META-INF/maven/plugin.xml} in the plugin jar of
 * the local repository: phase, thread safety and each configurable parameter with its type, user property, default
 * and the first sentence of its documentation. A goal is named {@code prefix:goal}, {@code groupId:artifactId:goal}
 * or {@code groupId:artifactId:version:goal}. Without a version, the version pinned in the root pom.xml is used,
 * else the highest release in the local repository.
 */
final class PluginDescriptors {

    /** A goal resolved to a plugin jar in the local repository. */
    record Resolved(Path jar, String goal, String versionSource) {}

    static final int MAX_PARAMETERS = 80;

    private static final List<String> PREFIX_GROUPS = List.of("org.apache.maven.plugins", "org.codehaus.mojo");
    private static final String DESCRIPTOR = "META-INF/maven/plugin.xml";
    private static final int DOC_CHARS = 200;

    private PluginDescriptors() {}

    /** The plugin jar for the goal, or empty when it is not in the local repository. */
    static Optional<Resolved> resolve(String spec, Path localRepo, Path root) throws IOException {
        String[] parts = split(spec);
        String goal = parts[parts.length - 1];
        Optional<String[]> plugin = parts.length == 2
                ? byPrefix(parts[0], localRepo)
                : Optional.of(new String[] {parts[0], parts[1]});
        Element pom = SecureXml.parse(root.resolve("pom.xml")).getDocumentElement();
        return plugin.flatMap(ga -> parts.length == 4
                        ? jar(localRepo, ga, parts[2], "goal")
                        : unversioned(localRepo, pom, ga))
                .map(found -> new Resolved(found.jar(), goal, found.versionSource()));
    }

    /** The plugin part of the spec: the prefix, or groupId:artifactId with the version when given. */
    static String plugin(String spec) {
        String[] parts = split(spec);
        return String.join(":", Arrays.copyOf(parts, parts.length - 1));
    }

    static ObjectNode describe(Resolved resolved) throws IOException {
        Element plugin = descriptor(resolved.jar());
        Element mojo = SecureXml.path(plugin, "mojos", "mojo").stream()
                .filter(candidate -> resolved.goal().equals(SecureXml.text(candidate, "goal")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no goal " + resolved.goal() + " in "
                        + coordinates(plugin) + "; goals: " + goals(plugin)));
        ObjectNode out = CompactJson.object()
                .put("plugin", coordinates(plugin))
                .put("goalPrefix", SecureXml.text(plugin, "goalPrefix"))
                .put("goal", resolved.goal())
                .put("versionSource", resolved.versionSource())
                .put("description", firstSentence(SecureXml.text(mojo, "description")));
        mojoFacts(mojo, out);
        return CompactJson.capped(out, "parameters", parameters(mojo), MAX_PARAMETERS);
    }

    private static String[] split(String spec) {
        String[] parts = ArgumentGuard.goal(spec).split(":", -1);
        if (parts.length < 2 || parts.length > 4 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("goal must be prefix:goal, groupId:artifactId:goal or"
                    + " groupId:artifactId:version:goal");
        }
        return parts;
    }

    private static Optional<Resolved> unversioned(Path localRepo, Element pom, String[] ga) {
        return pomVersion(pom, ga[0], ga[1])
                .flatMap(version -> jar(localRepo, ga, version, "pom.xml"))
                .or(() -> highestRelease(localRepo, ga).flatMap(version -> jar(localRepo, ga, version,
                        "local repository")));
    }

    /** The plugin's version in the root POM's build plugins or plugin management, with one property resolved. */
    private static Optional<String> pomVersion(Element pom, String groupId, String artifactId) {
        return Stream.of(
                        SecureXml.path(pom, "build", "plugins", "plugin"),
                        SecureXml.path(pom, "build", "pluginManagement", "plugins", "plugin"))
                .flatMap(List::stream)
                .filter(plugin -> artifactId.equals(SecureXml.text(plugin, "artifactId")))
                .filter(plugin -> groupId.equals(groupOf(plugin)))
                .map(plugin -> property(pom, SecureXml.text(plugin, "version")))
                .filter(version -> !version.isEmpty() && !version.contains("${"))
                .findFirst();
    }

    /** A plugin's groupId, org.apache.maven.plugins when the POM leaves it out. */
    private static String groupOf(Element plugin) {
        return firstNonEmpty(SecureXml.text(plugin, "groupId"), PREFIX_GROUPS.get(0));
    }

    private static String property(Element pom, String version) {
        if (!version.startsWith("${") || !version.endsWith("}")) {
            return version;
        }
        String name = version.substring(2, version.length() - 1);
        return SecureXml.child(pom, "properties").map(properties -> SecureXml.text(properties, name)).orElse("");
    }

    private static void mojoFacts(Element mojo, ObjectNode out) {
        out.put("phase", SecureXml.text(mojo, "phase"));
        out.put("threadSafe", "true".equals(SecureXml.text(mojo, "threadSafe")));
        out.put("aggregator", "true".equals(SecureXml.text(mojo, "aggregator")));
        out.put("requiresProject", !"false".equals(SecureXml.text(mojo, "requiresProject")));
    }

    private static List<JsonNode> parameters(Element mojo) {
        Optional<Element> configuration = SecureXml.child(mojo, "configuration");
        return SecureXml.path(mojo, "parameters", "parameter").stream()
                .filter(parameter -> !"false".equals(SecureXml.text(parameter, "editable")))
                .map(parameter -> parameter(parameter, configuration))
                .collect(Collectors.toList());
    }

    private static JsonNode parameter(Element parameter, Optional<Element> configuration) {
        String name = SecureXml.text(parameter, "name");
        Optional<Element> config = configuration.flatMap(element -> SecureXml.child(element, name));
        String expression = firstNonEmpty(SecureXml.text(parameter, "expression"),
                config.map(element -> element.getTextContent().trim()).orElse(""));
        String defaultValue = firstNonEmpty(SecureXml.text(parameter, "defaultValue"),
                config.map(element -> element.getAttribute("default-value")).orElse(""));
        String property = expression.matches("\\$\\{[^}]+}") ? expression.substring(2, expression.length() - 1) : "";
        ObjectNode row = CompactJson.object().put("name", name).put("type", SecureXml.text(parameter, "type"));
        putNonEmpty(row, "property", property);
        putNonEmpty(row, "default", defaultValue);
        if ("true".equals(SecureXml.text(parameter, "required"))) {
            row.put("required", true);
        }
        return row.put("doc", firstSentence(SecureXml.text(parameter, "description")));
    }

    private static void putNonEmpty(ObjectNode row, String name, String value) {
        if (!value.isEmpty()) {
            row.put(name, value);
        }
    }

    private static Optional<String[]> byPrefix(String prefix, Path localRepo) {
        return PREFIX_GROUPS.stream()
                .flatMap(group -> Stream.of("maven-" + prefix + "-plugin", prefix + "-maven-plugin")
                        .map(artifact -> new String[] {group, artifact}))
                .filter(ga -> Files.isDirectory(artifactDir(localRepo, ga)))
                .findFirst();
    }

    private static Optional<Resolved> jar(Path localRepo, String[] ga, String version, String source) {
        Path jar = artifactDir(localRepo, ga).resolve(version).resolve(ga[1] + "-" + version + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(new Resolved(jar, "", source)) : Optional.empty();
    }

    /** The highest version made only of numbers and dots that has a jar in the local repository. */
    private static Optional<String> highestRelease(Path localRepo, String[] ga) {
        Path dir = artifactDir(localRepo, ga);
        try (Stream<Path> versions = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            return versions.map(path -> path.getFileName().toString())
                    .filter(version -> version.matches("[0-9]+(\\.[0-9]+)*"))
                    .filter(version -> jar(localRepo, ga, version, "").isPresent())
                    .max(Comparator.comparing(PluginDescriptors::numbers, Arrays::compare));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] numbers(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static Path artifactDir(Path localRepo, String[] ga) {
        return localRepo.resolve(ga[0].replace('.', '/')).resolve(ga[1]);
    }

    private static Element descriptor(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = Optional.ofNullable(zip.getEntry(DESCRIPTOR))
                    .orElseThrow(() -> new IOException("no " + DESCRIPTOR + " in " + jar));
            try (InputStream in = zip.getInputStream(entry)) {
                return SecureXml.parse(in).getDocumentElement();
            }
        }
    }

    private static String coordinates(Element plugin) {
        return SecureXml.text(plugin, "groupId") + ":" + SecureXml.text(plugin, "artifactId") + ":"
                + SecureXml.text(plugin, "version");
    }

    private static String goals(Element plugin) {
        return SecureXml.path(plugin, "mojos", "mojo").stream()
                .map(mojo -> SecureXml.text(mojo, "goal"))
                .collect(Collectors.joining(", "));
    }

    /** The text up to the first sentence end, without HTML tags, at most {@value #DOC_CHARS} characters. */
    static String firstSentence(String html) {
        String text = html.replaceAll("<[^>]*>", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        int end = text.indexOf(". ");
        return CompactJson.cap(end < 0 ? text : text.substring(0, end + 1), DOC_CHARS);
    }

    private static String firstNonEmpty(String first, String second) {
        return first.isEmpty() ? second : first;
    }
}
