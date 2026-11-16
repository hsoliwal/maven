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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Validates the original scanner's selected paths before the original engine can
 * create destination parents. It neither rescans nor changes selected names.
 */
final class GuardedBuildContext implements BuildContext {
    private final Path projectRoot;
    private final Path sourceRoot;
    private final Path outputRoot;
    private final BuildContext delegate;

    GuardedBuildContext(Path projectRoot, Path sourceRoot, Path outputRoot, BuildContext delegate)
            throws IOException {
        this.projectRoot = EvidenceFiles.checked(projectRoot, projectRoot, false);
        this.sourceRoot = EvidenceFiles.checked(this.projectRoot, sourceRoot, true);
        this.outputRoot = EvidenceFiles.checked(this.projectRoot, outputRoot, true);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (delegate.isIncremental()) {
            throw new IOException("incremental BuildContext is outside resource evidence v1");
        }
    }

    @Override
    public Scanner newScanner(File basedir) {
        checkBase(basedir);
        return new GuardedScanner(delegate.newScanner(basedir), false);
    }

    @Override
    public Scanner newScanner(File basedir, boolean ignoreDelta) {
        checkBase(basedir);
        return new GuardedScanner(delegate.newScanner(basedir, ignoreDelta), false);
    }

    @Override
    public Scanner newDeleteScanner(File basedir) {
        checkBase(basedir);
        return new GuardedScanner(delegate.newDeleteScanner(basedir), true);
    }

    private void checkBase(File basedir) {
        try {
            if (!sourceRoot.equals(EvidenceFiles.checked(projectRoot, basedir.toPath(), true))) {
                throw new IOException("resource scanner base differs from the declared resource root");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("resource evidence scanner path rejected", exception);
        }
    }

    private final class GuardedScanner implements Scanner {
        private final Scanner scanner;
        private final boolean deletion;
        private String[] files;
        private String[] directories;

        private GuardedScanner(Scanner scanner, boolean deletion) {
            this.scanner = Objects.requireNonNull(scanner, "scanner");
            this.deletion = deletion;
        }

        @Override
        public void scan() {
            files = null;
            directories = null;
            scanner.scan();
            String[] selectedFiles = scanner.getIncludedFiles();
            String[] selectedDirectories = scanner.getIncludedDirectories();
            try {
                // The original engine always asks for this scanner, including
                // full builds. Only its ordinary empty result is in this profile.
                if (deletion && (selectedFiles.length != 0 || selectedDirectories.length != 0)) {
                    throw new IOException("resource deletion selections are outside resource evidence v1");
                }
                if (selectedFiles.length > RecordingFileFilter.MAX_OPERATIONS
                        || selectedDirectories.length > RecordingFileFilter.MAX_OPERATIONS) {
                    throw new IOException("resource evidence scanner entry limit exceeded");
                }
                selectedFiles = selectedFiles.clone();
                selectedDirectories = selectedDirectories.clone();
                Set<Path> outputs = new HashSet<>();
                for (String name : selectedFiles) {
                    Path destination = validate(name, false);
                    if (!outputs.add(destination)) {
                        throw new IOException("duplicate resource scanner output");
                    }
                }
                for (String name : selectedDirectories) {
                    validate(name, true);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("resource evidence scanner path rejected", exception);
            }
            files = selectedFiles;
            directories = selectedDirectories;
        }

        private Path validate(String name, boolean directory) throws IOException {
            if (name == null || (!directory && name.isEmpty())) {
                throw new IOException("invalid resource scanner name");
            }
            Path relative = Path.of(name);
            if (relative.isAbsolute()) {
                throw new IOException("absolute resource scanner name is not admitted");
            }
            for (Path component : relative) {
                String part = component.toString();
                if (part.indexOf('\\') >= 0
                        || part.indexOf(':') >= 0
                        || part.chars().anyMatch(Character::isISOControl)) {
                    throw new IOException("ambiguous resource scanner name is not admitted");
                }
            }
            Path source = EvidenceFiles.checked(projectRoot, sourceRoot.resolve(relative), deletion);
            Path destination = EvidenceFiles.checked(projectRoot, outputRoot.resolve(relative), true);
            if (!source.startsWith(sourceRoot) || !destination.startsWith(outputRoot)) {
                throw new IOException("resource scanner name escapes its declared tree");
            }
            if (!deletion && !directory && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("resource scanner input must be a regular file");
            }
            return destination;
        }

        @Override
        public String[] getIncludedFiles() {
            if (files == null) {
                throw new IllegalStateException("resource scanner has no validated result");
            }
            return files.clone();
        }

        @Override
        public String[] getIncludedDirectories() {
            if (directories == null) {
                throw new IllegalStateException("resource scanner has no validated result");
            }
            return directories.clone();
        }

        @Override
        public void setIncludes(String[] includes) {
            scanner.setIncludes(includes);
        }

        @Override
        public void setExcludes(String[] excludes) {
            scanner.setExcludes(excludes);
        }

        @Override
        public void addDefaultExcludes() {
            scanner.addDefaultExcludes();
        }

        @Override
        public File getBasedir() {
            return scanner.getBasedir();
        }

        @Override
        public void setFilenameComparator(Comparator<String> comparator) {
            scanner.setFilenameComparator(comparator);
        }
    }

    @Override
    public boolean hasDelta(String relpath) {
        return delegate.hasDelta(relpath);
    }

    @Override
    public boolean hasDelta(File file) {
        return delegate.hasDelta(file);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean hasDelta(List relpaths) {
        return delegate.hasDelta(relpaths);
    }

    @Override
    public void refresh(File file) {
        delegate.refresh(file);
    }

    @Override
    public OutputStream newFileOutputStream(File file) throws IOException {
        return delegate.newFileOutputStream(file);
    }

    @Override
    public boolean isIncremental() {
        return delegate.isIncremental();
    }

    @Override
    public void setValue(String key, Object value) {
        delegate.setValue(key, value);
    }

    @Override
    public Object getValue(String key) {
        return delegate.getValue(key);
    }

    /** @deprecated Retains the original BuildContext compatibility method and delegation. */
    @Deprecated
    @Override
    public void addWarning(File file, int line, int column, String message, Throwable cause) {
        delegate.addWarning(file, line, column, message, cause);
    }

    /** @deprecated Retains the original BuildContext compatibility method and delegation. */
    @Deprecated
    @Override
    public void addError(File file, int line, int column, String message, Throwable cause) {
        delegate.addError(file, line, column, message, cause);
    }

    @Override
    public void addMessage(File file, int line, int column, String message, int severity, Throwable cause) {
        delegate.addMessage(file, line, column, message, severity, cause);
    }

    @Override
    public void removeMessages(File file) {
        delegate.removeMessages(file);
    }

    @Override
    public boolean isUptodate(File target, File source) {
        return delegate.isUptodate(target, source);
    }
}

