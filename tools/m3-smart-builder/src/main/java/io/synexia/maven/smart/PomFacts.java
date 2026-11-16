/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Secure direct-only POM facts; does not evaluate the effective model or resolve dependencies. */
public record PomFacts(String group, String artifact, String version, String packaging,
                       List<String> modules, List<String> dependencies) {
    public static PomFacts inspect(Path pom) throws IOException {
        if (!Files.isRegularFile(pom) || Files.size(pom) > 4 * 1024 * 1024)
            throw new IOException("POM missing or above 4 MiB");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(SAXParseException error) throws SAXParseException { throw error; }
                @Override public void error(SAXParseException error) throws SAXParseException { throw error; }
            });
            Element project = builder.parse(pom.toFile()).getDocumentElement();
            if (!"project".equals(project.getLocalName())) throw new IOException("not a Maven POM project");
            Element parent = direct(project, "parent");
            String group = scalar(project, "groupId");
            String version = scalar(project, "version");
            if (group.isEmpty() && parent != null) group = scalar(parent, "groupId");
            if (version.isEmpty() && parent != null) version = scalar(parent, "version");
            List<String> modules = new ArrayList<>();
            Element moduleContainer = direct(project, "modules");
            if (moduleContainer != null) for (Element element : children(moduleContainer, "module")) modules.add(safe(element.getTextContent()));
            List<String> dependencies = new ArrayList<>();
            Element depContainer = direct(project, "dependencies");
            if (depContainer != null) for (Element e : children(depContainer, "dependency")) {
                String g = scalar(e, "groupId"), a = scalar(e, "artifactId");
                if (!g.isBlank() && !a.isBlank()) dependencies.add(g + ":" + a + ":" + scalar(e,"scope"));
            }
            if (modules.size() > 4096 || dependencies.size() > 4096) throw new IOException("oversize direct POM structure");
            Collections.sort(modules);
            Collections.sort(dependencies);
            return new PomFacts(safe(group), scalar(project, "artifactId"), safe(version),
                    scalar(project, "packaging").isEmpty() ? "jar" : scalar(project,"packaging"),
                    List.copyOf(modules), List.copyOf(dependencies));
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("POM parse rejected", error); }
    }
    public String json() {
        return "{\"groupId\":" + TinyJson.quote(group)
                + ",\"artifactId\":" + TinyJson.quote(artifact)
                + ",\"version\":" + TinyJson.quote(version)
                + ",\"packaging\":" + TinyJson.quote(packaging)
                + ",\"moduleCount\":" + modules.size()
                + ",\"declaredDependencyCount\":" + dependencies.size() + "}";
    }
    public String boundedPrompt() {
        StringBuilder result = new StringBuilder();
        result.append("Declared POM coordinates=").append(group).append(':').append(artifact).append(':').append(version)
              .append("; packaging=").append(packaging).append("; modules=").append(modules.size())
              .append("; direct dependencies=").append(dependencies.size()).append("; sample=");
        int used = 0;
        for (String dep : dependencies) {
            if (used >= 40 || result.length() + dep.length() > 5000) break;
            result.append(dep).append(';'); used++;
        }
        return result.toString();
    }
    private static String scalar(Element parent, String name) {
        Element e = direct(parent, name);
        return e == null ? "" : safe(e.getTextContent());
    }
    private static String safe(String s) {
        String v = s == null ? "" : s.strip();
        if (v.length() > 256 || v.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("invalid POM scalar");
        return v;
    }
    private static Element direct(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && name.equals(e.getLocalName())) return e;
        }
        return null;
    }
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && name.equals(e.getLocalName())) result.add(e);
        }
        return result;
    }
}
