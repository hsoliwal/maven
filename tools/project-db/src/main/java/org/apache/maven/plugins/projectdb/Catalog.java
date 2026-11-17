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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The serialized forms kept in the store: the catalog of one build (which source, at which content hash, parsed in
 * which build), the build list, and chunk lists. All are UTF-8 TSV with escaped fields, sorted, so equal content gives
 * equal bytes and therefore the same M3IndexDB blob.
 */
final class Catalog {
    /** One catalog row: the source, its content hash, the chunk artifact holding its chunks, its first build. */
    record Entry(String key, String module, String path, String kind, String sha, String build) {
        String chunks() {
            return chunksArtifact(sha);
        }
    }

    /** One build: sequence number, id, green or red, and how many sources were parsed and reused. */
    record Build(int sequence, String id, String status, int sources, int parsed, int reused) {}

    private Catalog() {}

    static String chunksArtifact(String sha) {
        return "chunks/" + Source.PARSER_VERSION + "/" + sha;
    }

    static byte[] encodeEntries(Map<String, Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry e : new TreeMap<>(entries).values()) {
            out.append(row(e.key(), e.module(), e.path(), e.kind(), e.sha(), e.build()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, Entry> decodeEntries(byte[] bytes) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        for (String[] c : rows(bytes, 6)) entries.put(c[0], new Entry(c[0], c[1], c[2], c[3], c[4], c[5]));
        return entries;
    }

    static byte[] encodeBuilds(List<Build> builds) {
        StringBuilder out = new StringBuilder();
        for (Build b : builds) {
            out.append(row(Integer.toString(b.sequence()), b.id(), b.status(), Integer.toString(b.sources()),
                    Integer.toString(b.parsed()), Integer.toString(b.reused())));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<Build> decodeBuilds(byte[] bytes) throws IOException {
        List<Build> builds = new ArrayList<>();
        for (String[] c : rows(bytes, 6)) {
            builds.add(new Build(Integer.parseInt(c[0]), c[1], c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                    Integer.parseInt(c[5])));
        }
        return builds;
    }

    static byte[] encodeChunks(List<Chunk> chunks) {
        StringBuilder out = new StringBuilder();
        for (Chunk c : chunks) {
            StringBuilder facets = new StringBuilder();
            c.facets().forEach((k, v) -> facets.append(percent(k)).append('=').append(percent(v)).append(';'));
            out.append(row(c.kind(), c.module(), c.file(), Integer.toString(c.line()), c.text(), facets.toString(),
                    c.build()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<Chunk> decodeChunks(byte[] bytes) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        for (String[] c : rows(bytes, 7)) {
            chunks.add(new Chunk(c[0], c[1], c[2], Integer.parseInt(c[3]), c[4], facets(c[5]), c[6]));
        }
        return chunks;
    }

    private static Map<String, String> facets(String encoded) {
        Map<String, String> facets = new TreeMap<>();
        for (String pair : encoded.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                facets.put(unpercent(pair.substring(0, equals)), unpercent(pair.substring(equals + 1)));
            }
        }
        return facets;
    }

    private static String percent(String value) {
        return value.replace("%", "%25").replace(";", "%3B").replace("=", "%3D");
    }

    private static String unpercent(String value) {
        return value.replace("%3B", ";").replace("%3D", "=").replace("%25", "%");
    }

    private static String row(String... fields) {
        StringBuilder row = new StringBuilder();
        for (String field : fields) row.append(row.length() == 0 ? "" : "\t").append(Text.escape(field));
        return row.append('\n').toString();
    }

    /** Rows with exactly {@code columns} unescaped fields; any other shape is a corrupt artifact. */
    private static List<String[]> rows(byte[] bytes, int columns) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != columns) throw new IOException("project database row has " + fields.length + " fields");
            for (int i = 0; i < fields.length; i++) fields[i] = Text.unescape(fields[i]);
            rows.add(fields);
        }
        return rows;
    }

    /** A store directory with no catalog yet. */
    static boolean empty(Path directory) {
        return !java.nio.file.Files.isDirectory(directory);
    }
}
