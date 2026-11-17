/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Read-only Eclipse project evidence for Maven, m2e and external build hosts.
 * Eclipse remains the owner of builders, classpath resolution, natures and toolchain state.
 * No workspace resource, linked path, compiler, LSP server or build command is opened.
 */
public final class EclipseProjectModel {
    private static final int MAX_DESCRIPTOR_BYTES = 8 * 1024 * 1024;
    private static final List<String> DESCRIPTORS = List.of(
            ".project", ".classpath", ".cproject", ".pydevproject", "Cargo.toml",
            ".settings/org.eclipse.jdt.core.prefs", ".settings/org.eclipse.cdt.core.prefs",
            ".settings/org.python.pydev.prefs");

    /** Original classpath attributes and children; no Maven classpath is synthesized. */
    public record ClasspathEntry(Map<String, String> attributes, List<Map<String, String>> accessRules,
            List<Map<String, String>> extraAttributes) {
        public ClasspathEntry {
            attributes = Map.copyOf(attributes);
            accessRules = accessRules.stream().map(Map::copyOf).toList();
            extraAttributes = extraAttributes.stream().map(Map::copyOf).toList();
        }
    }

    /** Eclipse linked-resource location is recorded, never followed or normalized. */
    public record LinkedResource(String name, String type, String location, String locationUri) { }

    /** Ordered builder declaration; arguments are evidence, not instructions to Maven. */
    public record BuildCommand(String name, String triggers, Map<String, String> arguments) {
        public BuildCommand { arguments = Map.copyOf(arguments); }
    }

    public record CdtConfiguration(String id, String name) { }

    public record PydevPath(String property, String path) { }

    /** Immutable Eclipse descriptor snapshot, with digests for every present recognized file. */
    public record Snapshot(String name, List<String> natureIds, List<String> builders, List<BuildCommand> buildCommands,
            List<String> projectReferences, List<LinkedResource> linkedResources,
            List<ClasspathEntry> classpath, List<CdtConfiguration> cdtConfigurations,
            Map<String, String> pydevProperties, List<PydevPath> pydevPaths,
            Map<String, String> descriptorSha256) {
        public Snapshot {
            natureIds = List.copyOf(natureIds);
            builders = List.copyOf(builders);
            buildCommands = List.copyOf(buildCommands);
            projectReferences = List.copyOf(projectReferences);
            linkedResources = List.copyOf(linkedResources);
            classpath = List.copyOf(classpath);
            cdtConfigurations = List.copyOf(cdtConfigurations);
            pydevProperties = Collections.unmodifiableMap(new TreeMap<>(pydevProperties));
            pydevPaths = List.copyOf(pydevPaths);
            descriptorSha256 = Collections.unmodifiableMap(new TreeMap<>(descriptorSha256));
        }

        /** Stable, UTF-8-compatible JSON; no dependency on Maven or OSGi classes. */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"schema\":\"m3-eclipse-project-v1\"");
            field(out, "name", name);
            strings(out, "natureIds", natureIds);
            strings(out, "builders", builders);
            out.append(",\"buildCommands\":[");
            for (int i = 0; i < buildCommands.size(); i++) {
                if (i > 0) out.append(',');
                BuildCommand command = buildCommands.get(i);
                out.append('{');
                property(out, "name", command.name());
                out.append(',');
                property(out, "triggers", command.triggers());
                out.append(',');
                property(out, "arguments", command.arguments());
                out.append('}');
            }
            out.append(']');
            strings(out, "projectReferences", projectReferences);
            out.append(",\"linkedResources\":[");
            for (int i = 0; i < linkedResources.size(); i++) {
                if (i > 0) out.append(',');
                LinkedResource link = linkedResources.get(i);
                out.append('{');
                property(out, "name", link.name());
                out.append(',');
                property(out, "type", link.type());
                out.append(',');
                property(out, "location", link.location());
                out.append(',');
                property(out, "locationUri", link.locationUri());
                out.append('}');
            }
            out.append(']');
            out.append(",\"classpath\":[");
            for (int i = 0; i < classpath.size(); i++) {
                if (i > 0) out.append(',');
                ClasspathEntry entry = classpath.get(i);
                out.append('{');
                property(out, "attributes", entry.attributes());
                out.append(',');
                property(out, "accessRules", entry.accessRules());
                out.append(',');
                property(out, "extraAttributes", entry.extraAttributes());
                out.append('}');
            }
            out.append(']');
            out.append(",\"cdtConfigurations\":[");
            for (int i = 0; i < cdtConfigurations.size(); i++) {
                if (i > 0) out.append(',');
                CdtConfiguration config = cdtConfigurations.get(i);
                out.append('{');
                property(out, "id", config.id());
                out.append(',');
                property(out, "name", config.name());
                out.append('}');
            }
            out.append(']');
            field(out, "pydevProperties", pydevProperties);
            out.append(",\"pydevPaths\":[");
            for (int i = 0; i < pydevPaths.size(); i++) {
                if (i > 0) out.append(',');
                PydevPath path = pydevPaths.get(i);
                out.append('{');
                property(out, "property", path.property());
                out.append(',');
                property(out, "path", path.path());
                out.append('}');
            }
            out.append(']');
            field(out, "descriptorSha256", descriptorSha256);
            return out.append('}').toString();
        }
    }

    private EclipseProjectModel() { }

    public static Snapshot inspect(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Eclipse model root must be an existing directory");
        }
        Map<String, String> digests = new TreeMap<>();
        Document project = xml(root, ".project", "projectDescription", true, digests);
        Document jdt = xml(root, ".classpath", "classpath", false, digests);
        Document cdt = xml(root, ".cproject", "cproject", false, digests);
        Document pydev = xml(root, ".pydevproject", "pydev_project", false, digests);
        for (String path : DESCRIPTORS.subList(4, DESCRIPTORS.size())) {
            byte[] data = bytes(root.resolve(path), false);
            if (data != null) digests.put(path, sha256(data));
        }

        Element main = project.getDocumentElement();
        String name = childText(main, "name");
        if (name.isBlank()) throw new IOException("Eclipse .project has no project name");
        List<String> natures = childTexts(main, "natures", "nature");
        List<String> references = childTexts(main, "projects", "project");
        List<String> builders = new ArrayList<>();
        List<BuildCommand> buildCommands = new ArrayList<>();
        Element buildSpec = child(main, "buildSpec");
        if (buildSpec != null) {
            for (Element command : children(buildSpec, "buildCommand")) {
                String builderName = childText(command, "name");
                builders.add(builderName);
                Map<String, String> arguments = new TreeMap<>();
                Element args = child(command, "arguments");
                if (args != null) {
                    for (Element dictionary : children(args, "dictionary")) {
                        arguments.put(childText(dictionary, "key"), childText(dictionary, "value"));
                    }
                }
                buildCommands.add(new BuildCommand(builderName, childText(command, "triggers"), arguments));
            }
        }
        List<LinkedResource> linked = new ArrayList<>();
        Element linkedResources = child(main, "linkedResources");
        if (linkedResources != null) {
            for (Element resource : children(linkedResources, "link")) {
                linked.add(new LinkedResource(childText(resource, "name"), childText(resource, "type"),
                        childText(resource, "location"), childText(resource, "locationURI")));
            }
        }
        List<ClasspathEntry> classpath = new ArrayList<>();
        if (jdt != null) {
            for (Element entry : children(jdt.getDocumentElement(), "classpathentry")) {
                classpath.add(new ClasspathEntry(attributes(entry), nestedAttributes(entry, "accessrules", "accessrule"),
                        nestedAttributes(entry, "attributes", "attribute")));
            }
        }
        List<CdtConfiguration> configurations = new ArrayList<>();
        if (cdt != null) {
            var nodes = cdt.getElementsByTagName("cconfiguration");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element config = (Element) nodes.item(i);
                String configName = config.getAttribute("name");
                for (Element storage : children(config, "storageModule")) {
                    if ("org.eclipse.cdt.core.settings".equals(storage.getAttribute("moduleId"))) {
                        configName = storage.getAttribute("name");
                        break;
                    }
                }
                configurations.add(new CdtConfiguration(config.getAttribute("id"), configName));
            }
        }
        Map<String, String> pythonProperties = new TreeMap<>();
        List<PydevPath> pythonPaths = new ArrayList<>();
        if (pydev != null) {
            Element rootElement = pydev.getDocumentElement();
            for (Element property : children(rootElement, "pydev_property")) {
                pythonProperties.put(property.getAttribute("name"), property.getTextContent());
            }
            for (Element property : children(rootElement, "pydev_pathproperty")) {
                for (Element path : children(property, "path")) {
                    pythonPaths.add(new PydevPath(property.getAttribute("name"), path.getTextContent()));
                }
            }
        }
        return new Snapshot(name, natures, builders, buildCommands, references, linked, classpath,
                configurations, pythonProperties, pythonPaths, digests);
    }

    private static Document xml(Path root, String name, String expectedRoot, boolean required,
            Map<String, String> digests) throws IOException {
        byte[] data = bytes(root.resolve(name), required);
        if (data == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException { throw exception; }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
            });
            Document document = builder.parse(new ByteArrayInputStream(data));
            if (!expectedRoot.equals(document.getDocumentElement().getNodeName())) {
                throw new IOException("unexpected root in " + name);
            }
            digests.put(name, sha256(data));
            return document;
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException failure) {
            throw new IOException("invalid Eclipse descriptor " + name + ": " + failure.getMessage(), failure);
        }
    }

    private static byte[] bytes(Path file, boolean required) throws IOException {
        // A linked .settings directory is not a trusted Eclipse project metadata owner.
        if (".settings".equals(file.getParent().getFileName().toString())
                && Files.isSymbolicLink(file.getParent())) {
            throw new IOException("symlinked Eclipse settings directory is not inspected: " + file.getParent());
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!required) return null;
            throw new IOException("missing Eclipse descriptor: " + file.getFileName());
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("Eclipse descriptor must be a non-symlink regular file under 8 MiB: " + file);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (data.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("Eclipse descriptor byte limit exceeded: " + file);
            }
            return data;
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getTagName())) result.add(element);
        }
        return result;
    }

    private static Element child(Element parent, String tag) {
        for (Element element : children(parent, tag)) return element;
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element item = child(parent, tag);
        return item == null ? "" : item.getTextContent();
    }

    private static List<String> childTexts(Element parent, String container, String tag) {
        Element items = child(parent, container);
        if (items == null) return List.of();
        return children(items, tag).stream().map(Element::getTextContent).toList();
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> result = new TreeMap<>();
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            result.put(attr.getNodeName(), attr.getNodeValue());
        }
        return result;
    }

    private static List<Map<String, String>> nestedAttributes(Element element, String container, String tag) {
        Element parent = child(element, container);
        if (parent == null) return List.of();
        return children(parent, tag).stream().map(EclipseProjectModel::attributes).toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    private static void strings(StringBuilder out, String key, List<String> values) {
        out.append(',');
        quoted(out, key);
        out.append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            quoted(out, values.get(i));
        }
        out.append(']');
    }

    private static void field(StringBuilder out, String key, Object value) {
        out.append(',');
        property(out, key, value);
    }

    private static void property(StringBuilder out, String key, Object value) {
        quoted(out, key);
        out.append(':');
        value(out, value);
    }

    private static void value(StringBuilder out, Object value) {
        if (value instanceof String str) {
            quoted(out, str);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            int index = 0;
            Map<String, Object> ordered = new TreeMap<>();
            for (var entry : map.entrySet()) ordered.put((String) entry.getKey(), entry.getValue());
            for (var entry : ordered.entrySet()) {
                if (index++ > 0) out.append(',');
                property(out, entry.getKey(), entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                value(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("unsupported model value");
        }
    }

    private static void quoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                                .append(Character.forDigit(c & 15, 16));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
