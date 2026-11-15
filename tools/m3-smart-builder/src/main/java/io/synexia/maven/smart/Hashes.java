package io.synexia.maven.smart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 receipts are computed from bytes rather than filenames or timestamps. */
final class Hashes {
    private Hashes() { }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    static String sha256(Path path) throws IOException {
        MessageDigest d = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) d.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
}
