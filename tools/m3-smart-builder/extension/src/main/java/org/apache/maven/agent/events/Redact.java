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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message text fit for an agent file: no credentials of {@code scheme://user:password@host} URLs, no terminal
 * colours, and the build root's absolute path written relative, so files are portable and stable across checkouts.
 * Every pattern runs in linear time: a scheme starts only where no scheme character precedes it and is at most 64
 * characters long, and the possessive quantifiers never backtrack. Messages are clipped before they are redacted.
 */
final class Redact {
    private static final Pattern CREDENTIALS =
            Pattern.compile("(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]{0,63}+://)[^/\\s@]{1,8192}+@");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*+[A-Za-z]");
    /** Characters kept before redaction, per character of the final bound, so redaction never meets a huge text. */
    static final int PRE_CLIP_FACTOR = 4;
    /** Characters kept before redaction besides {@link #PRE_CLIP_FACTOR}, for the root paths that redaction drops. */
    static final int PRE_CLIP_SLACK = 4096;

    private Redact() {}

    /** {@code text} with credentials and colours removed. */
    static String secrets(String text) {
        String plain = ANSI.matcher(text).replaceAll("");
        return CREDENTIALS.matcher(plain).replaceAll("$1***@");
    }

    /** {@code text} with every spelling of {@code root} followed by a separator removed ({@code /C:/r/a} gives a). */
    static String paths(String text, Path root) {
        String forward = root.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<String> spellings = List.of("/" + forward + "/", forward + "/", forward.replace('/', '\\') + "\\");
        return spellings.stream().reduce(text, (result, spelling) -> result.replaceAll(
                "(?i)" + Pattern.quote(spelling), Matcher.quoteReplacement("")), (a, b) -> a);
    }

    /** First line of a message, cleaned, made root-relative and bounded. */
    static String firstLine(String message, Path root, int max) {
        String text = message == null ? "" : message.strip();
        int end = text.indexOf('\n');
        return clean(end < 0 ? text : text.substring(0, end), root, max);
    }

    /** A whole message, clipped, cleaned, made root-relative and bounded. */
    static String clean(String message, Path root, int max) {
        String text = message == null ? "" : message;
        int head = (int) Math.min(Integer.MAX_VALUE, (long) max * PRE_CLIP_FACTOR + PRE_CLIP_SLACK);
        String clipped = text.length() > head ? JsonText.clip(text, head) : text;
        return JsonText.clip(paths(secrets(clipped), root).strip(), max);
    }
}
