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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XML reading for POMs, effective POMs and plugin descriptors: no DOCTYPE, no external entities, no XInclude, and
 * parse problems reported as {@link IOException} instead of printed.
 */
final class SecureXml {

    private SecureXml() {}

    static Document parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    static Document parse(InputStream in) throws IOException {
        try {
            return builder().parse(in);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("XML parse failed: " + e.getMessage(), e);
        }
    }

    /** The direct child elements of {@code parent} named {@code name}, in document order. */
    static List<Element> children(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        return IntStream.range(0, nodes.getLength())
                .mapToObj(nodes::item)
                .filter(Element.class::isInstance)
                .map(Element.class::cast)
                .filter(element -> name.equals(element.getTagName()))
                .collect(Collectors.toList());
    }

    static Optional<Element> child(Element parent, String name) {
        return children(parent, name).stream().findFirst();
    }

    /** The elements at the end of a path of child names, such as {@code build, plugins, plugin}. */
    static List<Element> path(Element parent, String... names) {
        List<Element> level = List.of(parent);
        return IntStream.range(0, names.length).boxed().reduce(level, (current, index) -> current.stream()
                .flatMap(element -> children(element, names[index]).stream())
                .collect(Collectors.toList()), (left, right) -> right);
    }

    /** The trimmed text of the first child named {@code name}, or "" when there is none. */
    static String text(Element parent, String name) {
        return child(parent, name).map(element -> element.getTextContent().trim()).orElse("");
    }

    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }
}
