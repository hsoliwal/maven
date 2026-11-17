/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.extensions.clustering;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * The service registrations javac reads from a processor path. A plugin jar must register the javac plugin: javac
 * fails every compilation ({@code plug-in not found}) when {@code -Xplugin:CodeClustering} names a plugin that its
 * processor path does not register, so the jar must hold {@value #SERVICE} listing {@value #PLUGIN_CLASS}, and that
 * class. A jar of the plugin built without the javac plugin (an older or another tree's {@code 0.1.0-SNAPSHOT}) has
 * neither. The class must be compiled for the running Java or an older one, and the jar's path must not hold the path
 * separator. Results are cached by path, size and modification time. Class path elements are checked for annotation
 * processor registrations ({@value #PROCESSOR_SERVICE}).
 */
final class PluginJar {
    static final String SERVICE = "META-INF/services/com.sun.source.util.Plugin";
    static final String PLUGIN_CLASS = "org.apache.maven.plugins.clustering.InlineClustering";
    static final String PROCESSOR_SERVICE = "META-INF/services/javax.annotation.processing.Processor";
    private static final Map<String, Optional<String>> CHECKED = new ConcurrentHashMap<>();
    /** A class file starts with the magic number, the minor and the major version: 8 bytes. */
    private static final int CLASS_HEAD = 8;
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int MAJOR_OFFSET = 6;
    /** Class file major version minus this is the Java feature release (61 is Java 17). */
    private static final int MAJOR_JAVA_OFFSET = 44;

    private PluginJar() { }

    /**
     * Why the jar cannot serve as the javac plugin; empty when it can. Its path must not hold the path separator (a
     * processor path is split at it), and the plugin class must be compiled for the running Java or an older one.
     */
    static Optional<String> problem(Path jar) {
        if (jar.toString().contains(File.pathSeparator)) {
            return Optional.of(jar + " contains the path separator " + File.pathSeparator + " (a processor path splits at it)");
        }
        if (!Files.isRegularFile(jar)) return Optional.of(jar + " is not a file");
        try {
            String key = jar.toAbsolutePath() + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
            return CHECKED.computeIfAbsent(key, k -> inspect(jar));
        } catch (IOException e) {
            return Optional.of(jar + " cannot be read: " + e.getMessage());
        }
    }

    private static Optional<String> inspect(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry service = file.getJarEntry(SERVICE);
            boolean registered = service != null && registrations(file, service).contains(PLUGIN_CLASS);
            JarEntry plugin = file.getJarEntry(PLUGIN_CLASS.replace('.', '/') + ".class");
            String problem = jar + " has no javac plugin CodeClustering (" + SERVICE + " listing " + PLUGIN_CLASS + ")";
            return registered && plugin != null ? classVersion(jar, file, plugin) : Optional.of(problem);
        } catch (IOException e) {
            return Optional.of(jar + " cannot be read: " + e.getMessage());
        }
    }

    /** Why the plugin class cannot load in this Java: not a class file, or compiled for a newer Java. */
    private static Optional<String> classVersion(Path jar, JarFile file, JarEntry plugin) throws IOException {
        byte[] head;
        try (InputStream in = file.getInputStream(plugin)) {
            head = in.readNBytes(CLASS_HEAD);
        }
        ByteBuffer bytes = ByteBuffer.wrap(head);
        if (head.length < CLASS_HEAD || bytes.getInt(0) != CLASS_MAGIC) return Optional.of(jar + " holds a damaged " + PLUGIN_CLASS);
        int java = Short.toUnsignedInt(bytes.getShort(MAJOR_OFFSET)) - MAJOR_JAVA_OFFSET;
        int running = Runtime.version().feature();
        return java > running ? Optional.of(jar + " needs Java " + java + ", Maven runs on Java " + running) : Optional.empty();
    }

    /** The class names a service file lists: one per line, {@code #} comments and blanks left out. */
    private static List<String> registrations(JarFile file, JarEntry service) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(service),
                StandardCharsets.UTF_8))) {
            return reader.lines().map(l -> l.replaceAll("#.*", "").trim()).filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    /** Whether a class path element (a directory or a jar) registers annotation processors. */
    static boolean registersProcessors(Path element) {
        if (Files.isDirectory(element)) return Files.isRegularFile(element.resolve(PROCESSOR_SERVICE));
        if (!Files.isRegularFile(element)) return false;
        try (JarFile file = new JarFile(element.toFile())) {
            return file.getJarEntry(PROCESSOR_SERVICE) != null;
        } catch (IOException e) {
            return true;
        }
    }
}
