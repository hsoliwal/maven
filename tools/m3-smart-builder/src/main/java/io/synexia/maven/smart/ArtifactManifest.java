// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict opt-in manifest. A declared URL cannot originate from model output. */
public final class ArtifactManifest {
    public record Entry(String id, String profile, String kind, URI uri, String sha256, long maxBytes) {
        public String cacheName() { return sha256 + (kind.equals("model-gguf") ? ".gguf" : ".bin"); }
    }
    private static final String HEADER = "id\tprofile\tkind\turl\tsha256\tmaxBytes\tlicenseApproved";
    private final List<Entry> entries;
    private final String sha256;

    private ArtifactManifest(List<Entry> entries, String sha256) {
        this.entries = List.copyOf(entries);
        this.sha256 = sha256;
    }
    public static ArtifactManifest empty() { return new ArtifactManifest(List.of(), ""); }
    public String digest() { return sha256; }
    public List<Entry> all() { return entries; }
    public List<Entry> selected(String profile) {
        if (profile == null || !profile.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("invalid profile");
        return entries.stream().filter(x -> x.profile().equals(profile)).toList();
    }
    public static ArtifactManifest load(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) throw new IOException("manifest missing or above 1 MiB");
        byte[] bytes = Files.readAllBytes(path);
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) throw new IOException("invalid manifest header");
        if (lines.length > 1026) throw new IOException("too many manifest rows");
        Set<String> seen = new HashSet<>();
        List<Entry> list = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 7) throw new IOException("manifest row " + (i + 1) + ": expected seven columns");
            String id = fields[0], profile = fields[1], kind = fields[2];
            if (!id.matches("[a-z][a-z0-9-]{0,63}") || !profile.matches("[a-z][a-z0-9-]{0,63}"))
                throw new IOException("invalid artifact ID/profile at row " + (i + 1));
            if (!Set.of("model-gguf", "native-bin", "test-data").contains(kind)) throw new IOException("invalid artifact kind");
            if (!seen.add(profile + "\u0000" + id)) throw new IOException("duplicate artifact identifier within profile");
            if (!fields[4].matches("[0-9a-f]{64}")) throw new IOException("invalid SHA-256");
            if (!"true".equals(fields[6])) throw new IOException("artifact license must be explicitly approved");
            final long maximum;
            try { maximum = Long.parseLong(fields[5]); }
            catch (NumberFormatException e) { throw new IOException("invalid artifact byte limit", e); }
            if (maximum < 1 || maximum > 68_719_476_736L) throw new IOException("artifact byte limit outside 1..64 GiB");
            URI uri;
            try { uri = URI.create(fields[3]); }
            catch (IllegalArgumentException e) { throw new IOException("invalid artifact URL", e); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || uri.getHost().matches("[0-9.]+") || uri.getHost().contains(":"))
                throw new IOException("artifact URL must be public-host HTTPS without credentials/fragment/IP literal");
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || !host.contains(".")) throw new IOException("unapproved artifact host");
            list.add(new Entry(id, profile, kind, uri, fields[4], maximum));
        }
        list.sort(Comparator.comparing(Entry::profile).thenComparing(Entry::id));
        return new ArtifactManifest(list, Hashes.sha256(bytes));
    }
}
