/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Content stamps over a task's definition and declared inputs, for opt-in incremental runs. Never timestamps. */
final class InputStamps {
    static final String DIRECTORY = ".m3/polyglot-stamps";

    private InputStamps() {}

    /** SHA-256 over the task definition and the bytes of every declared input; directories recursively, sorted. */
    static String stamp(TaskManifest.Task task, Path root) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "task");
        update(digest, task.name());
        update(digest, task.tool());
        update(digest, task.workingDir());
        for (String argument : task.arguments()) {
            update(digest, "arg");
            update(digest, argument);
        }
        for (String output : task.outputs()) {
            update(digest, "output");
            update(digest, output);
        }
        for (String input : task.inputs()) {
            Path path = root.resolve(input).normalize();
            update(digest, "input");
            update(digest, input);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(files::add);
                }
                for (Path file : files) {
                    update(digest, root.relativize(file).toString().replace('\\', '/'));
                    content(digest, file);
                }
            } else {
                content(digest, path);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** True when the recorded stamp equals the current one and every declared output exists. */
    static boolean upToDate(TaskManifest.Task task, Path root, String current) throws IOException {
        Path file = file(task, root);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!current.equals(Files.readString(file, StandardCharsets.UTF_8).strip())) return false;
        for (String output : task.outputs()) {
            if (!Files.exists(root.resolve(output).normalize(), LinkOption.NOFOLLOW_LINKS)) return false;
        }
        return true;
    }

    /** Records the stamp of a task that completed and produced its declared outputs. */
    static void record(TaskManifest.Task task, Path root, String current) throws IOException {
        Path file = file(task, root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, current + "\n", StandardCharsets.UTF_8);
    }

    static Path file(TaskManifest.Task task, Path root) {
        return root.resolve(DIRECTORY).resolve(task.name() + ".sha256");
    }

    private static void content(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) digest.update(buffer, 0, n);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
