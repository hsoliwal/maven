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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

/**
 * The listener of the host's one compilation. It never throws: javac wraps a listener exception into a
 * ClientCodeException that ends the whole compilation, so a diagnostic that cannot be recorded becomes a note of an
 * incomplete run instead. A source that is not a file (a class inside a jar: {@code jar:file:...!/p/C.class}, a
 * {@code jrt:} class) is located by its URI, never through {@code Path.of}. An ERROR without a source whose code
 * says the invocation could not run as asked ({@link #invocation}: a javac plug-in or annotation processor not found,
 * an option refused, a file unreadable) is a note of an incomplete run, not a finding; every other ERROR, also a
 * processor's {@code Messager} error without an element and the {@code -Werror} summary, is a finding. Columns count
 * UTF-16 code units, as SARIF does, not javac's tab-expanded columns.
 */
final class JavacDiagnostics implements DiagnosticListener<JavaFileObject> {
    /** Code prefixes (after {@code compiler.err.}) of invocation failures: plug-ins, locations, options. */
    static final List<String> INVOCATION_PREFIXES = List.of("plugin.", "locn.", "option.", "module.not.found");
    /** Exact codes (after {@code compiler.err.}) of invocation failures: files, flags, values, releases. */
    static final Set<String> INVOCATION = Set.of("file.not.found", "file.not.directory", "file.not.file",
            "error.reading.file", "error.writing.file", "cant.read.file", "class.cant.write", "bad.file.name",
            "invalid.flag", "invalid.path", "invalid.profile", "invalid.source", "invalid.target", "invalid.A.key",
            "illegal.argument.for.option", "bad.name.for.option", "bad.value.for.option", "no.value.for.option",
            "req.arg", "release.bootclasspath.conflict", "profile.bootclasspath.conflict", "add.exports.with.release",
            "add.reads.with.release", "addmods.all.module.path.invalid", "no.output.dir", "no.source.files",
            "no.source.files.classes", "preview.without.source.or.release", "unsupported.release.version",
            "unsupported.encoding");
    private final Path root;
    private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private final Map<URI, Optional<String>> contents = new ConcurrentHashMap<>();

    JavacDiagnostics(Path root) {
        this.root = root;
    }

    @Override public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        try {
            accept(diagnostic);
        } catch (RuntimeException | LinkageError failure) {
            notes.add("a javac diagnostic could not be recorded: " + failure);
        }
    }

    List<Finding> findings() {
        return List.copyOf(findings);
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    private void accept(Diagnostic<? extends JavaFileObject> d) {
        if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() == null && invocation(d.getCode())) {
            notes.add("javac " + d.getCode() + ": " + d.getMessage(Locale.ROOT));
        } else {
            findings.add(finding(d));
        }
    }

    /**
     * javac's codes for an invocation that could not run as asked: a plug-in or processor that cannot be found or
     * loaded, an option or path javac refused, a file it cannot read or write. Every other sourceless ERROR (a
     * processor's {@code Messager} error without an element, the {@code -Werror} summary) is an analysis result.
     */
    static boolean invocation(String code) {
        String key = code == null ? "" : code.replaceFirst("^compiler\\.err\\.", "");
        boolean processor = key.startsWith("proc.") && !"proc.messager".equals(key);
        return processor || INVOCATION_PREFIXES.stream().anyMatch(key::startsWith) || INVOCATION.contains(key);
    }

    /** Plug-in messages start with {@code [CheckName]}: that is the SARIF rule; otherwise javac's diagnostic code is. */
    private Finding finding(Diagnostic<? extends JavaFileObject> d) {
        String message = d.getMessage(Locale.ROOT);
        int close = message.indexOf(']');
        boolean plugin = message.startsWith("[") && close > 1;
        String rule = plugin ? message.substring(1, close) : Objects.requireNonNullElse(d.getCode(), JavacPluginHost.TOOL);
        String text = plugin ? message.substring(close + 1).strip() : message;
        EventVocabulary.Entry event = EventVocabulary.javac(message, root);
        return new Finding(location(d.getSource()), d.getLineNumber(), column(d), level(d.getKind()), rule, text,
                event.rule(), event.message());
    }

    /** Root-relative path of a file source; any other source (jar entry, jrt class) as its absolute URI. */
    private String location(JavaFileObject source) {
        if (source == null) {
            return "";
        }
        URI uri = source.toUri();
        try {
            return "file".equalsIgnoreCase(uri.getScheme()) ? FindingReports.relative(root, Path.of(uri)) : uri.toString();
        } catch (RuntimeException notAPath) {
            return uri.toString();
        }
    }

    private long column(Diagnostic<? extends JavaFileObject> d) {
        long position = d.getPosition();
        long fallback = Math.max(0, d.getColumnNumber());
        if (position == Diagnostic.NOPOS || d.getSource() == null) {
            return fallback;
        }
        return content(d.getSource()).filter(text -> position <= text.length())
                .map(text -> utf16Column(text, (int) position)).orElse(fallback);
    }

    private Optional<String> content(JavaFileObject source) {
        return contents.computeIfAbsent(source.toUri(), uri -> read(source));
    }

    /** The characters javac's positions index (the same decoding as the compilation's file manager). */
    private static Optional<String> read(JavaFileObject source) {
        try {
            return Optional.of(source.getCharContent(true).toString());
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** 1-based column of a character offset: UTF-16 code units after the last line break ({@code \n} or {@code \r}). */
    static long utf16Column(CharSequence text, int position) {
        int start = IntStream.iterate(Math.min(position, text.length()) - 1, i -> i >= 0, i -> i - 1)
                .filter(i -> text.charAt(i) == '\n' || text.charAt(i) == '\r').findFirst().orElse(-1);
        return (long) position - start;
    }

    static Finding.Level level(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Finding.Level.ERROR;
            case WARNING, MANDATORY_WARNING -> Finding.Level.WARNING;
            default -> Finding.Level.NOTE;
        };
    }
}
