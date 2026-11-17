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
package org.apache.maven.impl;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.maven.api.MonotonicClock;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.Interpolator;
import org.apache.maven.api.services.MavenException;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.api.services.ResourceFilter;
import org.apache.maven.api.services.ResourceFilterException;
import org.apache.maven.api.services.ResourceFilterRequest;
import org.apache.maven.api.services.ResourceFilterRequest.ChangeDetection;
import org.apache.maven.api.services.ResourceFilterResult;
import org.apache.maven.api.services.ResourceFilterResult.CopiedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link ResourceFilter}: expressions are resolved by the {@link Interpolator} service
 * and files are selected by the {@link PathMatcherFactory} service.
 * <p>
 * The copy rules follow maven-filtering 3.x {@code DefaultMavenResourcesFiltering} (Apache-2.0): a missing root
 * directory is skipped; a root's files go to its target path (relative paths against the output directory);
 * a target already written by an earlier root in the same call is skipped unless the request flattens or uses
 * {@link ChangeDetection#ALWAYS}; a flattened copy onto an existing file fails unless the rule is
 * {@code ALWAYS}; {@code *.properties} files use the properties encoding when one is set.
 *
 * @since 4.2.0
 */
@Named
@Singleton
public class DefaultResourceFilter implements ResourceFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultResourceFilter.class);

    private final Interpolator interpolator;
    private final PathMatcherFactory pathMatcherFactory;

    @Inject
    public DefaultResourceFilter(Interpolator interpolator, PathMatcherFactory pathMatcherFactory) {
        this.interpolator = requireNonNull(interpolator, "interpolator cannot be null");
        this.pathMatcherFactory = requireNonNull(pathMatcherFactory, "pathMatcherFactory cannot be null");
    }

    @Nonnull
    @Override
    public Reader filter(@Nonnull Reader reader, @Nonnull ResourceFilterRequest request) {
        requireNonNull(reader, "reader cannot be null");
        requireNonNull(request, "request cannot be null");
        return new ResourceFilterReader(reader, request, new ResourceFilterValues(interpolator, request));
    }

    @Nonnull
    @Override
    public ResourceFilterResult copy(@Nonnull ResourceFilterRequest request) {
        requireNonNull(request, "request cannot be null");
        Path output = request.getOutputDirectory()
                .orElseThrow(() -> new IllegalArgumentException("outputDirectory cannot be null"));
        Run run = new Run(request, output, new HashSet<>(), new ArrayList<>());
        long start = MonotonicClock.now().toEpochMilli();
        request.getRoots().forEach(root -> copyRoot(run, root));
        LOGGER.debug(
                "resource-filter roots={} files={} written={} millis={}",
                request.getRoots().size(),
                run.files().size(),
                run.files().stream().filter(CopiedFile::written).count(),
                MonotonicClock.now().toEpochMilli() - start);
        return new Result(request, List.copyOf(run.files()));
    }

    /** State of one {@link #copy(ResourceFilterRequest)} call. */
    private record Run(ResourceFilterRequest request, Path output, Set<Path> written, List<CopiedFile> files) {}

    /** The result: the request and the files in copy order. */
    private record Result(ResourceFilterRequest getRequest, List<CopiedFile> getFiles)
            implements ResourceFilterResult {}

    private void copyRoot(Run run, SourceRoot root) {
        Path directory = directory(run.request(), root);
        if (!root.enabled() || !Files.isDirectory(directory)) {
            LOGGER.info("Skipping non-existing or disabled resource directory {}", directory);
            return;
        }
        Path base = targetBase(root, run.output());
        ResourceFilterScan scan = scan(run.request(), root, directory);
        createDirectories(run.output());
        if (run.request().isIncludeEmptyDirs()) {
            scan.directories()
                    .forEach(dir -> createDirectories(
                            base.resolve(directory.relativize(dir).toString())));
        }
        LOGGER.info("Copying {} resource{} from {} to {}", scan.files().size(), plural(scan), directory, base);
        scan.files().forEach(file -> run.files().add(copyFile(run, root, file, target(run, directory, file, base))));
    }

    private ResourceFilterScan scan(ResourceFilterRequest request, SourceRoot root, Path directory) {
        PathMatcher matcher = pathMatcherFactory.createPathMatcher(
                directory, root.includes(), root.excludes(), request.isUseDefaultExcludes());
        try {
            return ResourceFilterScan.of(directory, matcher, pathMatcherFactory.deriveDirectoryMatcher(matcher));
        } catch (IOException e) {
            throw new ResourceFilterException("Cannot scan resource directory " + directory, e);
        }
    }

    private CopiedFile copyFile(Run run, SourceRoot root, Path source, Path target) {
        ResourceFilterRequest request = run.request();
        boolean first = run.written().add(target.toAbsolutePath().normalize());
        if (!first && !request.isFlatten() && request.getChangeDetection() != ChangeDetection.ALWAYS) {
            LOGGER.debug("Skipping {}: {} was written by an earlier resource root", source, target);
            return new CopiedFile(source, target, false, false);
        }
        checkFlattenCollision(request, source, target);
        boolean filtering = root.stringFiltering() && isFilteredExtension(request, source);
        Charset encoding = filtering ? encoding(request, source) : null;
        ResourceFilterCopy copy = new ResourceFilterCopy(source, target, encoding, reader -> filter(reader, request));
        return new CopiedFile(source, target, filtering, write(copy, request.getChangeDetection()));
    }

    private static boolean write(ResourceFilterCopy copy, ChangeDetection rule) {
        createDirectories(copy.target().getParent());
        try {
            return copy.copy(rule);
        } catch (IOException | MavenException e) {
            throw new ResourceFilterException(failure(copy, e), e);
        }
    }

    private static String failure(ResourceFilterCopy copy, Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (copy.encoding() != null && e instanceof CharacterCodingException) {
            reason += " while reading with " + copy.encoding().name() + " encoding";
        }
        return (copy.encoding() != null ? "filtering " : "copying ") + copy.source() + " to " + copy.target()
                + " failed with " + reason;
    }

    private static void checkFlattenCollision(ResourceFilterRequest request, Path source, Path target) {
        if (!request.isFlatten() || !Files.exists(target)) {
            return;
        }
        if (request.getChangeDetection() != ChangeDetection.ALWAYS) {
            throw new ResourceFilterException("existing file " + target.getFileName() + " will be overwritten by "
                    + source + " and overwrite was not set to true");
        }
        LOGGER.warn("existing file {} will be overwritten by {}", target.getFileName(), source);
    }

    private Path directory(ResourceFilterRequest request, SourceRoot root) {
        Path directory = root.directory();
        if (directory.isAbsolute()) {
            return directory;
        }
        return request.getBaseDirectory()
                .orElseGet(() -> request.getSession().getTopDirectory())
                .resolve(directory);
    }

    /** As {@link SourceRoot#targetPath(org.apache.maven.api.Project)}, with the request's output directory. */
    private static Path targetBase(SourceRoot root, Path output) {
        return root.targetPath()
                .map(path -> path.isAbsolute() ? path : output.resolve(path))
                .orElse(output);
    }

    private static Path target(Run run, Path directory, Path file, Path base) {
        Path relative = run.request().isFlatten() ? file.getFileName() : directory.relativize(file);
        return base.resolve(relative.toString());
    }

    private static boolean isFilteredExtension(ResourceFilterRequest request, Path file) {
        boolean filtered = !request.getNonFilteredFileExtensions().contains(extension(file));
        LOGGER.debug("file {} has a {}filtered file extension", file.getFileName(), filtered ? "" : "non ");
        return filtered;
    }

    private static Charset encoding(ResourceFilterRequest request, Path file) {
        if ("properties".equals(extension(file))) {
            return request.getPropertiesEncoding().orElse(request.getEncoding());
        }
        return request.getEncoding();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** As maven-filtering: "0 resource", "1 resource", "2 resources". */
    private static String plural(ResourceFilterScan scan) {
        return scan.files().size() > 1 ? "s" : "";
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new ResourceFilterException("Cannot create resource output directory: " + directory, e);
        }
    }
}
