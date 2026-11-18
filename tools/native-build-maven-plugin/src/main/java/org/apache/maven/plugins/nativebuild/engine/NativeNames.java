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
package org.apache.maven.plugins.nativebuild.engine;

import java.util.regex.Pattern;

/**
 * Checks for values that come from parameters and user properties before they reach a native tool's argument list or
 * a path. Make and Ninja read some arguments as code ({@code NAME:=$(shell ...)}, {@code --eval=...}) or options
 * ({@code -t clean}), so targets must be plain names; a classifier names a directory and must not move it.
 */
public final class NativeNames {
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_.+/@~][A-Za-z0-9_.+/@~-]*");
    private static final Pattern CLASSIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern GENERATOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._+-]*");
    private static final Pattern PRESET = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.+:/-]*");
    private static final Pattern BUILD_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PREFIX = Pattern.compile("/|(/[A-Za-z0-9_+-][A-Za-z0-9_.+-]*)+/?");
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.+-]*");
    private static final Pattern RELATIVE = Pattern.compile("[A-Za-z0-9_.+@~][A-Za-z0-9_.+@~/ -]*");

    private NativeNames() {}

    /** A make, ninja or CMake target: no leading dash, no {@code = $ : ,} spaces, quotes or control characters. */
    public static String target(String value) {
        return checked(value, TARGET, "target", "a plain target name such as all, check or src/x.o");
    }

    /** A classifier, which also names build directories: no path separators, no {@code ..}. */
    public static String classifier(String value) {
        String checked = checked(value, CLASSIFIER, "native.classifier", "letters, digits, '_', '.' and '-'");
        if (checked.contains("..")) {
            throw new IllegalArgumentException("native.classifier must not contain '..': " + value);
        }
        return checked;
    }

    /** A CMake generator name such as {@code Ninja Multi-Config} or {@code Visual Studio 17 2022}. */
    public static String generator(String value) {
        return value.isEmpty() ? value : checked(value, GENERATOR, "native.cmake.generator", "a CMake generator name");
    }

    /** A CMake preset name. */
    public static String preset(String value) {
        return value.isEmpty() ? value : checked(value, PRESET, "native.cmake.preset", "a preset name");
    }

    /** A CMake build type such as Release. */
    public static String buildType(String value) {
        return checked(value, BUILD_TYPE, "native.buildType", "Release, Debug, RelWithDebInfo or MinSizeRel");
    }

    /**
     * The install {@code --prefix} (Autotools, CMake): an absolute POSIX path without spaces or shell characters whose
     * segments neither are {@code .} or {@code ..} nor end with a dot (which Windows drops), so
     * {@code <stage>/<prefix>} always stays inside the stage.
     */
    public static String prefix(String value) {
        String checked = checked(value, PREFIX, "native.autotools.prefix", "an absolute path such as /usr/local, "
                + "without '.' or '..' segments");
        if (java.util.Arrays.stream(checked.split("/")).anyMatch(segment -> segment.endsWith("."))) {
            throw new IllegalArgumentException("refused native.autotools.prefix '" + value + "': a segment ends "
                    + "with '.', which Windows drops");
        }
        return checked;
    }

    /** A provisioning version or group id, which also names a folder: no path separators, no {@code ..}. */
    public static String coordinate(String value, String what) {
        String checked = checked(value, COORDINATE, what, "letters, digits, '_', '.', '+' and '-'");
        if (checked.contains("..")) {
            throw new IllegalArgumentException(what + " must not contain '..': " + value);
        }
        return checked;
    }

    /** A makefile or tool name relative to the source directory, without {@code ..} or a leading dash. */
    public static String relative(String value, String what) {
        if (value.isEmpty()) {
            return value;
        }
        String checked = checked(value, RELATIVE, what, "a relative file name");
        if (checked.contains("..")) {
            throw new IllegalArgumentException(what + " must not contain '..': " + value);
        }
        return checked;
    }

    private static String checked(String value, Pattern pattern, String what, String expected) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("refused " + what + " '" + value + "': expected " + expected);
        }
        return value;
    }
}
