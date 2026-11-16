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

package io.synexia.maven.smart;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Content-verified, no-overwrite artifact cache; never executes downloaded bytes. */
public final class ArtifactStore {
    @FunctionalInterface
    public interface Transfer { InputStream open(ArtifactManifest.Entry entry) throws IOException; }
    private final Path cache;
    private final Transfer transfer;

    public ArtifactStore(Path approvedExistingCache, Transfer transfer) throws IOException {
        if (approvedExistingCache == null || !approvedExistingCache.isAbsolute()
                || !Files.isDirectory(approvedExistingCache, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(approvedExistingCache)
                || approvedExistingCache.getParent() == null)
            throw new IOException("cache must be an existing approved absolute writable directory");
        this.cache = approvedExistingCache.toRealPath(LinkOption.NOFOLLOW_LINKS);
        this.transfer = java.util.Objects.requireNonNull(transfer);
    }
    public static Transfer httpsTransfer() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15)).build();
        return entry -> {
            HttpRequest req = HttpRequest.newBuilder(entry.uri()).timeout(Duration.ofMinutes(30))
                    .header("Accept", "application/octet-stream").GET().build();
            try {
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    res.body().close();
                    throw new IOException("artifact HTTP status " + res.statusCode());
                }
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("artifact transfer interrupted", e);
            }
        };
    }
    public Path verifyOrAcquire(ArtifactManifest.Entry entry, boolean offline) throws IOException {
        Path target = cache.resolve(entry.cacheName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            check(target, entry);
            return target;
        }
        if (offline) throw new IOException("offline artifact missing: " + entry.id());
        Path part = Files.createTempFile(cache, ".m3-download-", ".part");
        try {
            MessageDigest digest = Hashes.digest();
            long total = 0L;
            try (InputStream source = transfer.open(entry);
                 var output = Files.newOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    if (n == 0) continue;
                    if (total > entry.maxBytes() - n) throw new IOException("artifact exceeded byte bound");
                    total += n;
                    digest.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
            }
            if (!entry.sha256().equals(HexFormat.of().formatHex(digest.digest())))
                throw new IOException("artifact checksum mismatch");
            try { Files.createLink(target, part); }
            catch (FileAlreadyExistsException concurrent) { check(target, entry); }
            return target;
        } finally { Files.deleteIfExists(part); }
    }
    private static void check(Path file, ArtifactManifest.Entry entry) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > entry.maxBytes()
                || !Hashes.sha256(file).equals(entry.sha256()))
            throw new IOException("corrupt cached artifact: " + entry.id());
    }
}
