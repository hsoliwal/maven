// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors
package org.apache.maven.cling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-class, opt-in Maven launch preflight for a SHA-pinned GitHub PR patch.
 * Nothing is downloaded or changed for an ordinary invocation without an enabled
 * project-local .m3/patch-boot.properties. No Maven plugin is installed or rebuilt.
 * A nontrivial/overlapping patch is held for the Synexia OpenRewrite hunk resolver.
 */
public final class M3GitHubPatchBootstrap {
    private static final String MANIFEST = ".m3/patch-boot.properties";
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern SHA40 = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern COMMIT = Pattern.compile("(?m)^From ([a-f0-9]{40}) Mon Sep 17 00:00:00 2001\\r?$");
    private static final Pattern DIFF = Pattern.compile("^diff --git a/([^\\t\\r\\n]+) b/([^\\t\\r\\n]+)$");
    private static final int MAX_PATCH_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIFFS = 65536;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private M3GitHubPatchBootstrap() {}

    /** Called by the native Maven 4 CLI entry points, before lifecycle/model loading. */
    static void beforeMaven(String[] args) throws IOException {
        Path config = findManifest(Path.of(System.getProperty("user.dir")));
        if (config == null) {
            if (setting(args, "m3.patch.apply") || setting(args, "m3.patch.check")) {
                throw new IOException("M3_PATCH_MANIFEST_MISSING: add an exact source-pinned " + MANIFEST);
            }
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        }
        boolean apply = setting(args, "m3.patch.apply");
        boolean check = apply || setting(args, "m3.patch.check") || "true".equals(properties.getProperty("autoCheck"));
        if (!check) return; // local manifest discovery costs no network or mutation
        Plan plan = Plan.from(properties);
        Path root = config.getParent().getParent();
        verifyLocalRepository(root, plan);
        String observedHead = remoteHead(root, plan);
        if (!plan.head().equals(observedHead)) throw new IOException("M3_PATCH_HEAD_DRIFT");
        byte[] patch = download(plan);
        String digest = sha256(patch);
        if (!plan.sha256().equals(digest)) throw new IOException("M3_PATCH_SHA256_DRIFT");
        MboxReceipt mbox = inspectMbox(patch, plan.head());
        for (String changed : mbox.paths()) requirePhysicalPath(root, changed);
        Path temporary = Files.createTempFile("m3-github-pr-", ".patch");
        try {
            Files.write(temporary, patch);
            // The Git preflight is the mechanical donor. Semantic overlaps are
            // processed only by the canonical Synexia per-file/hunk recipe.
            run(root, "git", "apply", "--check", "--index", temporary.toString());
            if (apply) {
                verifyClean(root);
                if (!plan.head().equals(remoteHead(root, plan))) throw new IOException("M3_PATCH_HEAD_MOVED_AT_APPLY");
                if (!plan.base().equals(run(root, "git", "rev-parse", "HEAD").trim())) {
                    throw new IOException("M3_PATCH_LOCAL_HEAD_MOVED_AT_APPLY");
                }
                run(root, "git", "apply", "--index", temporary.toString());
            }
            System.out.println("M3_PR_PATCH_" + (apply ? "APPLIED" : "CHECKED")
                    + " repo=" + plan.repository() + " pr=" + plan.pr()
                    + " source=" + plan.head() + " sha256=" + digest
                    + " commits=" + mbox.commits() + " fileSections=" + mbox.fileSections());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path findManifest(Path current) {
        for (Path directory = current.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path file = directory.resolve(MANIFEST);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    static boolean setting(String[] args, String key) {
        if ("true".equals(System.getProperty(key))) return true;
        for (String arg : args) {
            if (("-D" + key + "=true").equals(arg)) return true;
        }
        return false;
    }

    record Plan(String repository, int pr, String head, String base, String sha256) {
        static Plan from(Properties p) throws IOException {
            String repository = p.getProperty("repository", "");
            String pr = p.getProperty("pr", "");
            String head = p.getProperty("head", "");
            String base = p.getProperty("base", "");
            String sha = p.getProperty("patchSha256", "");
            if (!REPOSITORY.matcher(repository).matches() || repository.contains("..")
                    || !pr.matches("[1-9][0-9]{0,8}") || !SHA40.matcher(head).matches()
                    || !SHA40.matcher(base).matches() || !SHA256.matcher(sha).matches()) {
                throw new IOException("M3_PATCH_INVALID_PINNED_MANIFEST");
            }
            return new Plan(repository, Integer.parseInt(pr), head, base, sha);
        }

        URI patchUri() { return URI.create("https://github.com/" + repository + "/pull/" + pr + ".patch"); }
    }

    record MboxReceipt(int commits, int fileSections, String finalHead, List<String> paths) {}

    static MboxReceipt inspectMbox(byte[] patch, String expectedHead) throws IOException {
        if (patch.length == 0 || patch.length > MAX_PATCH_BYTES || !SHA40.matcher(expectedHead).matches()) {
            throw new IOException("M3_PATCH_INVALID_SIZE_OR_HEAD");
        }
        String text = new String(patch, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8), patch)) {
            throw new IOException("M3_PATCH_NON_UTF8");
        }
        List<String> commits = new ArrayList<>();
        Matcher matches = COMMIT.matcher(text);
        while (matches.find()) {
            if (commits.contains(matches.group(1))) throw new IOException("M3_PATCH_DUPLICATE_COMMIT");
            commits.add(matches.group(1));
        }
        if (commits.isEmpty() || !expectedHead.equals(commits.get(commits.size() - 1))) {
            throw new IOException("M3_PATCH_MBOX_HEAD_MISMATCH");
        }
        int sections = 0;
        Set<String> paths = new LinkedHashSet<>();
        for (String line : text.split("\\n", -1)) {
            if (line.startsWith("diff --git ")) {
                Matcher file = DIFF.matcher(line);
                if (!file.matches() || !file.group(1).equals(file.group(2)) || !safePath(file.group(1))) {
                    throw new IOException("M3_PATCH_UNSAFE_FILE_SECTION");
                }
                paths.add(file.group(1));
                if (++sections > MAX_DIFFS) throw new IOException("M3_PATCH_TOO_MANY_SECTIONS");
            } else if (line.startsWith("deleted file mode ") || line.startsWith("new file mode 120000")
                    || line.startsWith("new file mode 160000") || line.startsWith("new file mode 100755")
                    || line.startsWith("old mode ") || line.startsWith("new mode ")
                    || line.startsWith("rename from ") || line.startsWith("rename to ")
                    || line.startsWith("copy from ") || line.startsWith("copy to ")
                    || line.startsWith("GIT binary patch") || line.startsWith("Binary files ")
                    || line.startsWith("+++ /dev/null") || line.startsWith("--- a/.git/")) {
                throw new IOException("M3_PATCH_UNSUPPORTED_DESTRUCTIVE_OR_OPAQUE");
            }
        }
        if (sections == 0) throw new IOException("M3_PATCH_MISSING_FILE_HUNKS");
        return new MboxReceipt(commits.size(), sections, commits.get(commits.size() - 1), List.copyOf(paths));
    }

    static boolean safePath(String name) {
        if (name.isBlank() || name.length() > 2048 || name.startsWith("/") || name.contains("\\")
                || name.contains("\"" ) || name.indexOf('\0') >= 0) return false;
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || ".git".equals(segment)
                    || segment.contains(":") || segment.chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private static void requirePhysicalPath(Path root, String changed) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path target = absoluteRoot.resolve(changed).normalize();
        if (!target.startsWith(absoluteRoot)) throw new IOException("M3_PATCH_ESCAPE");
        for (Path current = target; current != null && !current.equals(absoluteRoot);
                current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("M3_PATCH_SYMLINK_PATH");
        }
    }

    private static void verifyLocalRepository(Path root, Plan plan) throws IOException {
        Path physicalRoot = root.toRealPath();
        if (!physicalRoot.toString().equals(run(root, "git", "rev-parse", "--show-toplevel").trim())) {
            throw new IOException("M3_PATCH_NOT_AT_REPOSITORY_ROOT");
        }
        String actual = run(root, "git", "rev-parse", "HEAD").trim();
        if (!plan.base().equals(actual)) throw new IOException("M3_PATCH_LOCAL_BASE_DRIFT");
        String origin = run(root, "git", "remote", "get-url", "origin").trim();
        String expected = plan.repository().toLowerCase(Locale.ROOT);
        String normalized = origin.toLowerCase(Locale.ROOT);
        boolean allowed = normalized.equals("https://github.com/" + expected + ".git")
                || normalized.equals("https://github.com/" + expected)
                || normalized.equals("git@github.com:" + expected + ".git")
                || normalized.equals("git@github.com:" + expected);
        if (!allowed) throw new IOException("M3_PATCH_REPOSITORY_ORIGIN_MISMATCH");
    }

    private static void verifyClean(Path root) throws IOException {
        if (!run(root, "git", "status", "--porcelain", "--untracked-files=no").isBlank()) {
            throw new IOException("M3_PATCH_DIRTY_TRACKED_WORKTREE");
        }
    }

    private static String remoteHead(Path root, Plan plan) throws IOException {
        String ref = "refs/pull/" + plan.pr() + "/head";
        String output = run(root, "git", "ls-remote", "https://github.com/" + plan.repository() + ".git", ref).trim();
        if (!output.matches("[0-9a-f]{40}\\s+" + Pattern.quote(ref))) {
            throw new IOException("M3_PATCH_REMOTE_HEAD_UNAVAILABLE");
        }
        return output.substring(0, 40);
    }

    private static byte[] download(Plan plan) throws IOException {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
            URI current = plan.patchUri();
            for (int hop = 0; hop < 4; hop++) {
                requireHost(current);
                HttpRequest request = HttpRequest.newBuilder(current).timeout(TIMEOUT)
                        .header("User-Agent", "M3JDK21-Pinned-Recipe-Bootstrap")
                        .GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int code = response.statusCode();
                    if (code == 301 || code == 302 || code == 307 || code == 308) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IOException("M3_PATCH_REDIRECT_MISSING_LOCATION"));
                        current = current.resolve(location);
                        continue;
                    }
                    if (code != 200) throw new IOException("M3_PATCH_FETCH_HTTP_" + code);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = body.read(buffer)) != -1) {
                        if ((long) bytes.size() + count > MAX_PATCH_BYTES) {
                            throw new IOException("M3_PATCH_TOO_LARGE");
                        }
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            }
            throw new IOException("M3_PATCH_REDIRECT_LIMIT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("M3_PATCH_INTERRUPTED", interrupted);
        }
    }

    private static void requireHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !("github.com".equals(host) || "patch-diff.githubusercontent.com".equals(host))) {
            throw new IOException("M3_PATCH_UNTRUSTED_REDIRECT");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String run(Path root, String... argv) throws IOException {
        Path log = Files.createTempFile("m3-git-command-", ".log");
        try {
            ProcessBuilder command = new ProcessBuilder(argv)
                    .directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            command.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = command.start();
            if (!process.waitFor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("M3_PATCH_COMMAND_TIMEOUT: " + argv[1]);
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(log)) {
                bytes = input.readNBytes(16 * 1024);
            }
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("M3_PATCH_HOLD_FOR_CANONICAL_HUNK_RESOLVER: " + argv[1]
                        + " " + output.strip());
            }
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("M3_PATCH_INTERRUPTED", interrupted);
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
