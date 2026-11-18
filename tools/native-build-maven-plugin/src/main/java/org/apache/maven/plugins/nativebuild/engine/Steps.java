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
import java.util.Map;
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

    /**
     * Configure inputs: the descriptor files that exist, the toolchain fingerprint when declared, and the markers
     * of the unpacked native dependencies (their zip hashes), so a new dependency build re-runs configure.
     */
    static List<String> configureInputs(PlanContext context, String... descriptors) {
        String fingerprint = context.layout().inBuild(PlanContext.FINGERPRINT);
        boolean declare = context.fingerprintInput() && Files.exists(context.layout().path(fingerprint));
        List<String> markers = existing(context.layout(), context.dependencies().stream()
                .map(d -> Layout.join(d, NativeDependencies.MARKER)).toArray(String[]::new));
        return list(existing(context.layout(), descriptors), declare ? fingerprint : "", markers);
    }

    /** The absolute (mixed) install prefixes of the native dependencies. */
    static List<String> prefixes(PlanContext context) {
        return context.dependencies().stream().map(context.layout()::mixed).toList();
    }

    /** Each prefix's {@code sub} folder, joined with {@code separator}; empty without dependencies. */
    static String joined(PlanContext context, String sub, String separator) {
        return prefixes(context).stream().map(p -> sub.isEmpty() ? p : p + "/" + sub)
                .collect(java.util.stream.Collectors.joining(separator));
    }

    /**
     * The entries of the source directory that make up the sources: everything but target, hidden entries, a
     * published compile_commands.json, the build, stage and reports directories, and a build tree inside the sources
     * (a preset binaryDir such as out/build/x), whose contents change with every build and test.
     */
    static List<String> sourceSet(PlanContext context) throws IOException {
        Layout layout = context.layout();
        String tree = StepPlanner.forSystem(context.system()).buildDirectory(context);
        Set<String> skipped = Stream.of("target", "compile_commands.json", first(layout, layout.build()),
                first(layout, layout.stage()), first(layout, layout.reports()),
                tree.equals(layout.source()) ? "" : first(layout, tree)).collect(Collectors.toSet());
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
        return list(sourceSet(context), List.of(extra));
    }

    /**
     * Inputs of test and install steps: the declared outputs (else the source set), then those of {@code state} (the
     * configure result, such as CMakeCache.txt), the toolchain fingerprint and the {@link BuildDigest} that exist
     * now. A configure, toolchain or relink change therefore re-runs test and install, not only a source change.
     */
    static List<String> afterBuildInputs(PlanContext context, String... state) throws IOException {
        List<String> outputs = context.options().outputs();
        Layout layout = context.layout();
        List<String> chain = existing(layout, list(List.of(state), layout.inBuild(PlanContext.FINGERPRINT),
                layout.inBuild(BuildDigest.FILE)).toArray(String[]::new));
        return list(outputs.isEmpty() ? sourceSet(context) : outputs, chain);
    }

    /**
     * Why make must not install into the stage: its absolute path holds spaces or shell characters (also
     * {@code [ ] { } ~ !}: Git's sh is bash, which expands globs, braces and tildes), which make passes unquoted to
     * the shell (an install would then write elsewhere). Empty when the path is safe.
     */
    static Optional<String> unsafeStage(PlanContext context) {
        String stage = context.layout().mixed(context.layout().stage());
        return stage.matches(".*[\\s$#'\"`;&|()<>*?\\\\\\[\\]{}~!].*") ? Optional.of("the stage path " + stage
                + " holds spaces or shell characters, which make passes unquoted to the shell; set "
                + "native.buildDirectory to a path without them") : Optional.empty();
    }

    /**
     * Environment additions for native dependencies (Make): CPPFLAGS {@code -I<prefix>/include}, LDFLAGS
     * {@code -L<prefix>/lib}, PKG_CONFIG_PATH and CMAKE_PREFIX_PATH, each after the inherited value.
     */
    static Map<String, String> dependencyEnvironment(PlanContext c) {
        if (c.dependencies().isEmpty()) {
            return Map.of();
        }
        String separator = c.platform().windows() ? ";" : ":";
        return Map.of("CPPFLAGS", flags(c, "CPPFLAGS", "-I", "/include"), "LDFLAGS", flags(c, "LDFLAGS", "-L", "/lib"),
                "PKG_CONFIG_PATH", after(c, "PKG_CONFIG_PATH", joined(c, "lib/pkgconfig", separator), separator),
                "CMAKE_PREFIX_PATH", after(c, "CMAKE_PREFIX_PATH", joined(c, "", separator), separator));
    }

    /**
     * On Windows, the folder of the resolved sh (Git for Windows' usr/bin, which also holds mkdir, cp and rm) before
     * PATH, because GNU make starts recipe lines without shell syntax itself and they need those tools too; the
     * shim expands {@code ${PATH}} at run time. Empty elsewhere or without sh.
     */
    static Map<String, String> shellPath(PlanContext c) {
        String sh = c.tool("sh");
        int slash = sh.lastIndexOf('/');
        return c.platform().windows() && c.tools().has("sh") && slash > 0
                ? Map.of("PATH", sh.substring(0, slash) + ";${PATH}") : Map.of();
    }

    /** The environment of make steps: {@link #dependencyEnvironment} and {@link #shellPath}. */
    static Map<String, String> makeEnvironment(PlanContext c) {
        Map<String, String> environment = new java.util.TreeMap<>(dependencyEnvironment(c));
        environment.putAll(shellPath(c));
        return environment;
    }

    private static String flags(PlanContext c, String name, String flag, String sub) {
        return String.join(" ", list(c.environment().getOrDefault(name, ""),
                prefixes(c).stream().map(p -> flag + p + sub).toList()));
    }

    private static String after(PlanContext c, String name, String value, String separator) {
        return String.join(separator, list(c.environment().getOrDefault(name, ""), value));
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
