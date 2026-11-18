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

import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.apache.maven.api.services.Interpolator;
import org.apache.maven.api.services.ResourceFilterRequest;

/**
 * Resolves the body of one filtered expression through the {@link Interpolator} service.
 * <p>
 * A body that is a plain name (no {@code $}, braces or backslash) is resolved as {@code ${body}}, so the
 * Interpolator's {@code :-} and {@code :+} operators and nested references in values apply. Any other body is
 * looked up as is, without nested resolution: it cannot be written as one {@code ${...}} expression, and
 * maven-filtering finds no property for such text either. A leading dot is dropped, as plexus-interpolation does.
 */
final class ResourceFilterValues implements Function<String, Optional<String>> {

    /**
     * The Windows path shape of maven-filtering 3.5.0 {@code FilteringUtils.escapeWindowsPath} (Apache-2.0):
     * a drive letter followed by a backslash.
     */
    private static final Pattern WINDOWS_PATH = Pattern.compile("^(.*)[a-zA-Z]:\\\\(.*)");

    private final Interpolator interpolator;
    private final UnaryOperator<String> lookup;
    private final BinaryOperator<String> postprocessor;

    ResourceFilterValues(Interpolator interpolator, ResourceFilterRequest request) {
        this.interpolator = interpolator;
        this.lookup = lookup(request);
        this.postprocessor = request.isEscapeWindowsPaths() ? (name, value) -> escapeWindowsPath(value) : null;
    }

    @Override
    public Optional<String> apply(String body) {
        String name = body.startsWith(".") ? body.substring(1) : body;
        if (!isPlainName(name)) {
            return Optional.ofNullable(lookup.apply(name)).map(value -> postprocess(name, value));
        }
        String expression = "${" + name + "}";
        String value = interpolator.interpolate(expression, lookup, postprocessor, false);
        return expression.equals(value) ? Optional.empty() : Optional.ofNullable(value);
    }

    private String postprocess(String name, String value) {
        return postprocessor == null ? value : postprocessor.apply(name, value);
    }

    /** Request properties, then the session's effective properties, then the request callback. */
    private static UnaryOperator<String> lookup(ResourceFilterRequest request) {
        Map<String, String> properties = request.getProperties();
        Map<String, String> session = request.getSession().getEffectiveProperties();
        UnaryOperator<String> callback = request.getCallback().orElse(name -> null);
        return Interpolator.chain(properties::get, session::get, callback);
    }

    static boolean isPlainName(String name) {
        return !name.isEmpty() && name.chars().noneMatch(c -> c == '$' || c == '{' || c == '}' || c == '\\');
    }

    /**
     * Doubles the backslashes of a value that looks like a Windows path; an already doubled backslash stays
     * doubled. Adapted from maven-filtering {@code FilteringUtils.escapeWindowsPath} (Apache-2.0).
     *
     * @param value the value, may be null
     * @return the escaped value
     */
    static String escapeWindowsPath(String value) {
        if (value == null || value.isBlank() || !WINDOWS_PATH.matcher(value).matches()) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        int from = 0;
        for (int at = value.indexOf('\\'); at >= 0; at = value.indexOf('\\', from)) {
            escaped.append(value, from, at).append("\\\\");
            from = value.startsWith("\\", at + 1) ? at + 2 : at + 1;
        }
        return escaped.append(value, from, value.length()).toString();
    }
}
