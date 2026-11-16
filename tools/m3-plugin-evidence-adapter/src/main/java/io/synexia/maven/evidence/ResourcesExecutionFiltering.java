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
package io.synexia.maven.evidence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.apache.maven.shared.filtering.DefaultMavenResourcesFiltering;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.filtering.Resource;
import org.sonatype.plexus.build.incremental.BuildContext;

/** Qualifies the effective donor request before constructing its local original engine. */
final class ResourcesExecutionFiltering implements MavenResourcesFiltering {
    private static final int MAX_CONTEXT_BYTES = 1024 * 1024;

    private final Path projectRoot;
    private final Path evidenceDirectory;
    private final DefaultMavenResourcesFiltering original;
    private final RecordingFileFilter recorder;
    private final BuildContext buildContext;
    private final List<String> defaultExtensions;
    private byte[] declaration;

    ResourcesExecutionFiltering(
            Path projectRoot,
            Path evidenceDirectory,
            DefaultMavenResourcesFiltering original,
            RecordingFileFilter recorder,
            BuildContext buildContext) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.evidenceDirectory = Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        this.original = Objects.requireNonNull(original, "original");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.buildContext = Objects.requireNonNull(buildContext, "buildContext");
        this.defaultExtensions = List.copyOf(original.getDefaultNonFilteredFileExtensions());
    }

    @Override
    public void filterResources(MavenResourcesExecution request) throws MavenFilteringException {
        try {
            if (declaration != null) {
                throw new IOException("resource evidence admits one effective request per invocation");
            }
            if (request == null || request.getResources() == null || request.getResources().size() != 1) {
                throw new IOException("resource evidence v1 requires exactly one resource root");
            }
            Resource resource = request.getResources().get(0);
            if (resource == null
                    || resource.isFiltering()
                    || request.isFilterFilenames()
                    || request.isIncludeEmptyDirs()
                    || request.isFlatten()
                    || resource.getTargetPath() != null) {
                throw new IOException(
                        "resource evidence v1 requires non-filtering, no targetPath, no empty directories and no flattening");
            }
            if (buildContext.isIncremental()) {
                throw new IOException("incremental BuildContext is outside resource evidence v1");
            }
            if (resource.getDirectory() == null || request.getOutputDirectory() == null) {
                throw new IOException("resource evidence requires declared source and output directories");
            }
            Path resourcePath = Path.of(resource.getDirectory());
            if (!resourcePath.isAbsolute()) {
                if (request.getResourcesBaseDirectory() == null) {
                    throw new IOException("relative resource directory requires its original base directory");
                }
                resourcePath = request.getResourcesBaseDirectory().resolve(resourcePath);
            }
            Path sourceRoot = EvidenceFiles.checked(projectRoot, resourcePath.toAbsolutePath(), true);
            Path outputRoot =
                    EvidenceFiles.checked(projectRoot, request.getOutputDirectory().toAbsolutePath(), true);
            if (sourceRoot.startsWith(outputRoot) || outputRoot.startsWith(sourceRoot)) {
                throw new IOException("resource source and output trees must not overlap");
            }
            requireOutside(evidenceDirectory, sourceRoot);
            requireOutside(evidenceDirectory, outputRoot);

            GuardedBuildContext guarded = new GuardedBuildContext(projectRoot, sourceRoot, outputRoot, buildContext);
            DefaultMavenResourcesFiltering engine = new DefaultMavenResourcesFiltering(recorder, guarded);
            engine.getDefaultNonFilteredFileExtensions().clear();
            engine.getDefaultNonFilteredFileExtensions().addAll(defaultExtensions);
            declaration = declaration(request, resource, sourceRoot, outputRoot);
            engine.filterResources(request);
        } catch (UncheckedIOException exception) {
            throw new MavenFilteringException(
                    "resource evidence request rejected: " + exception.getCause().getMessage(), exception.getCause());
        } catch (IOException exception) {
            throw new MavenFilteringException("resource evidence request rejected: " + exception.getMessage(), exception);
        }
    }

    /** Framed declared observations only; these bytes do not define a complete cache key. */
    byte[] declaredContext() throws IOException {
        if (declaration == null) {
            throw new IOException("resource evidence has no effective request observation");
        }
        return declaration.clone();
    }

    private byte[] declaration(
            MavenResourcesExecution request, Resource resource, Path sourceRoot, Path outputRoot)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            text(output, "m3.resources.declared-observation.v1");
            text(output, relative(sourceRoot));
            text(output, relative(outputRoot));
            text(output, request.getChangeDetection().name());
            text(output, resource.getChangeDetection() == null ? null : resource.getChangeDetection().name());
            output.writeBoolean(request.isAddDefaultExcludes());
            text(output, request.getEncoding());
            text(output, request.getOutputEncoding());
            text(output, request.getPropertiesEncoding());
            text(output, request.getOutputPropertiesEncoding());
            text(output, resource.getEncoding());
            text(output, resource.getOutputEncoding());
            strings(output, resource.getIncludes());
            strings(output, resource.getExcludes());
            strings(output, resource.getNonFilteredFiles());
            strings(output, request.getNonFilteredFileExtensions());
            strings(output, defaultExtensions);
        }
        if (bytes.size() > MAX_CONTEXT_BYTES) {
            throw new IOException("resource declared-context byte limit exceeded");
        }
        return bytes.toByteArray();
    }

    private String relative(Path path) {
        return projectRoot.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static void requireOutside(Path evidence, Path tree) throws IOException {
        if (evidence.startsWith(tree) || tree.startsWith(evidence)) {
            throw new IOException("resource evidence directory overlaps a resource or output tree");
        }
    }

    private static void strings(DataOutputStream output, List<String> values) throws IOException {
        if (values == null) {
            output.writeInt(-1);
            return;
        }
        if (values.size() > RecordingFileFilter.MAX_OPERATIONS) {
            throw new IOException("resource declared-context entry limit exceeded");
        }
        output.writeInt(values.size());
        for (String value : values) {
            text(output, value);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        if (value.length() > 16_384) {
            throw new IOException("resource declared-context byte limit exceeded");
        }
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new IOException("malformed Unicode in resource declared context", exception);
        }
        int length = encoded.remaining();
        if (length > 16_384 || output.size() > MAX_CONTEXT_BYTES - length - Integer.BYTES) {
            throw new IOException("resource declared-context byte limit exceeded");
        }
        byte[] bytes = new byte[length];
        encoded.get(bytes);
        output.writeInt(length);
        output.write(bytes);
    }

    @Override
    public List<String> getDefaultNonFilteredFileExtensions() {
        return original.getDefaultNonFilteredFileExtensions();
    }

    @Override
    public boolean filteredFileExtension(String fileName, List<String> userNonFilteredFileExtensions) {
        return original.filteredFileExtension(fileName, userNonFilteredFileExtensions);
    }
}

