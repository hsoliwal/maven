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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;

import org.apache.maven.api.Service;
import org.apache.maven.api.annotations.Experimental;
import org.apache.maven.api.annotations.Nonnull;

/**
 * Copies resource roots and replaces expressions in their text on the way (resource filtering).
 * <p>
 * Expressions are found between the {@linkplain ResourceFilterRequest#getDelimiters() delimiters} of the request
 * ({@code ${...}} and {@code @...@} by default) and their values are resolved through the {@link Interpolator}
 * service, so the {@code ${name:-default}} and {@code ${name:+alternate}} operators and nested {@code ${...}}
 * references inside property values work as they do everywhere else in Maven 4. An expression whose value cannot
 * be resolved is copied unchanged. The text between expressions is copied character for character, so line
 * endings ({@code \n}, {@code \r\n}, {@code \r}) and a byte order mark are preserved.
 * <p>
 * The expression syntax, the escape string, the multi-line rule and the change detection modes follow
 * {@code org.apache.maven.shared:maven-filtering}, which Maven 3 plugins keep using unchanged; this service is
 * the opt-in replacement for plugins written against the Maven 4 API.
 *
 * @since 4.2.0
 * @see ResourceFilterRequest
 */
@Experimental
public interface ResourceFilter extends Service {

    /**
     * Copies the files of the request's {@linkplain ResourceFilterRequest#getRoots() source roots} to their target
     * directories, filtering the text of the roots that have {@link org.apache.maven.api.SourceRoot#stringFiltering()}
     * set, except files with a {@linkplain ResourceFilterRequest#getNonFilteredFileExtensions() non-filtered
     * extension}.
     *
     * @param request the copy request, which must have an output directory
     * @return the files that were selected, with whether each was filtered and written
     * @throws ResourceFilterException if a file cannot be read, decoded, filtered or written
     * @throws IllegalArgumentException if the request has no output directory
     */
    @Nonnull
    ResourceFilterResult copy(@Nonnull ResourceFilterRequest request);

    /**
     * Returns a reader that replaces the expressions of the given reader, using the expression settings of the
     * request (properties, callback, delimiters, escape string, multi-line rule, Windows path escaping).
     * The roots, directories, encodings and change detection of the request are not used.
     *
     * @param reader the text to filter
     * @param request the expression settings
     * @return the filtered text; closing it closes {@code reader}
     */
    @Nonnull
    Reader filter(@Nonnull Reader reader, @Nonnull ResourceFilterRequest request);

    /**
     * Filters a string with the expression settings of the request.
     *
     * @param text the text to filter
     * @param request the expression settings
     * @return the filtered text
     * @throws ResourceFilterException if the text cannot be filtered
     * @see #filter(Reader, ResourceFilterRequest)
     */
    @Nonnull
    default String filter(@Nonnull String text, @Nonnull ResourceFilterRequest request) {
        try (Reader reader = filter(new StringReader(text), request);
                StringWriter writer = new StringWriter(text.length())) {
            reader.transferTo(writer);
            return writer.toString();
        } catch (IOException e) {
            throw new ResourceFilterException("Unable to filter text", e);
        }
    }
}
