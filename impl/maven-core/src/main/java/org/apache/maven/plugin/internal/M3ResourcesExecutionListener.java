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
package org.apache.maven.plugin.internal;

// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0

import javax.inject.Named;
import javax.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.ResourceObservation;
import org.apache.maven.api.services.ResourceObservation.Area;
import org.apache.maven.api.services.ResourceObservation.FileState;
import org.apache.maven.api.services.ResourceObservation.Kind;
import org.apache.maven.api.services.ResourceObservation.ResourceRoot;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in observations around the original Resources plugin, using Maven's existing
 * execution listener and session evidence service. Whole-root observations do not
 * assert selection, filtering-input closure, output ownership or a cache key.
 * Evidence failures do not change the original goal's outcome.
 *
 * <p>Only public configured-Mojo getters are read. In particular the separate private
 * testResources skip field, interpolation internals and user filtering components
 * remain outside this observation contract. No plugin or filtering engine is replaced.
 */
@Named
@Singleton
public final class M3ResourcesExecutionListener extends AbstractEventSpy implements MojoExecutionListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(M3ResourcesExecutionListener.class);
    private static final String ENABLED = "m3.resources.evidence";
    private static final String PLUGIN = "org.apache.maven.plugins:maven-resources-plugin";
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SCAN_BYTES = 256L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VALUES = 4096;
    private static final int MAX_TEXT = 65_536;
    private static final int MAX_CONFIGURATION_BYTES = 4 * 1024 * 1024;
    private static final SessionData.Key<State> KEY =
            SessionData.key(State.class, M3ResourcesExecutionListener.class.getName());

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) {
        if (!selected(event) || !enabled(event)) {
            return;
        }
        Session session = event.getSession().getSession();
        if (session == null) {
            return;
        }
        State state = session.getData().computeIfAbsent(KEY, State::new);
        Pending pending = null;
        try {
            pending = state.reserve(event);
            pending.capture = capture(event, pending.generation);
        } catch (Exception | LinkageError failure) {
            if (pending != null) {
                state.remove(event);
            }
            preserveInterrupt(failure);
            state.warn();
        }
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) {
        Session session = event.getSession().getSession();
        if (session == null) {
            return;
        }
        State state = session.getData().get(KEY);
        Pending pending = state == null ? null : state.remove(event);
        if (pending == null || pending.capture == null) {
            return;
        }
        Path published = null;
        try {
            checkInterrupted();
            Capture capture = pending.capture;
            List<FileState> after = observe(capture.resources, capture.output);
            ResourceObservation observation = new ResourceObservation(
                    capture.identity.contextSha256(),
                    capture.skipRequested,
                    capture.resources,
                    uri(capture.output),
                    capture.before,
                    after,
                    capture.limitations);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            observation.writeTo(bytes);
            byte[] content = bytes.toByteArray();
            if (content.length > ResourceObservation.MAX_BYTES) {
                throw new IOException("resource observation exceeds byte budget");
            }
            M3PluginEvidenceService service = session.getService(M3PluginEvidenceService.class);
            createEvidenceDirectory(capture.destination);
            published = Files.createTempFile(capture.destination, "resources-", ".bin");
            rejectLinks(published);
            try (var output = Channels.newOutputStream(
                    Files.newByteChannel(published, Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))) {
                output.write(content);
            }
            checkInterrupted();
            service.publish(
                    session,
                    new M3PluginEvidenceService.Receipt(
                            capture.identity,
                            published,
                            ResourceObservation.SCHEMA,
                            HexFormat.of().formatHex(sha256().digest(content)),
                            M3PluginEvidenceService.RESOURCES));
            published = null;
        } catch (Exception | LinkageError failure) {
            preserveInterrupt(failure);
            state.warn();
        } finally {
            if (published != null) {
                try {
                    rejectLinks(published);
                    Files.deleteIfExists(published); // Only this producer's unpublished temporary file.
                } catch (IOException ignored) {
                    // No receipt was admitted; do not turn evidence cleanup into a goal failure.
                }
            }
        }
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
        Session session = event.getSession().getSession();
        if (session != null) {
            State state = session.getData().get(KEY);
            if (state != null) {
                state.remove(event);
            }
        }
    }

    @Override
    public void onEvent(Object event) {
        // Checked Mojo failures are reported by the existing lifecycle event
        // path, without a MojoExecutionListener failure callback in core.
        if (event instanceof ExecutionEvent execution && execution.getType() == ExecutionEvent.Type.MojoFailed) {
            Session session = execution.getSession().getSession();
            if (session != null) {
                State state = session.getData().get(KEY);
                if (state != null) {
                    state.removeByExecution(execution.getMojoExecution(), execution.getProject());
                }
            }
        }
    }

    private static boolean selected(MojoExecutionEvent event) {
        MojoExecution execution = event.getExecution();
        return execution != null
                && "org.apache.maven.plugins".equals(execution.getGroupId())
                && "maven-resources-plugin".equals(execution.getArtifactId())
                && Set.of("resources", "testResources", "copy-resources").contains(execution.getGoal());
    }

    private static boolean enabled(MojoExecutionEvent event) {
        return Boolean.parseBoolean(property(event, ENABLED));
    }

    private static String property(MojoExecutionEvent event, String name) {
        MavenSession session = event.getSession();
        String value = session.getUserProperties().getProperty(name);
        if (value == null && event.getProject() != null) {
            value = event.getProject().getProperties().getProperty(name);
        }
        return value == null ? session.getSystemProperties().getProperty(name) : value;
    }

    private static Capture capture(MojoExecutionEvent event, long generation) throws Exception {
        checkInterrupted();
        Mojo mojo = Objects.requireNonNull(event.getMojo(), "configured Mojo");
        Path base = event.getProject().getBasedir().toPath().toAbsolutePath();
        List<ResourceRoot> resources = new ArrayList<>();
        Object configuredResources = invoke(mojo, "getResources");
        if (!(configuredResources instanceof List<?> entries) || entries.size() > ResourceObservation.MAX_ROOTS) {
            throw new IOException("unsupported Resources configuration");
        }
        for (Object entry : entries) {
            if (!(entry instanceof Resource resource) || resource.getDirectory() == null) {
                throw new IOException("unsupported resource root");
            }
            Path path = resolve(base, resource.getDirectory());
            resources.add(new ResourceRoot(
                    uri(path),
                    resource.getTargetPath() == null ? "" : resource.getTargetPath(),
                    resource.isFiltering(),
                    strings(resource.getIncludes()),
                    strings(resource.getExcludes())));
        }
        resources = List.copyOf(resources);
        Object configuredOutput = invoke(mojo, "getOutputDirectory");
        if (!(configuredOutput instanceof File directory)) {
            throw new IOException("unsupported resource output");
        }
        Path output = resolve(base, directory.toString());
        Path destination =
                resolve(base, event.getProject().getBuild().getDirectory()).resolve("m3-resources-evidence");
        rejectOverlap(destination, output);
        for (ResourceRoot resource : resources) {
            rejectOverlap(destination, Path.of(URI.create(resource.directory())));
        }
        rejectLinks(destination);
        Object skip = invoke(mojo, "isSkip");
        if (!(skip instanceof Boolean skipRequested)) {
            throw new IOException("unsupported Resources skip getter");
        }
        List<String> limitations = new ArrayList<>(List.of(
                "SOURCE_SELECTION_UNPROVEN",
                "INPUT_CLOSURE_UNPROVEN",
                "OUTPUT_OWNERSHIP_UNPROVEN",
                "FILESYSTEM_NOT_TRANSACTIONAL"));
        if ("testResources".equals(event.getExecution().getGoal())) {
            limitations.add("TEST_SKIP_UNOBSERVED");
        }
        String context = configurationDigest(event, resources, output, skipRequested);
        MojoExecution execution = event.getExecution();
        M3PluginEvidenceService.Identity identity = new M3PluginEvidenceService.Identity(
                event.getProject().getId(),
                PLUGIN,
                execution.getVersion(),
                execution.getGoal(),
                execution.getExecutionId() == null ? "default-cli" : execution.getExecutionId(),
                context,
                generation);
        return new Capture(
                identity,
                resources,
                output,
                destination,
                skipRequested,
                observe(resources, output),
                List.copyOf(limitations));
    }

    private static String configurationDigest(
            MojoExecutionEvent event, List<ResourceRoot> resources, Path output, boolean skip) throws Exception {
        MessageDigest digest = sha256();
        try (DataOutputStream data =
                new DataOutputStream(new DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))) {
            writeText(data, ResourceObservation.SCHEMA);
            writeText(data, event.getMojo().getClass().getName());
            writeText(data, uri(output));
            data.writeBoolean(skip);
            writeProperties(data, event.getSession().getUserProperties());
            writeProperties(data, event.getSession().getSystemProperties());
            writeProperties(data, event.getProject().getProperties());
            writeStrings(data, strings(event.getProject().getBuild().getFilters()));
            data.writeInt(resources.size());
            for (ResourceRoot root : resources) {
                writeText(data, root.directory());
                writeText(data, root.targetPath());
                data.writeBoolean(root.filtering());
                writeStrings(data, root.includes());
                writeStrings(data, root.excludes());
            }
            // Values can contain credentials or filter properties. Only their digest is published.
            Xpp3Dom configuration = event.getExecution().getConfiguration();
            ArrayDeque<Xpp3Dom> nodes = new ArrayDeque<>();
            data.writeBoolean(configuration != null);
            if (configuration != null) {
                nodes.push(configuration);
            }
            int count = 0;
            while (!nodes.isEmpty()) {
                if (++count > MAX_VALUES) {
                    throw new IOException("resource configuration exceeds node budget");
                }
                Xpp3Dom node = nodes.pop();
                writeText(data, node.getName());
                writeText(data, node.getValue());
                String[] attributes = node.getAttributeNames();
                if (attributes.length > MAX_VALUES) {
                    throw new IOException("resource configuration exceeds attribute budget");
                }
                Arrays.sort(attributes);
                data.writeInt(attributes.length);
                for (String attribute : attributes) {
                    writeText(data, attribute);
                    writeText(data, node.getAttribute(attribute));
                }
                Xpp3Dom[] children = node.getChildren();
                if (children.length + nodes.size() > MAX_VALUES) {
                    throw new IOException("resource configuration exceeds node budget");
                }
                data.writeInt(children.length);
                for (int i = children.length - 1; i >= 0; i--) {
                    nodes.push(children[i]);
                }
            }
            // These public getters exist on the original Resources Mojo. Internal filtering
            // options without public getters remain unproven, never inferred from the model.
            for (String getter : List.of(
                    "getFilters", "getDelimiters", "isUseDefaultDelimiters", "isIncludeEmptyDirs", "isOverwrite")) {
                writeText(data, getter);
                Object value = invoke(event.getMojo(), getter);
                if (value instanceof Collection<?> collection) {
                    data.writeByte(1);
                    writeStrings(data, strings(collection));
                } else if (value instanceof Boolean flag) {
                    data.writeByte(2);
                    data.writeBoolean(flag);
                } else if (value == null) {
                    data.writeByte(0);
                } else {
                    throw new IOException("unsupported public Resources getter");
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> strings(Collection<?> values) throws IOException {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_VALUES) {
            throw new IOException("resource configuration exceeds value budget");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text) || text.length() > MAX_TEXT) {
                throw new IOException("unsupported resource configuration value");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static void writeStrings(DataOutputStream data, List<String> values) throws IOException {
        data.writeInt(values.size());
        for (String value : values) {
            writeText(data, value);
        }
    }

    private static void writeProperties(DataOutputStream data, Properties properties) throws IOException {
        Set<String> names = properties.stringPropertyNames();
        if (names.size() > MAX_VALUES) {
            throw new IOException("resource context exceeds property budget");
        }
        String[] ordered = names.toArray(String[]::new);
        Arrays.sort(ordered);
        data.writeInt(ordered.length);
        for (String name : ordered) {
            writeText(data, name);
            writeText(data, properties.getProperty(name));
        }
    }

    private static void writeText(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
        } else {
            if (value.length() > MAX_TEXT) {
                throw new IOException("resource configuration exceeds text budget");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(bytes.length);
            data.write(bytes);
        }
        if (data.size() > MAX_CONFIGURATION_BYTES) {
            throw new IOException("resource context exceeds byte budget");
        }
    }

    private static Object invoke(Mojo mojo, String getter) throws ReflectiveOperationException {
        return mojo.getClass().getMethod(getter).invoke(mojo);
    }

    private static Path resolve(Path base, String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("missing configured resource path");
        }
        Path path = base.resolve(value).toAbsolutePath();
        // Inspect lexical ancestors before normalization can erase a link/.. pair.
        boolean everyAncestorExists = rejectLinks(path);
        Path normalized = path.normalize();
        // Windows resolves "missing\..\x" lexically, so Files.exists(path) can be true for a missing ancestor;
        // the element-by-element walk is the platform-independent answer.
        if (!path.equals(normalized) && !everyAncestorExists) {
            throw new IOException("noncanonical missing resource path");
        }
        return normalized;
    }

    private static String uri(Path path) {
        String value = path.toUri().toASCIIString();
        // Path.toUri adds a slash for an existing directory. Identity must not
        // change merely because a previously missing directory now exists.
        return path.getFileName() != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void rejectOverlap(Path destination, Path observed) throws IOException {
        if (destination.startsWith(observed) || observed.startsWith(destination)) {
            throw new IOException("evidence directory overlaps observed resources");
        }
    }

    /** Rejects a symbolic link on the lexical path; returns whether every element of the lexical path exists. */
    private static boolean rejectLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path element : path) {
            current = current.resolve(element);
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    throw new IOException("resource observations do not follow symbolic links");
                }
            } catch (NoSuchFileException missing) {
                return false;
            }
        }
        return true;
    }

    private static List<FileState> observe(List<ResourceRoot> resources, Path output) throws IOException {
        ObservationScan scan = new ObservationScan();
        for (ResourceRoot resource : resources) {
            scan.scan(Area.INPUT, Path.of(URI.create(resource.directory())));
        }
        scan.scan(Area.OUTPUT, output);
        return List.copyOf(scan.states.values());
    }

    private static final class ObservationScan {
        private final TreeMap<String, FileState> states = new TreeMap<>();
        private long bytesRead;
        private final byte[] buffer = new byte[16_384];

        void scan(Area area, Path root) throws IOException {
            rejectLinks(root);
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    throw new IOException("resource root must be a directory");
                }
            } catch (NoSuchFileException missing) {
                add(new FileState(area, uri(root), Kind.MISSING, 0, 0, "UNKNOWN", ""));
                return;
            }
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    checkInterrupted();
                    add(new FileState(
                            area,
                            uri(directory),
                            Kind.DIRECTORY,
                            0,
                            attributes.lastModifiedTime().toMillis(),
                            permissions(directory),
                            ""));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    checkInterrupted();
                    if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES) {
                        throw new IOException("resource observation requires bounded regular files");
                    }
                    String uri = uri(file);
                    if (states.containsKey(area.name() + "\n" + uri)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (states.size() == ResourceObservation.MAX_FILES) {
                        throw new IOException("resource observation exceeds file budget");
                    }
                    MessageDigest digest = sha256();
                    long size = 0;
                    rejectLinks(file);
                    try (InputStream input = Channels.newInputStream(
                            Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
                        for (int read; (read = input.read(buffer)) != -1; ) {
                            checkInterrupted();
                            if (read == 0) {
                                throw new IOException("stalled resource observation read");
                            }
                            size += read;
                            bytesRead += read;
                            if (size > MAX_FILE_BYTES || bytesRead > MAX_SCAN_BYTES) {
                                throw new IOException("resource observation exceeds byte budget");
                            }
                            digest.update(buffer, 0, read);
                        }
                    }
                    BasicFileAttributes current =
                            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!current.isRegularFile()
                            || size != attributes.size()
                            || current.size() != attributes.size()
                            || !current.lastModifiedTime().equals(attributes.lastModifiedTime())
                            || !Objects.equals(current.fileKey(), attributes.fileKey())) {
                        throw new IOException("resource changed while being observed");
                    }
                    add(new FileState(
                            area,
                            uri,
                            Kind.FILE,
                            size,
                            current.lastModifiedTime().toMillis(),
                            permissions(file),
                            HexFormat.of().formatHex(digest.digest())));
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private void add(FileState file) throws IOException {
            String key = file.area().name() + "\n" + file.uri();
            if (!states.containsKey(key) && states.size() == ResourceObservation.MAX_FILES) {
                throw new IOException("resource observation exceeds file budget");
            }
            states.putIfAbsent(key, file);
        }
    }

    private static String permissions(Path path) throws IOException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException unavailable) {
            return "UNKNOWN";
        }
    }

    private static void createEvidenceDirectory(Path directory) throws IOException {
        rejectLinks(directory);
        Path current = directory.getRoot();
        for (Path element : directory) {
            current = current.resolve(element);
            try {
                Files.createDirectory(current);
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                // Verify each existing component below without following links.
            }
            BasicFileAttributes attributes =
                    Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("evidence directory requires ordinary directory ancestors");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("resource observation interrupted");
        }
    }

    private static void preserveInterrupt(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
        if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) {
            Thread.currentThread().interrupt();
        }
    }

    private record Capture(
            M3PluginEvidenceService.Identity identity,
            List<ResourceRoot> resources,
            Path output,
            Path destination,
            boolean skipRequested,
            List<FileState> before,
            List<String> limitations) {}

    private static final class Pending {
        private final MojoExecution execution;
        private final Mojo mojo;
        private final MavenProject project;
        private final long generation;
        private Capture capture;

        private Pending(MojoExecutionEvent event, long generation) {
            execution = event.getExecution();
            mojo = event.getMojo();
            project = event.getProject();
            this.generation = generation;
        }
    }

    private static final class State {
        private final List<Pending> pending = new ArrayList<>();
        private long generation;
        private int warnings;

        synchronized Pending reserve(MojoExecutionEvent event) throws IOException {
            if (pending.size() == 64 || generation == 512) {
                throw new IOException("resource evidence session budget exceeded");
            }
            for (Pending entry : pending) {
                if (entry.execution == event.getExecution() && entry.mojo == event.getMojo()) {
                    throw new IOException("resource invocation already observed");
                }
            }
            Pending entry = new Pending(event, ++generation);
            pending.add(entry);
            return entry;
        }

        synchronized Pending remove(MojoExecutionEvent event) {
            for (int i = 0; i < pending.size(); i++) {
                Pending entry = pending.get(i);
                if (entry.execution == event.getExecution() && entry.mojo == event.getMojo()) {
                    pending.remove(i);
                    return entry;
                }
            }
            return null;
        }

        synchronized void removeByExecution(MojoExecution execution, MavenProject project) {
            pending.removeIf(entry -> entry.execution == execution && entry.project == project);
        }

        synchronized void warn() {
            if (warnings < 3) {
                warnings++;
                LOGGER.warn("M3 Resources evidence unavailable; the original goal outcome is unchanged.");
            }
        }
    }
}
