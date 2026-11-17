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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shared pieces of the planners: argument lists, declared inputs and the source set. */
final class Steps {
    private Steps() {}

    /**
     * An argument list from strings (empty ones dropped), collections of strings and optional strings, in order.
     * Nothing is split or quoted: each string stays exactly one argument.
     */
    static List<String> list(Object... parts) {
        List<String> result = new ArrayList<>();
        Arrays.stream(parts).forEach(part -> add(result, part));
        return result;
    }

    private static void add(List<String> result, Object part) {
        if (part instanceof Collection<?> values) {
            values.forEach(value -> add(result, value));
        } else if (part instanceof Optional<?> value) {
            value.ifPresent(present -> add(result, present));
        } else if (part instanceof String text && !text.isEmpty()) {
            result.add(text);
        }
    }

    /** The project-relative paths among {@code candidates} that exist now. */
    static List<String> existing(Layout layout, String... candidates) {
        return Arrays.stream(candidates).filter(c -> Files.exists(layout.path(c))).toList();
    }

    /** Configure inputs: the descriptor files that exist plus the toolchain fingerprint when declared. */
    static List<String> configureInputs(PlanContext context, String... descriptors) {
        String fingerprint = context.layout().inBuild(PlanContext.FINGERPRINT);
        boolean declare = context.fingerprintInput() && Files.exists(context.layout().path(fingerprint));
        return list(existing(context.layout(), descriptors), declare ? fingerprint : "");
    }

    /**
     * The entries of the source directory that make up the sources: everything but target, hidden entries, a
     * published compile_commands.json and the build, stage and reports directories.
     */
    static List<String> sourceSet(Layout layout) throws IOException {
        Set<String> skipped = Stream.of("target", "compile_commands.json", first(layout, layout.build()),
                first(layout, layout.stage()), first(layout, layout.reports())).collect(Collectors.toSet());
        try (Stream<Path> list = Files.list(layout.path(layout.source()))) {
            return list.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".") && !skipped.contains(n))
                    .sorted().map(n -> Layout.join(layout.source(), n)).toList();
        }
    }

    /** Inputs of a build step: the source set plus {@code extra}, but only when the user declared outputs. */
    static List<String> buildInputs(PlanContext context, String... extra) throws IOException {
        if (context.options().outputs().isEmpty()) {
            return List.of();
        }
        return list(sourceSet(context.layout()), List.of(extra));
    }

    /** Inputs of test and install steps: the declared outputs, else the source set plus {@code extra}. */
    static List<String> afterBuildInputs(PlanContext context, String... extra) throws IOException {
        List<String> outputs = context.options().outputs();
        return outputs.isEmpty() ? list(sourceSet(context.layout()), List.of(extra)) : outputs;
    }

    /** A Make {@code SHELL=} argument on Windows, where GNU Make otherwise needs sh on PATH for shell recipes. */
    static String makeShell(PlanContext context) {
        return context.platform().windows() && context.tools().has("sh") ? "SHELL=" + context.tool("sh") : "";
    }

    /** {@code CC=...} and {@code CXX=...} for configured compilers only. */
    static List<String> compilerAssignments(PlanContext context) {
        return list(context.tools().configured("cc").map(cc -> "CC=" + cc),
                context.tools().configured("cxx").map(cxx -> "CXX=" + cxx));
    }

    private static String first(Layout layout, String relative) {
        String source = layout.source();
        String inside = ".".equals(source) ? relative : relative.startsWith(source + "/")
                ? relative.substring(source.length() + 1) : "";
        int slash = inside.indexOf('/');
        return slash < 0 ? inside : inside.substring(0, slash);
    }
}
