package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Bounded deterministic Java/JNI source inventory without emitting source text or names. */
public record SourceInventory(String digest, int files, int javaFiles, int nativeFiles,
                              long bytes, int nativeKeywordMarkers, int jniExports) {
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final int MAX_FILES = 2_000;
    public static SourceInventory inspect(Path projectBase, Path sourceRoot) throws IOException {
        return inventory(projectBase, sourceRoot, false).summary();
    }

    record FileFact(String path, String sha256, int bytes) { }
    record Selected(SourceInventory summary, String sourceRoot, List<FileFact> files) { }

    /** Complete selected-file inventory for preparation binding, including unsupported assets. */
    static Selected inspectSelected(Path projectBase, Path sourceRoot) throws IOException {
        return inventory(projectBase, sourceRoot, true);
    }

    private static Selected inventory(Path projectBase, Path sourceRoot, boolean allFiles) throws IOException {
        Path lexicalBase = projectBase.toAbsolutePath().normalize();
        Path lexicalRoot = sourceRoot.toAbsolutePath().normalize();
        if (!lexicalRoot.startsWith(lexicalBase) || lexicalRoot.equals(lexicalBase)) {
            throw new IOException("source root must be inside the project");
        }
        Path component = lexicalBase;
        for (Path segment : lexicalBase.relativize(lexicalRoot)) {
            component = component.resolve(segment);
            if (Files.isSymbolicLink(component)) throw new IOException("source parent contains symbolic link");
        }
        Path realBase = lexicalBase.toRealPath();
        Path realRoot = lexicalRoot.toRealPath();
        if (!realRoot.startsWith(realBase) || !Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(realRoot)) throw new IOException("source root must be an approved ordinary directory");
        List<Path> eligible = new ArrayList<>();
        final int[] visitedDirectories = {0};
        Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("symbolic link in source tree");
                if (++visitedDirectories[0] > 8192) throw new IOException("source inventory exceeds 8192 directories");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("symbolic link in source tree");
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (allFiles || name.endsWith(".java") || name.endsWith(".c") || name.endsWith(".cc")
                        || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".hpp")) {
                    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                        throw new IOException("source file not regular or exceeds 1 MiB");
                    }
                    if (allFiles) safeRelative(realBase.relativize(file));
                    eligible.add(file);
                    if (eligible.size() > MAX_FILES) throw new IOException("source inventory exceeds 2000 files");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        eligible.sort(Comparator.comparing(p -> realRoot.relativize(p).toString().replace('\\', '/')));
        int javaFiles = 0, nativeFiles = 0, nativeKeywordMarkers = 0, jniExports = 0;
        long size = 0;
        List<FileFact> facts = new ArrayList<>();
        MessageDigest digest = Hashes.digest();
        for (Path file : eligible) {
            if (Files.isSymbolicLink(file)) throw new IOException("source became a symlink");
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes((int) MAX_FILE_BYTES + 1); }
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("source changed during inventory");
            String relative = realRoot.relativize(file).toString().replace('\\','/');
            String hash = Hashes.sha256(bytes);
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(hash.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte)'\n');
            size += bytes.length;
            if (allFiles && size > 16_000_000L) throw new IOException("selected source exceeds 16 MB");
            facts.add(new FileFact(relative, hash, bytes.length));
            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".java")) javaFiles++;
            else if (!allFiles || List.of(".c", ".cc", ".cpp", ".h", ".hpp").stream().anyMatch(fileName::endsWith)) nativeFiles++;
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            nativeKeywordMarkers += countLiteral(text, " native ");
            jniExports += countLiteral(text, "JNIEXPORT");
        }
        return new Selected(new SourceInventory(HexFormat.of().formatHex(digest.digest()), eligible.size(), javaFiles,
                nativeFiles, size, nativeKeywordMarkers, jniExports), realBase.relativize(realRoot).toString().replace('\\', '/'), List.copyOf(facts));
    }

    private static void safeRelative(Path path) throws IOException {
        for (Path part : path) {
            String name = part.toString();
            if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IOException("ambiguous selected source path");
        }
    }
    private static int countLiteral(String s, String needle) {
        int n=0, at=0;
        while ((at=s.indexOf(needle,at)) >= 0) { n++; at += needle.length(); }
        return n;
    }
    public String json() {
        return "{\"digest\":" + TinyJson.quote(digest)
                + ",\"files\":" + files + ",\"javaFiles\":" + javaFiles
                + ",\"nativeFiles\":" + nativeFiles + ",\"bytes\":" + bytes
                + ",\"nativeKeywordMarkers\":" + nativeKeywordMarkers
                + ",\"jniExportMarkers\":" + jniExports + "}";
    }
}
