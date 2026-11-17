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
package org.apache.maven.plugins.clustering.clang;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads the output of {@code clang -Xclang -ast-dump=json} as a stream, so a translation unit that pulls in large
 * system headers is never held in memory. Only nodes that start in the main file are kept, with their whole subtree;
 * every other node is read and dropped. Each top-level declaration that holds a kept node goes to the sink.
 *
 * <p>The dump leaves a location's {@code file} out when it equals the file of the location written just before it,
 * in output order. The reader therefore follows every location in the stream, including those of dropped nodes. A
 * location in an included file also carries {@code includedFrom.file}; that names the including file and does not
 * change the current file. A location inside a macro expansion is written as a {@code spellingLoc} and an
 * {@code expansionLoc}, both of which count for the current file; the expansion location is the one kept.
 *
 * <p>Named records (structs, classes, unions and their template specializations) are entered in {@code records}
 * by id with their enclosing record names, so a member function defined outside its class
 * ({@code parentDeclContextId}) gets its owner even when the class is declared in a header.
 */
final class AstStream {
    static final Set<String> RECORDS = Set.of("CXXRecordDecl", "RecordDecl", "ClassTemplateSpecializationDecl",
            "ClassTemplatePartialSpecializationDecl");
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Integer.MAX_VALUE)
                    .maxStringLength(Integer.MAX_VALUE).build())
            .build();

    private final JsonParser p;
    private final Predicate<String> isMain;
    private final Map<String, String> records;
    private final Deque<String> scopes = new ArrayDeque<>();
    private String lastFile = "";

    private AstStream(JsonParser p, Predicate<String> isMain, Map<String, String> records) {
        this.p = p;
        this.isMain = isMain;
        this.records = records;
    }

    /** One node being read: kept when its parent is kept or it starts in the main file. */
    private static final class Frame {
        final AstNode node = new AstNode();
        final boolean keepAll;
        Boolean keep;

        Frame(boolean keepAll) {
            this.keepAll = keepAll;
        }
    }

    /** The parts of one location object. */
    private static final class LocFields {
        int offset = -1;
        int tokLen;
        AstLoc expansion;
    }

    /**
     * Reads a whole dump.
     *
     * @param isMain  tells whether a file name, as clang printed it, is the main file
     * @param records receives record id to owner-qualified name
     * @param sink    receives each top-level declaration that is, or holds, a main-file node
     */
    static void read(InputStream in, Predicate<String> isMain, Map<String, String> records, Consumer<AstNode> sink)
            throws IOException {
        try (JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a clang JSON syntax tree dump");
            new AstStream(p, isMain, records).translationUnit(sink);
        }
    }

    private void translationUnit(Consumer<AstNode> sink) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            if (p.nextToken() != JsonToken.START_ARRAY || !field.equals("inner")) {
                p.skipChildren();
                continue;
            }
            while (p.nextToken() == JsonToken.START_OBJECT) {
                AstNode node = node(false);
                if (node != null) sink.accept(node);
            }
        }
    }

    /** Reads one node; the parser is on its START_OBJECT. Null when neither it nor a descendant is kept. */
    private AstNode node(boolean keepAll) throws IOException {
        Frame f = new Frame(keepAll);
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            field(f, field);
        }
        register(f.node);
        return keep(f) || !f.node.children.isEmpty() ? f.node : null;
    }

    private void field(Frame f, String field) throws IOException {
        AstNode n = f.node;
        switch (field) {
            case "id" -> n.id = p.getText();
            case "kind" -> n.kind = p.getText();
            case "name" -> n.name = scalar();
            case "loc" -> n.loc = location();
            case "range" -> range(f);
            case "inner" -> inner(f);
            default -> attribute(f, field);
        }
    }

    private boolean keep(Frame f) {
        if (f.keep == null) {
            AstLoc start = f.node.start();
            f.keep = f.keepAll || start != null && isMain.test(start.file());
        }
        return f.keep;
    }

    private String scalar() throws IOException {
        if (p.currentToken().isScalarValue()) return p.getText();
        p.skipChildren();
        return "";
    }

    private void range(Frame f) throws IOException {
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            if (field.equals("begin")) f.node.begin = location();
            else if (field.equals("end")) f.node.end = location();
            else p.skipChildren();
        }
        f.keep = null;
    }

    /** Reads a location object; the parser is on its START_OBJECT. Null for an empty location. */
    private AstLoc location() throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        LocFields fields = new LocFields();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            locationField(fields, field);
        }
        if (fields.expansion != null) return new AstLoc(fields.expansion.file(), fields.expansion.offset(),
                fields.expansion.tokLen(), true);
        return fields.offset < 0 ? null : new AstLoc(lastFile, fields.offset, fields.tokLen, false);
    }

    private void locationField(LocFields fields, String field) throws IOException {
        switch (field) {
            case "offset" -> fields.offset = p.getIntValue();
            case "tokLen" -> fields.tokLen = p.getIntValue();
            case "file" -> lastFile = p.getText();
            case "spellingLoc" -> location();
            case "expansionLoc" -> fields.expansion = location();
            default -> p.skipChildren();
        }
    }

    private void inner(Frame f) throws IOException {
        boolean keep = keep(f);
        boolean scope = RECORDS.contains(f.node.kind) && !f.node.name.isEmpty();
        if (scope) scopes.push(f.node.name);
        List<AstNode> kept = new ArrayList<>();
        while (p.nextToken() == JsonToken.START_OBJECT) {
            AstNode child = node(keep);
            if (child != null) kept.add(child);
        }
        if (scope) scopes.pop();
        f.node.children.addAll(kept);
    }

    /** Scalars are kept as text; {@code type}, {@code referencedDecl}, {@code decl} and {@code interface} by name. */
    private void attribute(Frame f, String field) throws IOException {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            objectAttribute(f, field);
        } else if (p.currentToken() == JsonToken.START_ARRAY) {
            p.skipChildren();
        } else if (keep(f) || field.equals("isImplicit")) {
            f.node.attrs.put(field, p.getText());
        }
    }

    private void objectAttribute(Frame f, String field) throws IOException {
        String as = alias(field);
        if (as == null || !keep(f)) {
            p.skipChildren();
            return;
        }
        String value = member(field.equals("type") ? "qualType" : "name");
        if (!value.isEmpty()) f.node.attrs.put(as, value);
    }

    private static String alias(String field) {
        return switch (field) {
            case "type" -> "type";
            case "referencedDecl", "decl" -> "ref";
            case "interface" -> "interface";
            default -> null;
        };
    }

    private String member(String key) throws IOException {
        String value = "";
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            if (field.equals(key) && p.currentToken().isScalarValue()) value = p.getText();
            else p.skipChildren();
        }
        return value;
    }

    private void register(AstNode n) {
        if (!RECORDS.contains(n.kind) || n.name.isEmpty() || n.id.isEmpty()) return;
        List<String> chain = new ArrayList<>();
        scopes.descendingIterator().forEachRemaining(chain::add);
        chain.add(n.name);
        records.putIfAbsent(n.id, String.join(".", chain));
    }
}
