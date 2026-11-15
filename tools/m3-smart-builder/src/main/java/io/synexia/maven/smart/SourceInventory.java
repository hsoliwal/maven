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
                if (name.endsWith(".java") || name.endsWith(".c") || name.endsWith(".cc")
                        || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".hpp")) {
                    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                        throw new IOException("source file not regular or exceeds 1 MiB");
                    }
                    eligible.add(file);
                    if (eligible.size() > MAX_FILES) throw new IOException("source inventory exceeds 2000 files");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        eligible.sort(Comparator.comparing(p -> realRoot.relativize(p).toString().replace('\\', '/')));
        int javaFiles = 0, nativeFiles = 0, nativeKeywordMarkers = 0, jniExports = 0;
        long size = 0;
        MessageDigest digest = Hashes.digest();
        for (Path file : eligible) {
            if (Files.isSymbolicLink(file)) throw new IOException("source became a symlink");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("source changed during inventory");
            String relative = realRoot.relativize(file).toString().replace('\\','/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(Hashes.sha256(bytes).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte)'\n');
            size += bytes.length;
            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".java")) javaFiles++;
            else nativeFiles++;
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            nativeKeywordMarkers += countLiteral(text, " native ");
            jniExports += countLiteral(text, "JNIEXPORT");
        }
        return new SourceInventory(HexFormat.of().formatHex(digest.digest()), eligible.size(), javaFiles,
                nativeFiles, size, nativeKeywordMarkers, jniExports);
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
