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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.shared.filtering.AbstractMavenFilteringRequest;
import org.apache.maven.shared.filtering.ChangeDetection;
import org.apache.maven.shared.filtering.FilterWrapper;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFileFilterRequest;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;

/**
 * Records only the original delegate's result-bearing copy operations.
 * Other overloads retain their original delegation and imply no captured result.
 */
final class RecordingFileFilter implements MavenFileFilter {
    static final int MAX_OPERATIONS = 1024;
    static final long MAX_HASH_BYTES = 256L * 1024 * 1024;

    private final Path projectRoot;
    private final MavenFileFilter delegate;
    private final List<Captured> captured = new ArrayList<>();
    private final Set<Path> destinations = new HashSet<>();
    private long hashBytes;
    private boolean failed;
    private List<ResourceEvidence.Operation> finished;

    RecordingFileFilter(Path projectRoot, MavenFileFilter delegate) throws IOException {
        this.projectRoot = EvidenceFiles.checked(projectRoot, projectRoot.toAbsolutePath(), false);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Revalidates every observed source/output after all original Mojo components
     * have returned. A completed result is immutable and cannot accept later copies.
     */
    List<ResourceEvidence.Operation> finish() throws IOException {
        if (failed) {
            throw new IOException("resource evidence contains a failed operation");
        }
        if (finished != null) {
            return finished;
        }
        List<ResourceEvidence.Operation> operations = new ArrayList<>(captured.size());
        try {
            for (Captured item : captured) {
                if (!item.input().equals(digest(item.source(), false))) {
                    throw new IOException("resource input changed after its copy operation");
                }
                if (!item.output().equals(digest(item.destination(), !item.delegateResult()))) {
                    throw new IOException("resource output changed after its copy operation");
                }
                operations.add(new ResourceEvidence.Operation(
                        relative(item.source()),
                        relative(item.destination()),
                        item.input().sha256(),
                        item.output().sha256(),
                        item.input().size(),
                        item.output().size(),
                        item.delegateResult()));
            }
        } catch (IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        }
        operations.sort(Comparator.comparing(ResourceEvidence.Operation::output)
                .thenComparing(ResourceEvidence.Operation::input));
        finished = List.copyOf(operations);
        return finished;
    }

    @Override
    public boolean copyFileWithResult(
            Path from,
            Path to,
            boolean filtering,
            List<FilterWrapper> wrappers,
            String encoding,
            ChangeDetection changeDetection)
            throws MavenFilteringException {
        return record(from, to, filtering,
                () -> delegate.copyFileWithResult(from, to, filtering, wrappers, encoding, changeDetection));
    }

    @Override
    public boolean copyFileWithResult(
            Path from,
            Path to,
            boolean filtering,
            List<FilterWrapper> wrappers,
            String inputEncoding,
            String outputEncoding,
            ChangeDetection changeDetection)
            throws MavenFilteringException {
        return record(from, to, filtering,
                () -> delegate.copyFileWithResult(
                        from, to, filtering, wrappers, inputEncoding, outputEncoding, changeDetection));
    }

    private boolean record(Path from, Path to, boolean filtering, CopyCall copy)
            throws MavenFilteringException {
        try {
            if (failed || finished != null) {
                throw new IOException("resource evidence recorder is no longer open");
            }
            if (filtering) {
                throw new IOException("filtered copy operations are outside resource evidence v1");
            }
            if (captured.size() >= MAX_OPERATIONS) {
                throw new IOException("resource evidence operation limit exceeded");
            }
            Path source = EvidenceFiles.checked(projectRoot, from.toAbsolutePath(), false);
            Path destination = EvidenceFiles.checked(projectRoot, to.toAbsolutePath(), true);
            for (Path previous : destinations) {
                if (destination.startsWith(previous) || previous.startsWith(destination)) {
                    throw new IOException("overlapping resource output operations are not admitted");
                }
            }
            destinations.add(destination);
            EvidenceFiles.FileDigest before = digest(source, false);
            boolean delegateResult = copy.run();
            EvidenceFiles.FileDigest after = digest(source, false);
            if (!before.equals(after)) {
                throw new IOException("resource input changed during its copy operation");
            }
            EvidenceFiles.FileDigest output = digest(destination, !delegateResult);
            captured.add(new Captured(source, destination, before, output, delegateResult));
            return delegateResult;
        } catch (IOException exception) {
            failed = true;
            throw new MavenFilteringException("resource evidence capture failed: " + exception.getMessage(), exception);
        } catch (MavenFilteringException | RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    private EvidenceFiles.FileDigest digest(Path path, boolean missingAllowed) throws IOException {
        EvidenceFiles.FileDigest digest =
                EvidenceFiles.digest(projectRoot, path, missingAllowed, MAX_HASH_BYTES - hashBytes);
        if (digest.size() >= 0) {
            hashBytes += digest.size();
        }
        return digest;
    }

    private String relative(Path path) {
        return projectRoot.relativize(path).toString().replace(File.separatorChar, '/');
    }

    @Override
    public void copyFile(
            Path from,
            Path to,
            boolean filtering,
            Project project,
            List<String> filters,
            boolean escapedBackslashesInFilePath,
            String encoding,
            Session session)
            throws MavenFilteringException {
        delegate.copyFile(from, to, filtering, project, filters, escapedBackslashesInFilePath, encoding, session);
    }

    @Override
    public void copyFile(MavenFileFilterRequest request) throws MavenFilteringException {
        delegate.copyFile(request);
    }

    @Deprecated
    @Override
    public void copyFile(
            Path from, Path to, boolean filtering, List<FilterWrapper> wrappers, String encoding)
            throws MavenFilteringException {
        delegate.copyFile(from, to, filtering, wrappers, encoding);
    }

    @Override
    public void copyFile(
            Path from,
            Path to,
            boolean filtering,
            List<FilterWrapper> wrappers,
            String encoding,
            ChangeDetection changeDetection)
            throws MavenFilteringException {
        delegate.copyFile(from, to, filtering, wrappers, encoding, changeDetection);
    }

    @Override
    public void copyFile(
            Path from,
            Path to,
            boolean filtering,
            List<FilterWrapper> wrappers,
            String encoding,
            boolean gracefulBinaryHandling)
            throws MavenFilteringException {
        delegate.copyFile(from, to, filtering, wrappers, encoding, gracefulBinaryHandling);
    }

    @Override
    public List<FilterWrapper> getDefaultFilterWrappers(
            Project project,
            List<String> filters,
            boolean escapedBackslashesInFilePath,
            Session session,
            MavenResourcesExecution request)
            throws MavenFilteringException {
        return delegate.getDefaultFilterWrappers(
                project, filters, escapedBackslashesInFilePath, session, request);
    }

    @Override
    public List<FilterWrapper> getDefaultFilterWrappers(AbstractMavenFilteringRequest request)
            throws MavenFilteringException {
        return delegate.getDefaultFilterWrappers(request);
    }

    private record Captured(
            Path source,
            Path destination,
            EvidenceFiles.FileDigest input,
            EvidenceFiles.FileDigest output,
            boolean delegateResult) {}

    @FunctionalInterface
    private interface CopyCall {
        boolean run() throws MavenFilteringException;
    }
}

