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
package org.apache.maven.plugins.polyglot.adapters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The rule and message vocabulary of build-events {@code diagnostic} lines, as their owner
 * ({@code org.apache.maven.agent.events.Diagnostic}, {@code DiagnosticLines} and {@code Redact} in the smart builder
 * extension) writes them, so a diagnostic this host captures and the same diagnostic read from the build log share
 * one identity (file, line, rule, message). Rule: the {@code -Xlint} key of a javac warning, a {@code [key]} prefix
 * written in lower case, else empty (written as null); a plug-in's {@code [CheckName]} prefix stays in the message.
 * Message: the first line plus javac's {@code symbol:}/{@code location:} continuation lines, credentials and colours
 * removed, the root's absolute path made relative, at most {@link #MESSAGE_CHARS} characters. Ported verbatim from
 * the owner, which is not on master yet; when it lands, this class gives way to it.
 */
final class EventVocabulary {
    /** {@code maven.agent.messageChars} default of the owner. */
    static final int MESSAGE_CHARS = 300;
    static final String ELLIPSIS = "...";
    private static final Pattern CREDENTIALS = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/\\s@]+@");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern CONTINUATION = Pattern.compile("^(symbol|location)\\s*:\\s*(.+)$");
    private static final Pattern LINT_PREFIX = Pattern.compile("^\\[([a-z-]+)\\]\\s*(.*)$");
    /** Javac warning message to -Xlint key, first match wins (javax.tools messages carry no key). */
    private static final List<Map.Entry<Pattern, String>> LINT = List.of(
            Map.entry(Pattern.compile("has been deprecated and marked for removal"), "removal"),
            Map.entry(Pattern.compile("has been deprecated"), "deprecation"),
            Map.entry(Pattern.compile("^found raw type"), "rawtypes"),
            Map.entry(Pattern.compile("^unchecked"), "unchecked"),
            Map.entry(Pattern.compile("^redundant cast"), "cast"),
            Map.entry(Pattern.compile("serializable class"), "serial"),
            Map.entry(Pattern.compile("possible 'this' escape"), "this-escape"),
            Map.entry(Pattern.compile("^non-varargs call"), "varargs"),
            Map.entry(Pattern.compile("^empty statement after if"), "empty"),
            Map.entry(Pattern.compile("fall-through"), "fallthrough"),
            Map.entry(Pattern.compile("^division by zero"), "divzero"),
            Map.entry(Pattern.compile("finally clause cannot complete normally"), "finally"));

    /** Rule ("" when unknown) and message of one event. */
    record Entry(String rule, String message) { }

    private EventVocabulary() { }

    /** A javac diagnostic message ({@code Diagnostic.getMessage}) as the owner reads it from build output. */
    static Entry javac(String message, Path root) {
        List<String> lines = message.lines().toList();
        String first = lines.isEmpty() ? "" : lines.get(0);
        Matcher prefixed = LINT_PREFIX.matcher(first);
        String text = prefixed.matches() ? prefixed.group(2) : first;
        String rule = prefixed.matches() ? prefixed.group(1) : lintKey(text);
        String continued = lines.stream().skip(1).map(line -> CONTINUATION.matcher(line.trim()))
                .filter(Matcher::matches).map(m -> "; " + m.group(1) + ": " + m.group(2).trim().replaceAll("\\s+", " "))
                .collect(Collectors.joining());
        return new Entry(rule, clean(text + continued, root));
    }

    /** The -Xlint key of a javac warning message, or "" when it is not one. */
    static String lintKey(String message) {
        return LINT.stream().filter(entry -> entry.getKey().matcher(message).find()).map(Map.Entry::getValue)
                .findFirst().orElse("");
    }

    /** A whole message, cleaned, made root-relative and bounded (the owner's {@code Redact.clean}). */
    static String clean(String message, Path root) {
        String plain = CREDENTIALS.matcher(ANSI.matcher(message).replaceAll("")).replaceAll("$1***@");
        return clip(paths(plain, root).strip(), MESSAGE_CHARS);
    }

    /** {@code text} with every spelling of {@code root} followed by a separator removed. */
    static String paths(String text, Path root) {
        String forward = root.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<String> spellings = List.of("/" + forward + "/", forward + "/", forward.replace('/', '\\') + "\\");
        return spellings.stream().reduce(text, (result, spelling) -> result.replaceAll(
                "(?i)" + Pattern.quote(spelling), Matcher.quoteReplacement("")), (a, b) -> a);
    }

    /** At most {@code max} characters, the cut marked with {@link #ELLIPSIS}; a surrogate pair is never split. */
    static String clip(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max - ELLIPSIS.length());
        end = end > 0 && Character.isHighSurrogate(value.charAt(end - 1)) ? end - 1 : end;
        return value.substring(0, end) + ELLIPSIS;
    }
}
