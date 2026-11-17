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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The one reading of javac arguments, shared by the static-analyzer provider (at plan time) and the host process, so
 * both see the same options, values and sources. An argument that starts with '-' is an option; whether it takes the
 * next argument as its value is javac's own answer ({@code isSupportedOption} of the system compiler and of its
 * standard file manager), except for the forms javac reads as one argument: an option holding ':' or '='
 * ({@code -Xplugin:...}, {@code --release=17}) and {@code -A...}. Every other argument is an operand: a source when
 * it ends in {@code .java}, otherwise a class name. So {@code -cp -d x.java} is a class path "-d" and a source, never
 * a {@code -d}. JDK only.
 *
 * @param arguments the javac arguments as given
 * @param parsed    the options in order, each with its value when it takes one
 * @param sources   the source files
 * @param classes   the class-name operands
 */
record JavacArguments(List<String> arguments, List<Option> parsed, List<String> sources, List<String> classes) {
    /** javac's output folders: classes, generated sources, native headers. javac uses the last of each. */
    static final List<String> OUTPUT_FLAGS = List.of("-d", "-s", "-h");

    /** One option and the value it takes, if any. */
    record Option(String name, Optional<String> value) { }

    /** Reads the arguments with the running JDK's javac option table. */
    static JavacArguments parse(List<String> arguments) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("the javac plug-in host needs a JDK: this Java runtime has no system Java compiler");
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            return parse(arguments, token -> Math.max(compiler.isSupportedOption(token), files.isSupportedOption(token)));
        }
    }

    /** Reads the arguments with {@code supported} as the option table (1: takes a value, 0: none, -1: unknown). */
    static JavacArguments parse(List<String> arguments, ToIntFunction<String> supported) {
        ToIntFunction<String> arity = token -> takesValue(token) ? Math.max(0, supported.applyAsInt(token)) : 0;
        List<Integer> heads = Stream.iterate(0, i -> i < arguments.size(), i -> i + 1 + arity.applyAsInt(arguments.get(i)))
                .toList();
        List<Option> parsed = heads.stream().filter(i -> arguments.get(i).startsWith("-")).map(i -> new Option(
                arguments.get(i), arity.applyAsInt(arguments.get(i)) == 1 && i + 1 < arguments.size()
                        ? Optional.of(arguments.get(i + 1)) : Optional.empty())).toList();
        List<String> operands = heads.stream().map(arguments::get).filter(a -> !a.startsWith("-")).toList();
        return new JavacArguments(List.copyOf(arguments), parsed, operands.stream().filter(a -> a.endsWith(".java"))
                .toList(), operands.stream().filter(a -> !a.endsWith(".java")).toList());
    }

    /** Only a separate option can take the next argument: not an operand, {@code -A...}, or a ':'/'=' form. */
    private static boolean takesValue(String token) {
        return token.startsWith("-") && !token.startsWith("-A") && token.indexOf(':') < 0 && token.indexOf('=') < 0;
    }

    /** The options with their values, in order, as javac receives them. */
    List<String> options() {
        return parsed.stream().flatMap(o -> Stream.concat(Stream.of(o.name()), o.value().stream())).toList();
    }

    /** The value of every occurrence of the option (an empty string where the value is missing). */
    List<String> values(String flag) {
        return parsed.stream().filter(o -> o.name().equals(flag)).map(o -> o.value().orElse("")).toList();
    }

    /**
     * Why the host must not run these arguments: an {@code @argfile} (it would hide options), a patching option (Error
     * Prone's {@code -XepPatchChecks} and {@code -XepPatchLocation}, in place or into a folder, rewrite or add files
     * outside the build's outputs; source changes belong to the approved patcher), or a repeated output folder (javac
     * uses the last one).
     */
    Optional<String> refusal() {
        if (arguments.stream().anyMatch(a -> a.startsWith("@"))) {
            return Optional.of("javac @argfiles are refused: they would hide -d, -s and -h");
        }
        List<String> patching = arguments.stream().flatMap(a -> Arrays.stream(a.split("\\s+")))
                .filter(t -> t.toLowerCase(Locale.ROOT).startsWith("-xeppatch")).toList();
        if (!patching.isEmpty()) {
            return Optional.of("patching options rewrite sources and are refused (patches go through the code-patch"
                    + " patcher with approval): " + patching);
        }
        return OUTPUT_FLAGS.stream().filter(flag -> values(flag).size() > 1).findFirst()
                .map(flag -> "javac output folder option given more than once (javac uses the last): " + flag);
    }
}
