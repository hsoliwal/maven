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
package org.apache.maven.plugins.codepatch;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The textual rules for a patch path, before it touches the file system. Everywhere: relative, no backslash, colon,
 * control character (below 0x20, and 0x7f: they would break the tab-separated plan and receipt), empty, {@code .} or
 * {@code ..} segment, no {@code .git} segment in any case, and no segment that ends with a dot or a space (Windows
 * drops those, so {@code .git.} would be {@code .git}). On Windows also: none of {@code <>"|?*} and no device name
 * ({@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, {@code COM1}..{@code COM9}, {@code LPT1}..{@code LPT9},
 * {@code CONIN$}, {@code CONOUT$}, {@code CLOCK$}, also with an extension).
 */
final class PathNames {
    private static final Set<String> REFUSED_SEGMENTS = Set.of("", ".", "..", ".git");
    private static final String WINDOWS_CHARACTERS = "<>\"|?*";
    private static final Pattern DEVICE = Pattern.compile("(con|prn|aux|nul|conin\\$|conout\\$|clock\\$|com[0-9¹²³]"
            + "|lpt[0-9¹²³])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private PathNames() { }

    static void check(String relative, boolean windows) throws PatchException {
        if (relative.chars().anyMatch(c -> c < ' ' || c == 0x7f)) throw refused(relative, "has a control character");
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw escape(relative);
        }
        for (String segment : relative.split("/", -1)) segment(relative, segment, windows);
    }

    private static void segment(String relative, String segment, boolean windows) throws PatchException {
        if (REFUSED_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) throw escape(relative);
        if (segment.endsWith(".") || segment.endsWith(" ")) throw refused(relative, "has a name that ends with a dot or a space");
        if (!windows) return;
        if (segment.chars().anyMatch(c -> WINDOWS_CHARACTERS.indexOf(c) >= 0)) {
            throw refused(relative, "has a character that Windows does not allow in names (<>\"|?*)");
        }
        int dot = segment.indexOf('.');
        if (DEVICE.matcher((dot < 0 ? segment : segment.substring(0, dot)).strip()).matches()) {
            throw refused(relative, "has a reserved Windows device name");
        }
    }

    /** The path for a message: as it is, or quoted with escapes when it holds control characters. */
    static String show(String relative) {
        boolean plain = relative.chars().noneMatch(c -> c < ' ' || c == 0x7f);
        return plain ? relative : ByteLines.show(relative.getBytes(StandardCharsets.UTF_8));
    }

    static PatchException refused(String relative, String why) {
        return new PatchException(show(relative) + ": " + why + "; refused");
    }

    static PatchException escape(String relative) {
        return refused(relative, "path escapes the base directory or is not a plain relative path");
    }
}
