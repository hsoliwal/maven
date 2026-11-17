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

import java.nio.file.Path;
import java.util.List;

import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Immutable;
import org.apache.maven.api.annotations.Nonnull;

import static java.util.Objects.requireNonNull;

/**
 * Result of {@link ResourceFilter#copy(ResourceFilterRequest)}.
 *
 * @since 4.2.0
 */
@Experimental
@Immutable
public interface ResourceFilterResult extends Result<ResourceFilterRequest> {

    /**
     * {@return every file selected by the include and exclude patterns of the roots, in copy order}
     * Files skipped because an earlier root already wrote their target are included with {@code written} false.
     */
    @Nonnull
    List<CopiedFile> getFiles();

    /**
     * {@return the target files whose content was written by this call}
     */
    @Nonnull
    default List<Path> getWrittenFiles() {
        return getFiles().stream()
                .filter(CopiedFile::written)
                .map(CopiedFile::target)
                .toList();
    }

    /**
     * One selected source file.
     *
     * @param source the source file
     * @param target the target file
     * @param filtered whether expressions in the text were replaced (as opposed to a byte copy)
     * @param written whether the target was written; {@code false} when the change detection found it up to date
     *                or an earlier root already wrote it
     */
    record CopiedFile(@Nonnull Path source, @Nonnull Path target, boolean filtered, boolean written) {
        /**
         * Validates the paths.
         */
        public CopiedFile {
            requireNonNull(source, "source cannot be null");
            requireNonNull(target, "target cannot be null");
        }
    }
}
