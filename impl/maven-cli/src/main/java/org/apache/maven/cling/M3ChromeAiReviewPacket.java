// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Local-only Chrome Prompt API handoff for GitHub hunks rejected by Git.
 * No browser/model is launched by Maven and no recommendation may apply source.
 */
public final class M3ChromeAiReviewPacket {
    //FLLDCIM: RECIPE - opt-in, SHA-pinned, fail-closed file-local AI review custody.
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern SHA40 = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final int MAX_REVIEW_BYTES = 20 * 1024;
    private static final String SUBDIR = "patch-ai-reviews";

    private M3ChromeAiReviewPacket() { }

    /**
     * Writes one immutable, auditable packet only after an explicitly requested
     * Git check has found an unresolved conflict. Never writes changed source.
     */
    static Path write(Path repositoryRoot, String repository, int pr,
                      String base, String head, String patchSha256, byte[] patch)
            throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(patch, "patch");
        if (!REPO.matcher(repository).matches() || repository.contains("..")
                || pr < 1 || !SHA40.matcher(base).matches() || !SHA40.matcher(head).matches()
                || !SHA256.matcher(patchSha256).matches()
                || patch.length == 0 || patch.length > MAX_REVIEW_BYTES
                || !patchSha256.equals(M3GitHubPatchBootstrap.sha256(patch))) {
            throw new IOException("M3_CHROME_AI_PACKET_UNSEALED_OR_TOO_LARGE");
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(patch)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("M3_CHROME_AI_PACKET_NON_UTF8", malformed);
        }
        if (decoded.indexOf('\0') >= 0)
            throw new IOException("M3_CHROME_AI_PACKET_NUL");
        Path root = repositoryRoot.toRealPath();
        Path m3 = root.resolve(".m3");
        if (!Files.isDirectory(m3, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(m3)) {
            throw new IOException("M3_CHROME_AI_PACKET_M3_DIR_REQUIRED");
        }
        Path review = m3.resolve(SUBDIR);
        if (Files.exists(review, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(review, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(review))
                throw new IOException("M3_CHROME_AI_PACKET_UNSAFE_DIR");
        } else {
            Files.createDirectory(review);
        }
        // Keep local source-bearing AI requests out of accidental Git staging.
        Path ignore = review.resolve(".gitignore");
        if (Files.exists(ignore, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(ignore) || !"*\n".equals(Files.readString(ignore)))
                throw new IOException("M3_CHROME_AI_PACKET_IGNORE_DRIFT");
        } else {
            Files.writeString(ignore, "*\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Path file = review.resolve("pr-" + pr + "-" + patchSha256.substring(0, 12) + ".json");
        String json = "{\n"
                + "  \"schema\":\"M3-CHROME-HUNK-REVIEW/1\",\n"
                + "  \"status\":\"HOLD_GIT_APPLY_CONFLICT\",\n"
                + "  \"repository\":\"" + repository + "\",\n"
                + "  \"pr\":" + pr + ",\n"
                + "  \"base\":\"" + base + "\",\n"
                + "  \"head\":\"" + head + "\",\n"
                + "  \"patchSha256\":\"" + patchSha256 + "\",\n"
                + "  \"patchUtf8Bytes\":" + patch.length + ",\n"
                + "  \"patch\":\"" + quote(decoded) + "\",\n"
                + "  \"mutationAuthorized\":false\n"
                + "}\n";
        // CREATE_NEW is essential: no silent replacement of reviewer evidence.
        Files.writeString(file, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return file;
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
