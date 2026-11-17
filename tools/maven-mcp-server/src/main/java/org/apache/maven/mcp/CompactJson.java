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
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.apache.maven.agent.events.Redact;

/**
 * Compact JSON for tool results: one shared Jackson mapper, no indentation, bounded strings, and bounded arrays that
 * say how many items were left out. Every string a client receives passes the build events extension's credential
 * redaction ({@link Redact#secrets}, shaded into the jar: one owner for both), and a capped string is redacted before
 * it is cut, so no {@code user:password@} of a URL reaches a client, from a log line or any other text.
 */
final class CompactJson {

    /** The one mapper of the server; Jackson comes with the MCP SDK. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** Longest message or log line a result carries, the same bound as the build event stream. */
    static final int MESSAGE_CHARS = 300;

    private static final int ERROR_CHARS = 2000;

    private CompactJson() {}

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** {@code text} without credentials, cut to {@code max} characters with the cut marked by "...". */
    static String cap(String text, int max) {
        if (text == null) {
            return null;
        }
        String clean = Redact.secrets(text);
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    /** A copy of {@code node} with every string value, at any depth, passed through {@link Redact#secrets}. */
    static JsonNode redacted(JsonNode node) {
        if (node.isTextual()) {
            return MAPPER.getNodeFactory().textNode(Redact.secrets(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode copy = array();
            node.forEach(item -> copy.add(redacted(item)));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = object();
            node.properties().forEach(field -> copy.set(field.getKey(), redacted(field.getValue())));
            return copy;
        }
        return node;
    }

    /** Puts at most {@code max} items under {@code name}, and the count left out under {@code <name>Omitted}. */
    static ObjectNode capped(ObjectNode parent, String name, List<? extends JsonNode> items, int max) {
        ArrayNode array = parent.putArray(name);
        items.stream().limit(max).forEach(array::add);
        if (items.size() > max) {
            parent.put(name + "Omitted", items.size() - max);
        }
        return parent;
    }

    /** Parses one JSON document from text. */
    static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    static String text(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CallToolResult result(JsonNode body) {
        return CallToolResult.builder().addTextContent(text(redacted(body))).isError(false).build();
    }

    static CallToolResult error(String message) {
        ObjectNode body = object().put("error", cap(message, ERROR_CHARS));
        return CallToolResult.builder().addTextContent(text(body)).isError(true).build();
    }
}
