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

import java.nio.file.Path;

/**
 * Path spellings handed to native tools. Absolute paths use the mixed form {@code C:/a/b} on Windows, which CMake,
 * Ninja, GNU Make, MSYS sh and the Windows API all accept, and never {@code /c/a/b} (MSYS only) or backslashes (an
 * escape character for sh and make). Relative paths always use forward slashes.
 */
public final class WindowsPaths {
    private WindowsPaths() {}

    /** Absolute, normalized, with forward slashes. */
    public static String mixed(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** {@code target} relative to {@code base} with forward slashes; {@code "."} when they are the same. */
    public static String relative(Path base, Path target) {
        Path from = base.toAbsolutePath().normalize();
        String value = from.relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }

    /** The file name without folders and without its last extension: {@code C:/x/gcc.exe} gives {@code gcc}. */
    public static String stem(String file) {
        String name = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && !name.endsWith(".") && isExtension(name.substring(dot + 1)) ? name.substring(0, dot) : name;
    }

    /** The file name without folders. */
    public static String name(String file) {
        return file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
    }

    private static boolean isExtension(String value) {
        return value.matches("(?i)exe|cmd|bat|com");
    }
}
