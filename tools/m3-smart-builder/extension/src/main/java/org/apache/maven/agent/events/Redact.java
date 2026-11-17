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
package org.apache.maven.agent.events;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Message text fit for an agent file: no credentials of {@code scheme://user:password@host} URLs, no terminal
 * colours, the build root's absolute path written relative, the local repository as {@code <local-repo>} and the home
 * directory as {@code ~}, so files are portable, stable across checkouts and free of user names. Every pattern runs
 * in linear time: a scheme starts only where no scheme character precedes it and is at most 64 characters long, and
 * the possessive quantifiers never backtrack. Messages are clipped before they are redacted. {@link #secrets} is the
 * one credential redaction of the build events extension and of the MCP server, which shades this class.
 */
public final class Redact {
    private static final Pattern CREDENTIALS =
            Pattern.compile("(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]{0,63}+://)[^/\\s@]{1,8192}+@");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*+[A-Za-z]");
    /** A place's name is replaced where it ends: before a separator, a character no file name holds, or the end. */
    private static final String PLACE_END = "(?=[/\\\\)\"'\\s,;:]|$)";
    /** Characters kept before redaction, per character of the final bound, so redaction never meets a huge text. */
    static final int PRE_CLIP_FACTOR = 4;
    /** Characters kept before redaction besides {@link #PRE_CLIP_FACTOR}, for the root paths that redaction drops. */
    static final int PRE_CLIP_SLACK = 4096;

    /**
     * The absolute places a message may name: the build root (its prefix is removed, so paths become root-relative),
     * the local repository (written {@code <local-repo>}) and the home directory (written {@code ~}).
     */
    record Places(Path root, Optional<Path> localRepository, Optional<Path> home) {
        /** Only the root. */
        static Places of(Path root) {
            return new Places(root, Optional.empty(), Optional.empty());
        }
    }

    private Redact() {}

    /** {@code text} with credentials and colours removed. */
    public static String secrets(String text) {
        String plain = ANSI.matcher(text).replaceAll("");
        return CREDENTIALS.matcher(plain).replaceAll("$1***@");
    }

    /** {@code text} with every spelling of {@code root} followed by a separator removed ({@code /C:/r/a} gives a). */
    static String paths(String text, Path root) {
        return places(text, Places.of(root));
    }

    /** {@code text} with the root made relative, then the local repository and the home directory named. */
    static String places(String text, Places places) {
        String relative = spellings(places.root()).stream().reduce(text, (result, spelling) -> result.replaceAll(
                "(?i)" + Pattern.quote(spelling) + "[/\\\\]", ""), (a, b) -> a);
        String repository = places.localRepository().map(repo -> name(relative, repo, "<local-repo>")).orElse(relative);
        return places.home().map(home -> name(repository, home, "~")).orElse(repository);
    }

    private static String name(String text, Path place, String name) {
        return spellings(place).stream().reduce(text, (result, spelling) -> result.replaceAll("(?i)"
                + Pattern.quote(spelling) + PLACE_END, Matcher.quoteReplacement(name)), (a, b) -> a);
    }

    /** {@code /C:/r}, {@code C:/r} and {@code C:\r} of a path, longest first, so no spelling leaves a part behind. */
    private static List<String> spellings(Path place) {
        String forward = place.toAbsolutePath().normalize().toString().replace('\\', '/');
        String trimmed = forward.endsWith("/") ? forward.substring(0, forward.length() - 1) : forward;
        return Stream.of("/" + trimmed, trimmed, trimmed.replace('/', '\\')).distinct().filter(s -> s.length() > 1)
                .collect(Collectors.toList());
    }

    /**
     * A whole message for the digest and {@code mojo-end}: its non-blank lines (trailing white space removed) joined
     * by LF, clipped, cleaned, with its places named, bounded to {@code max} characters.
     */
    static String message(String message, Places places, int max) {
        String text = message == null ? "" : clipFirst(message, max);
        String lines = text.lines().map(String::stripTrailing).filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return JsonText.clip(places(secrets(lines), places).strip(), max);
    }

    /** A whole message, clipped, cleaned, with its places named and bounded. */
    static String clean(String message, Places places, int max) {
        String text = message == null ? "" : clipFirst(message, max);
        return JsonText.clip(places(secrets(text), places).strip(), max);
    }

    private static String clipFirst(String text, int max) {
        int head = (int) Math.min(Integer.MAX_VALUE, (long) max * PRE_CLIP_FACTOR + PRE_CLIP_SLACK);
        return text.length() > head ? JsonText.clip(text, head) : text;
    }
}
