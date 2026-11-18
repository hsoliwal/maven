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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Message text fit for an agent file: no credentials (the userinfo of {@code scheme://user:password@host} URLs,
 * {@code password=}-like pairs, {@code Authorization} headers and bearer tokens), no terminal colours, the build
 * root's absolute path written relative, the local repository as {@code <local-repo>} and the home directory as
 * {@code ~}, so files are portable, stable across checkouts and free of user names. A URL's userinfo is found from the
 * literal {@code ://}, whatever precedes it: each run of characters after it up to white space, a quote or an angle
 * bracket is split at every further {@code ://}, and in each part everything before its last {@code @} is userinfo
 * when it holds no '/' or a ':' before its first '/' (a password may hold '/' and '@'; a path such as
 * {@code host/@scope} is no userinfo). Every pattern runs in linear time: each run is matched once, possessively. A
 * message is redacted within a bounded window first, a credential cut at the window's end is masked, and only then
 * is the text shrunk and clipped. {@link #secrets} is the one credential redaction of the build events extension and
 * of the MCP server, which shades this class.
 */
public final class Redact {
    /** A run after {@code ://}: what may hold a URL's userinfo, host and path. */
    private static final Pattern URL_RUN = Pattern.compile("://[^\\s\"'<>]*+");
    /** A {@code name=value} pair whose value is a credential; the value ends at a separator, quote or white space. */
    private static final Pattern PAIR = Pattern.compile("(?i)((?:passw(?:or)?d|passphrase|pwd|token|secret|api[_-]?key"
            + "|access[_-]?key|private[_-]?key|login|credentials?|signature)=)[^\\s&;,\"'<>]++");
    /** An {@code Authorization} header (or {@code authorization=}) and its value, with or without a scheme word. */
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization\\s*+[:=]\\s*+"
            + "(?:(?:basic|bearer|token|digest|negotiate|ntlm)\\s++)?)[^\\s,;\"'<>]++");
    /** A bearer token outside a header: the word bearer and at least eight token characters. */
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s++)[A-Za-z0-9._~+/=-]{8,}+");
    /** What may follow a cut {@code ://} at the end of a window: a userinfo whose '@' was cut away. */
    private static final Pattern OPEN_USERINFO = Pattern.compile("[^\\s\"'<>@]*+");
    private static final String SCHEME_END = "://";
    private static final Pattern SCHEME_SPLIT = Pattern.compile(SCHEME_END, Pattern.LITERAL);
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
        String urls = URL_RUN.matcher(plain).replaceAll(run -> Matcher.quoteReplacement(userinfo(run.group())));
        String pairs = PAIR.matcher(urls).replaceAll("$1***");
        return BEARER.matcher(AUTHORIZATION.matcher(pairs).replaceAll("$1***")).replaceAll("$1***");
    }

    /** A run starting with {@code ://}, each of its {@code ://} parts without userinfo. */
    private static String userinfo(String run) {
        return Arrays.stream(SCHEME_SPLIT.split(run.substring(SCHEME_END.length()), -1)).map(Redact::withoutUserinfo)
                .collect(Collectors.joining(SCHEME_END, SCHEME_END, ""));
    }

    /** One part after {@code ://}: what precedes its last '@' becomes {@code ***} when it is a userinfo. */
    private static String withoutUserinfo(String part) {
        int at = part.lastIndexOf('@');
        String userinfo = at > 0 ? part.substring(0, at) : "";
        int slash = userinfo.indexOf('/');
        int colon = userinfo.indexOf(':');
        boolean credential = !userinfo.isEmpty() && (slash < 0 || colon >= 0 && colon < slash);
        return credential ? "***" + part.substring(at) : part;
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
     * A whole message for the digest and {@code mojo-end}: redacted within its window, then its non-blank lines
     * (trailing white space removed) joined by LF, with its places named, bounded to {@code max} characters.
     */
    static String message(String message, Places places, int max) {
        String lines = window(message, max).lines().map(String::stripTrailing).filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return JsonText.clip(places(lines, places).strip(), max);
    }

    /** A whole message, redacted within its window, with its places named and bounded. */
    static String clean(String message, Places places, int max) {
        return JsonText.clip(places(window(message, max), places).strip(), max);
    }

    /**
     * The first {@code PRE_CLIP_FACTOR * max + PRE_CLIP_SLACK} characters of {@code text}, redacted; when the text was
     * longer, a URL run at the cut that has not reached its '@' yet is masked after its {@code ://}.
     */
    private static String window(String text, int max) {
        int head = (int) Math.min(Integer.MAX_VALUE, (long) max * PRE_CLIP_FACTOR + PRE_CLIP_SLACK);
        if (text == null || text.length() <= head) {
            return text == null ? "" : secrets(text);
        }
        String redacted = secrets(text.substring(0, head));
        int scheme = redacted.lastIndexOf(SCHEME_END);
        boolean open = scheme >= 0 && OPEN_USERINFO.matcher(redacted).region(scheme + SCHEME_END.length(),
                redacted.length()).matches();
        return open ? redacted.substring(0, scheme) + SCHEME_END + "***" : redacted;
    }
}
