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
package org.apache.maven.api.services;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Immutable;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.NotThreadSafe;
import org.apache.maven.api.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Request for the {@link ResourceFilter} service.
 * <p>
 * The value of an expression is looked up in {@link #getProperties()} first, then in the
 * {@linkplain ProtoSession#getEffectiveProperties() effective properties of the session} (user properties over
 * system properties), then through {@link #getCallback()}. Callers that want project values (for example
 * {@code project.version}) or filter files supply them through the callback, which lets them keep the precedence
 * of maven-filtering, where user and system properties win over project properties.
 *
 * @since 4.2.0
 */
@Experimental
@Immutable
public interface ResourceFilterRequest extends Request<ProtoSession> {

    /**
     * The delimiters used when none are given: {@code ${*}} and {@code @}, as in maven-filtering.
     */
    List<String> DEFAULT_DELIMITERS = List.of("${*}", "@");

    /**
     * The file extensions that are never filtered, as in maven-filtering. Requests add to this set.
     */
    Set<String> DEFAULT_NON_FILTERED_FILE_EXTENSIONS = Set.of("jpg", "jpeg", "gif", "bmp", "png", "ico");

    /**
     * When an existing target file is overwritten. The constants have the meaning of the maven-filtering
     * {@code ChangeDetection} constants of the same name.
     */
    enum ChangeDetection {
        /** Write when the source is newer than the target. */
        TIMESTAMP,
        /** Write when the new content differs from the target content. This is the default. */
        CONTENT,
        /** Write when the source is newer than the target and the new content differs. */
        TIMESTAMP_AND_CONTENT,
        /** Always write. */
        ALWAYS,
        /** Write only when the target does not exist. */
        NEVER
    }

    /**
     * {@return the source roots to copy, in order; for overlapping targets the first root wins}
     * Disabled roots ({@link SourceRoot#enabled()} false) and missing directories are skipped.
     */
    @Nonnull
    List<SourceRoot> getRoots();

    /**
     * {@return the directory against which empty and relative {@linkplain SourceRoot#targetPath() target paths}
     * are resolved}, required by {@link ResourceFilter#copy(ResourceFilterRequest)}
     */
    @Nonnull
    Optional<Path> getOutputDirectory();

    /**
     * {@return the directory against which relative root directories are resolved}
     * When empty, the {@linkplain ProtoSession#getTopDirectory() top directory} of the session is used.
     */
    @Nonnull
    Optional<Path> getBaseDirectory();

    /** {@return the properties that are looked up first} */
    @Nonnull
    Map<String, String> getProperties();

    /** {@return the lookup used for names found neither in the properties nor in the session} */
    @Nonnull
    Optional<UnaryOperator<String>> getCallback();

    /**
     * {@return the delimiter specifications}
     * A specification is {@code BEGIN*END} for asymmetric delimiters (as {@code ${*}}) or {@code TOKEN} for
     * symmetric ones (as {@code @}). When several begin tokens match at the same position, the one declared last
     * wins, as in maven-filtering.
     */
    @Nonnull
    List<String> getDelimiters();

    /**
     * {@return the escape string}
     * An expression preceded by it is copied without the escape string and is not replaced
     * ({@code \${a}} gives {@code ${a}} with escape string {@code \}).
     */
    @Nonnull
    Optional<String> getEscapeString();

    /** {@return whether an expression may span lines; when false an expression ends at the end of a line} */
    boolean isSupportMultiLineFiltering();

    /** {@return whether backslashes in values that look like Windows paths are doubled (default true)} */
    boolean isEscapeWindowsPaths();

    /** {@return the encoding of filtered text files (default UTF-8)} */
    @Nonnull
    Charset getEncoding();

    /** {@return the encoding of filtered {@code *.properties} files, when it differs from the encoding} */
    @Nonnull
    Optional<Charset> getPropertiesEncoding();

    /** {@return the lower-case extensions of files that are copied byte for byte even in filtered roots} */
    @Nonnull
    Set<String> getNonFilteredFileExtensions();

    /** {@return whether the default SCM excludes of the {@link PathMatcherFactory} apply (default true)} */
    boolean isUseDefaultExcludes();

    /** {@return whether directories selected by the patterns are created even when they hold no file} */
    boolean isIncludeEmptyDirs();

    /**
     * {@return whether files are copied without their directories}
     * An existing target then fails the copy unless the change detection is {@link ChangeDetection#ALWAYS}.
     */
    boolean isFlatten();

    /** {@return when an existing target file is overwritten (default {@link ChangeDetection#CONTENT})} */
    @Nonnull
    ChangeDetection getChangeDetection();

    /**
     * {@return a new builder}
     */
    @Nonnull
    static ResourceFilterRequestBuilder builder() {
        return new ResourceFilterRequestBuilder();
    }

    /**
     * Builder of {@link ResourceFilterRequest}; every setter returns this builder.
     */
    @NotThreadSafe
    class ResourceFilterRequestBuilder {
        ProtoSession session;
        RequestTrace trace;
        List<SourceRoot> roots = List.of();
        Path outputDirectory;
        Path baseDirectory;
        Map<String, String> properties = Map.of();
        UnaryOperator<String> callback;
        List<String> delimiters = DEFAULT_DELIMITERS;
        String escapeString;
        boolean supportMultiLineFiltering;
        boolean escapeWindowsPaths = true;
        Charset encoding = StandardCharsets.UTF_8;
        Charset propertiesEncoding;
        Set<String> nonFilteredFileExtensions = DEFAULT_NON_FILTERED_FILE_EXTENSIONS;
        boolean useDefaultExcludes = true;
        boolean includeEmptyDirs;
        boolean flatten;
        ChangeDetection changeDetection = ChangeDetection.CONTENT;

        ResourceFilterRequestBuilder() {}

        public ResourceFilterRequestBuilder session(@Nonnull ProtoSession session) {
            this.session = requireNonNull(session, "session cannot be null");
            return this;
        }

        public ResourceFilterRequestBuilder trace(@Nullable RequestTrace trace) {
            this.trace = trace;
            return this;
        }

        public ResourceFilterRequestBuilder roots(@Nonnull Collection<? extends SourceRoot> roots) {
            this.roots = List.copyOf(roots);
            return this;
        }

        public ResourceFilterRequestBuilder outputDirectory(@Nullable Path outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public ResourceFilterRequestBuilder baseDirectory(@Nullable Path baseDirectory) {
            this.baseDirectory = baseDirectory;
            return this;
        }

        public ResourceFilterRequestBuilder properties(@Nonnull Map<String, String> properties) {
            this.properties = Map.copyOf(properties);
            return this;
        }

        public ResourceFilterRequestBuilder callback(@Nullable UnaryOperator<String> callback) {
            this.callback = callback;
            return this;
        }

        /**
         * Sets the delimiter specifications; {@code null} or an empty collection selects {@code ${*}} only,
         * as maven-filtering does.
         *
         * @param delimiters the specifications, see {@link ResourceFilterRequest#getDelimiters()}
         * @return this builder
         * @throws IllegalArgumentException if a specification is empty or has an empty begin token
         */
        public ResourceFilterRequestBuilder delimiters(@Nullable Collection<String> delimiters) {
            if (delimiters == null || delimiters.isEmpty()) {
                this.delimiters = List.of("${*}");
                return this;
            }
            delimiters.forEach(ResourceFilterRequestBuilder::checkDelimiter);
            this.delimiters = delimiters.stream().distinct().toList();
            return this;
        }

        public ResourceFilterRequestBuilder escapeString(@Nullable String escapeString) {
            this.escapeString = escapeString == null || escapeString.isEmpty() ? null : escapeString;
            return this;
        }

        public ResourceFilterRequestBuilder supportMultiLineFiltering(boolean supportMultiLineFiltering) {
            this.supportMultiLineFiltering = supportMultiLineFiltering;
            return this;
        }

        public ResourceFilterRequestBuilder escapeWindowsPaths(boolean escapeWindowsPaths) {
            this.escapeWindowsPaths = escapeWindowsPaths;
            return this;
        }

        public ResourceFilterRequestBuilder encoding(@Nonnull Charset encoding) {
            this.encoding = requireNonNull(encoding, "encoding cannot be null");
            return this;
        }

        public ResourceFilterRequestBuilder propertiesEncoding(@Nullable Charset propertiesEncoding) {
            this.propertiesEncoding = propertiesEncoding;
            return this;
        }

        /**
         * Adds extensions to the {@linkplain #DEFAULT_NON_FILTERED_FILE_EXTENSIONS default} non-filtered ones.
         *
         * @param extensions extensions without the dot, matched without regard to case
         * @return this builder
         */
        public ResourceFilterRequestBuilder nonFilteredFileExtensions(@Nonnull Collection<String> extensions) {
            Set<String> all = new TreeSet<>(DEFAULT_NON_FILTERED_FILE_EXTENSIONS);
            extensions.forEach(extension -> all.add(extension.toLowerCase(Locale.ROOT)));
            this.nonFilteredFileExtensions = Set.copyOf(all);
            return this;
        }

        public ResourceFilterRequestBuilder useDefaultExcludes(boolean useDefaultExcludes) {
            this.useDefaultExcludes = useDefaultExcludes;
            return this;
        }

        public ResourceFilterRequestBuilder includeEmptyDirs(boolean includeEmptyDirs) {
            this.includeEmptyDirs = includeEmptyDirs;
            return this;
        }

        public ResourceFilterRequestBuilder flatten(boolean flatten) {
            this.flatten = flatten;
            return this;
        }

        public ResourceFilterRequestBuilder changeDetection(@Nonnull ChangeDetection changeDetection) {
            this.changeDetection = requireNonNull(changeDetection, "changeDetection cannot be null");
            return this;
        }

        @Nonnull
        public ResourceFilterRequest build() {
            return new DefaultResourceFilterRequest(this);
        }

        private static void checkDelimiter(String spec) {
            if (spec == null || spec.isEmpty() || spec.startsWith("*")) {
                throw new IllegalArgumentException("Invalid delimiter specification: '" + spec + "'");
            }
        }

        private static final class DefaultResourceFilterRequest extends BaseRequest<ProtoSession>
                implements ResourceFilterRequest {
            private final ResourceFilterRequestBuilder b;

            DefaultResourceFilterRequest(ResourceFilterRequestBuilder builder) {
                super(builder.session, builder.trace);
                this.b = copy(builder);
            }

            private static ResourceFilterRequestBuilder copy(ResourceFilterRequestBuilder source) {
                ResourceFilterRequestBuilder c = new ResourceFilterRequestBuilder();
                c.roots = source.roots;
                c.outputDirectory = source.outputDirectory;
                c.baseDirectory = source.baseDirectory;
                c.properties = source.properties;
                c.callback = source.callback;
                c.delimiters = source.delimiters;
                c.escapeString = source.escapeString;
                c.supportMultiLineFiltering = source.supportMultiLineFiltering;
                c.escapeWindowsPaths = source.escapeWindowsPaths;
                return copyFiles(source, c);
            }

            private static ResourceFilterRequestBuilder copyFiles(
                    ResourceFilterRequestBuilder source, ResourceFilterRequestBuilder c) {
                c.encoding = source.encoding;
                c.propertiesEncoding = source.propertiesEncoding;
                c.nonFilteredFileExtensions = source.nonFilteredFileExtensions;
                c.useDefaultExcludes = source.useDefaultExcludes;
                c.includeEmptyDirs = source.includeEmptyDirs;
                c.flatten = source.flatten;
                c.changeDetection = source.changeDetection;
                return c;
            }

            @Override
            public List<SourceRoot> getRoots() {
                return b.roots;
            }

            @Override
            public Optional<Path> getOutputDirectory() {
                return Optional.ofNullable(b.outputDirectory);
            }

            @Override
            public Optional<Path> getBaseDirectory() {
                return Optional.ofNullable(b.baseDirectory);
            }

            @Override
            public Map<String, String> getProperties() {
                return b.properties;
            }

            @Override
            public Optional<UnaryOperator<String>> getCallback() {
                return Optional.ofNullable(b.callback);
            }

            @Override
            public List<String> getDelimiters() {
                return b.delimiters;
            }

            @Override
            public Optional<String> getEscapeString() {
                return Optional.ofNullable(b.escapeString);
            }

            @Override
            public boolean isSupportMultiLineFiltering() {
                return b.supportMultiLineFiltering;
            }

            @Override
            public boolean isEscapeWindowsPaths() {
                return b.escapeWindowsPaths;
            }

            @Override
            public Charset getEncoding() {
                return b.encoding;
            }

            @Override
            public Optional<Charset> getPropertiesEncoding() {
                return Optional.ofNullable(b.propertiesEncoding);
            }

            @Override
            public Set<String> getNonFilteredFileExtensions() {
                return b.nonFilteredFileExtensions;
            }

            @Override
            public boolean isUseDefaultExcludes() {
                return b.useDefaultExcludes;
            }

            @Override
            public boolean isIncludeEmptyDirs() {
                return b.includeEmptyDirs;
            }

            @Override
            public boolean isFlatten() {
                return b.flatten;
            }

            @Override
            public ChangeDetection getChangeDetection() {
                return b.changeDetection;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof DefaultResourceFilterRequest that && Arrays.deepEquals(values(), that.values());
            }

            @Override
            public int hashCode() {
                return Arrays.deepHashCode(values());
            }

            @Override
            public String toString() {
                return "ResourceFilterRequest[roots=" + b.roots + ", outputDirectory=" + b.outputDirectory
                        + ", delimiters=" + b.delimiters + ", escapeString=" + b.escapeString
                        + ", encoding=" + b.encoding + ", changeDetection=" + b.changeDetection + ']';
            }

            private Object[] values() {
                return new Object[] {expressionValues(), copyValues()};
            }

            private Object[] expressionValues() {
                return new Object[] {
                    b.properties,
                    b.callback,
                    b.delimiters,
                    b.escapeString,
                    b.supportMultiLineFiltering,
                    b.escapeWindowsPaths
                };
            }

            private Object[] copyValues() {
                return new Object[] {
                    b.roots,
                    b.outputDirectory,
                    b.baseDirectory,
                    b.encoding,
                    b.propertiesEncoding,
                    b.nonFilteredFileExtensions,
                    b.useDefaultExcludes,
                    b.includeEmptyDirs,
                    b.flatten,
                    b.changeDetection
                };
            }
        }
    }
}
