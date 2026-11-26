// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator-curated, hash-pinned OpenRewrite recipes; never model-generated commands. */
final class RecipeCatalog {
    static final String HEADER = "recipe_id\tpattern\tscope\topenrewrite_recipe\timplementation_sha256";
    record Entry(String id, String pattern, String scope, String recipe, String implementationSha256) {
        String json() {
            return "{\"recipeId\":" + TinyJson.quote(id) + ",\"pattern\":" + TinyJson.quote(pattern)
                    + ",\"scope\":" + TinyJson.quote(scope) + ",\"openRewriteRecipe\":" + TinyJson.quote(recipe)
                    + ",\"implementationSha256\":" + TinyJson.quote(implementationSha256) + "}";
        }
    }
    private final String digest;
    private final List<Entry> entries;

    private RecipeCatalog(String digest, List<Entry> entries) {
        this.digest = digest;
        this.entries = List.copyOf(entries);
    }

    static RecipeCatalog load(Path base, Path file) throws IOException {
        byte[] bytes = readLocal(base, file, 65_536);
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0 || !text.endsWith("\n"))
            throw new IOException("invalid recipe catalog line format");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length < 2 || lines.length > 129 || !HEADER.equals(lines[0]))
            throw new IOException("invalid recipe catalog schema or size");
        List<Entry> entries = new ArrayList<>();
        String previous = "";
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length != 5 || !fields[0].matches("[a-z][a-z0-9-]{0,63}")
                    || fields[0].compareTo(previous) <= 0
                    || !fields[1].matches("[A-Z][A-Z_]{2,63}")
                    || !Set.of("FILE", "PACKAGE", "MODULE", "REACTOR", "API").contains(fields[2])
                    || !fields[3].matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                    || fields[3].length() > 256 || !fields[4].matches("[0-9a-f]{64}"))
                throw new IOException("invalid or unsorted recipe catalog row");
            entries.add(new Entry(fields[0], fields[1], fields[2], fields[3], fields[4]));
            previous = fields[0];
        }
        return new RecipeCatalog(Hashes.sha256(bytes), entries);
    }

    List<Entry> applicable(Map<?, ?> counts) throws IOException {
        List<Entry> available = new ArrayList<>();
        for (Entry entry : entries) {
            Object count = counts.get(entry.pattern());
            if (count == null) continue;
            if (!(count instanceof String value) || !value.matches("0|[1-9][0-9]{0,9}"))
                throw new IOException("invalid pattern count");
            if (Long.parseLong(value) > 0) available.add(entry);
        }
        return List.copyOf(available);
    }

    String digest() { return digest; }
    List<Entry> entries() { return entries; }

    /** All caller inputs remain in the checkout, with symlink components forbidden. */
    static byte[] readLocal(Path base, Path file, int maximum) throws IOException {
        Path root = base.toAbsolutePath().normalize();
        Path target = root.resolve(file).toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root) || !Files.isDirectory(root))
            throw new IOException("input outside checkout");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("input symlink forbidden");
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) == 0 || Files.size(target) > maximum)
            throw new IOException("input missing or too large");
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > maximum) throw new IOException("input grew during read");
        return bytes;
    }
}
