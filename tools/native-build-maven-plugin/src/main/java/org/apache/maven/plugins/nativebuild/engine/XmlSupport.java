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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** XML reading without DTDs or external entities, and text escaping for the reports this plugin writes. */
final class XmlSupport {
    private XmlSupport() {}

    /** Parses a file. */
    static Document parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file), file.toString());
    }

    /** Parses a string. */
    static Document parse(String xml) throws IOException {
        return parse(xml.getBytes(StandardCharsets.UTF_8), "<string>");
    }

    private static Document parse(byte[] bytes, String name) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException invalid) {
            throw new IOException("cannot read XML " + name + ": " + invalid.getMessage(), invalid);
        }
    }

    /** The element children of {@code parent}. */
    static List<Element> children(Node parent) {
        NodeList nodes = parent.getChildNodes();
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item).filter(Element.class::isInstance)
                .map(Element.class::cast).toList();
    }

    /** Element name to trimmed text of each element child, in document order (a later duplicate wins). */
    static Map<String, String> childTexts(Node parent) {
        Map<String, String> map = new LinkedHashMap<>();
        children(parent).forEach(e -> map.put(e.getTagName(), e.getTextContent().trim()));
        return map;
    }

    /** All elements named {@code tag} anywhere below {@code root}. */
    static List<Element> elements(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item).map(Element.class::cast).toList();
    }

    /** Text safe for XML attributes and content; characters XML 1.0 cannot hold are dropped. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(XmlSupport::allowed).forEach(c -> out.append(entity(c)));
        return out.toString();
    }

    private static boolean allowed(int c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                || c >= 0x10000;
    }

    private static String entity(int c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&apos;";
            default -> new String(Character.toChars(c));
        };
    }
}
