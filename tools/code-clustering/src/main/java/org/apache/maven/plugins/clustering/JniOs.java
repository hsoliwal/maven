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
package org.apache.maven.plugins.clustering;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * OS directories in JDK-layout paths ({@code share/classes}, {@code unix/native}, {@code windows/native}): the OS of a
 * path is its first directory that is one of {@link #OS}. A Java file under an OS directory takes its natives from
 * the same OS, from {@code share}, and within the unix family ({@code unix} and {@code posix} with {@code linux},
 * {@code macosx}, {@code macos}, {@code aix}, {@code bsd}, {@code solaris}, {@code android}, {@code ios}) from the
 * generic {@code unix} or {@code posix} directories, or for a generic one from any member. {@code windows} and
 * {@code win32}, and {@code macosx} and {@code macos}, are the same OS. A path without an OS directory, or under
 * {@code share}, fits every OS.
 */
final class JniOs {
    static final Set<String> OS = Set.of("share", "unix", "linux", "macosx", "macos", "windows", "win32", "aix", "bsd",
            "solaris", "posix", "android", "ios");
    private static final String SHARE = "share";
    private static final Set<String> UNIX = Set.of("unix", "posix", "linux", "macosx", "macos", "aix", "bsd", "solaris",
            "android", "ios");
    private static final Set<String> GENERIC_UNIX = Set.of("unix", "posix");
    private static final List<Set<String>> SAME = List.of(Set.of("windows", "win32"), Set.of("macosx", "macos"), GENERIC_UNIX);

    private JniOs() { }

    /** The first directory of the path that is an OS directory; empty when there is none. */
    static String of(String path) {
        String[] parts = path.split("/");
        return Arrays.stream(parts, 0, Math.max(0, parts.length - 1)).filter(OS::contains).findFirst().orElse("");
    }

    /** Whether a Java file under {@code javaOs} may take natives from {@code nativeOs}. */
    static boolean compatible(String javaOs, String nativeOs) {
        return everywhere(javaOs) || everywhere(nativeOs) || javaOs.equals(nativeOs)
                || SAME.stream().anyMatch(s -> s.contains(javaOs) && s.contains(nativeOs))
                || UNIX.contains(javaOs) && UNIX.contains(nativeOs)
                        && (GENERIC_UNIX.contains(javaOs) || GENERIC_UNIX.contains(nativeOs));
    }

    /** Whether code under this OS directory serves every OS: none, or {@code share}. */
    static boolean everywhere(String os) {
        return os.isEmpty() || os.equals(SHARE);
    }

    /** The OS directories among the directories of {@code path#symbol} references, sorted and comma-separated. */
    static String variants(List<String> refs) {
        return refs.stream().map(r -> r.substring(0, Math.max(0, r.lastIndexOf('/', r.indexOf('#')))))
                .flatMap(dir -> Arrays.stream(dir.split("/"))).filter(OS::contains)
                .collect(Collectors.toCollection(TreeSet::new)).stream().collect(Collectors.joining(","));
    }
}
