/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Eclipse Compiler for Java as an M3 compiler, {@code @m3:ecj:compiler:java}: ECJ's own batch compiler
 * ({@value #MAIN}) runs from a separately obtained ECJ jar on the selected JDK. ECJ (EPL-2.0) is never bundled,
 * downloaded or reimplemented here. Task arguments are javac-style and mapped by {@link EcjArguments} as
 * plexus-compiler-eclipse maps maven-compiler-plugin settings for {@code compilerId=eclipse}.
 *
 * <p>The jar is, first found: a {@code --m3-ecj-jar=<path>} task argument (relative to the workspace), the
 * {@value #JAR_PROPERTY} system property, or ECJ on this plugin's own class path (an {@code org.eclipse.jdt:ecj}
 * plugin dependency, as with maven-compiler-plugin). A {@code --m3-ecj-sha256=<hex>} argument pins its bytes.
 * Pass ECJ's {@code -log <file>.xml} to keep its diagnostics for the analyzer report adapter's ECJ format.
 */
public final class EcjCompilerProvider implements M3ToolProvider {
    static final String MAIN = "org.eclipse.jdt.internal.compiler.batch.Main";
    static final String JAR_PROPERTY = "m3.polyglot.ecj.jar";
    private static final String MAIN_RESOURCE = MAIN.replace('.', '/') + ".class";

    @Override
    public String id() {
        return "ecj";
    }

    @Override
    public Set<M3ToolCapability> capabilities() {
        return Set.of(M3ToolCapability.COMPILER);
    }

    @Override
    public Set<String> languages() {
        return Set.of("java");
    }

    @Override
    public List<String> command(M3ToolCapability capability, String language,
            TaskManifest.Task task, Path workspace) throws IOException {
        if (capability != M3ToolCapability.COMPILER || !"java".equals(language)) {
            throw new IOException("ECJ provider only supports Java compilation");
        }
        EcjArguments.Mapped mapped = EcjArguments.map(task.arguments(), workspace.resolve(task.workingDir()));
        Path jar = verified(jar(mapped.jar(), workspace), mapped.sha256());
        List<String> argv = new ArrayList<>(mapped.args().size() + 4);
        argv.addAll(List.of(java(), "-cp", jar.toString(), MAIN));
        argv.addAll(mapped.args());
        return List.copyOf(argv);
    }

    /** The java launcher of the running JDK, which also runs the JDK provider's javac. */
    static String java() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    static Path jar(String argument, Path workspace) throws IOException {
        if (argument != null) return workspace.resolve(argument).normalize();
        String property = System.getProperty(JAR_PROPERTY, "");
        if (!property.isBlank()) return Path.of(property);
        URL main = EcjCompilerProvider.class.getClassLoader().getResource(MAIN_RESOURCE);
        if (main != null && "jar".equals(main.getProtocol())) return jarOf(main);
        throw new IOException("ECJ jar not configured: add the org.eclipse.jdt:ecj plugin dependency, set -D"
                + JAR_PROPERTY + "=<ecj.jar>, or pass " + EcjArguments.JAR + "<ecj.jar>");
    }

    private static Path jarOf(URL main) throws IOException {
        String spec = main.toString();
        try {
            return Path.of(new java.net.URI(spec.substring("jar:".length(), spec.indexOf("!/"))));
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("cannot locate the ECJ jar of " + spec, failure);
        }
    }

    /** The jar must be a regular file; with a pin, its SHA-256 must match. */
    static Path verified(Path jar, String sha256) throws IOException {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !jar.toString().endsWith(".jar")) {
            throw new IOException("ECJ jar is not a regular .jar file: " + jar);
        }
        if (sha256 != null && !sha256.toLowerCase(Locale.ROOT).equals(sha256(jar))) {
            throw new IOException("ECJ jar does not match " + EcjArguments.SHA256 + sha256 + ": " + jar);
        }
        return jar;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65_536];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
