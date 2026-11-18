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
package org.apache.maven.plugins.clustering.clang;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The clang command that dumps a file's syntax tree, made from the file's compile command: the compiler is replaced
 * by clang (in {@code cl} driver mode for {@code cl} and {@code clang-cl} entries, in {@code g++} mode for C++
 * drivers, so files are read in the same language), the include paths, macros, language standard and target are
 * kept, and the outputs are dropped: the object file, dependency files (also {@code -Wp,-MD,<file>}), compilation
 * database fragments ({@code -MJ}), precompiled headers, the source file itself, so clang writes nothing into the
 * build folder. Then {@code -fsyntax-only -Xclang -ast-dump=json} (or {@code /Zs}), {@code -w}, {@code -ferror-limit=0} (so
 * many errors, for example from stub headers, do not stop clang), {@code -fno-delayed-template-parsing} (clang's
 * default for the MSVC target leaves the bodies of templates that are never instantiated unparsed) and the file are
 * added. A compiler
 * launcher in front of the compiler ({@code ccache}, {@code sccache}, {@code distcc}, {@code icecc}) is dropped.
 */
final class AstCommand {
    private static final Set<String> LAUNCHERS = Set.of("ccache", "sccache", "distcc", "icecc");
    private static final Set<String> CL_DRIVERS = Set.of("cl", "clang-cl");
    private static final Set<String> GNU_DROP = Set.of("-c", "-MD", "-MMD", "-MP", "-M", "-MM", "-fcolor-diagnostics",
            "-fdiagnostics-color", "--");
    private static final Set<String> GNU_DROP_NEXT = Set.of("-o", "-MF", "-MT", "-MQ", "-MJ");
    /** Joined forms of options that write a file next to the build: {@code -MJ<file>}, {@code -Wp,-MD,<file>}. */
    private static final List<String> GNU_DROP_PREFIXES = List.of("-MF", "-MT", "-MQ", "-MJ", "-Wp,-M");
    private static final Set<String> CL_DROP = Set.of("/c", "-c", "/showIncludes", "-showIncludes", "/FS", "-FS", "--");
    private static final List<String> CL_DROP_PREFIXES = List.of("Fo", "Fd", "Fe", "Fa", "Fp", "Yu", "Yc", "Fi");
    /** Options whose value is the next word; the value is kept as it is, never taken for the source file. */
    private static final Set<String> WITH_VALUE = Set.of("-include", "-imacros", "-I", "-isystem", "-iquote",
            "-idirafter", "-iprefix", "-iwithprefix", "-isysroot", "--sysroot", "-D", "-U", "-x", "-Xclang", "-target",
            "-arch", "-F", "-include-pch", "-Xpreprocessor", "/FI", "-FI", "/I", "-imsvc", "/imsvc");

    private AstCommand() { }

    /**
     * @param clang   the clang executable
     * @param command the file's compile command (its own, or a borrowed one with a header language)
     * @param file    the file to dump, absolute
     * @param include extra include folders, searched last (folders of stub headers)
     */
    static List<String> of(Path clang, CompileCommand command, Path file, List<Path> include) {
        List<String> words = withoutLauncher(command.arguments());
        boolean cl = isCl(words);
        List<String> out = new ArrayList<>(List.of(clang.toString()));
        if (cl) out.add("--driver-mode=cl");
        else if (base(words.get(0)).contains("++")) out.add("--driver-mode=g++");
        out.addAll(kept(words.subList(1, words.size()), command, cl));
        include.forEach(dir -> out.add("-I" + dir));
        command.headerLanguage().ifPresent(language -> out.addAll(language(language, cl)));
        out.addAll(cl ? List.of("/Zs", "-Xclang", "-ast-dump=json", "/w", "-ferror-limit=0", "-fno-delayed-template-parsing", "--")
                : List.of("-fsyntax-only", "-Xclang", "-ast-dump=json", "-fno-color-diagnostics", "-w", "-ferror-limit=0",
                        "-fno-delayed-template-parsing"));
        out.add(file.toString());
        return out;
    }

    static boolean isCl(List<String> words) {
        return CL_DRIVERS.contains(base(words.get(0))) || words.contains("--driver-mode=cl");
    }

    private static List<String> language(String language, boolean cl) {
        if (cl) return List.of(language.startsWith("c-") ? "/TC" : "/TP");
        return List.of("-x", language);
    }

    private static List<String> withoutLauncher(List<String> words) {
        return words.size() > 1 && LAUNCHERS.contains(base(words.get(0))) ? words.subList(1, words.size()) : words;
    }

    /** The executable's name in lower case, without folder or {@code .exe}. */
    static String base(String executable) {
        String name = executable.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static List<String> kept(List<String> args, CompileCommand command, boolean cl) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!cl && GNU_DROP_NEXT.contains(a) || cl && a.equals("-o")) i++;
            else if (WITH_VALUE.contains(a) && i + 1 < args.size()) kept.addAll(List.of(a, args.get(++i)));
            else if (!dropped(a, cl) && !isSource(a, command)) kept.add(a);
        }
        return kept;
    }

    private static boolean dropped(String a, boolean cl) {
        if (cl) return CL_DROP.contains(a) || (a.startsWith("/") || a.startsWith("-"))
                && CL_DROP_PREFIXES.stream().anyMatch(prefix -> a.startsWith(prefix, 1));
        return GNU_DROP.contains(a) || GNU_DROP_PREFIXES.stream().anyMatch(a::startsWith)
                || a.startsWith("-o") && a.length() > 2 && !a.startsWith("-objc");
    }

    /** True for the word naming the entry's own source file, relative to its directory or absolute. */
    private static boolean isSource(String word, CompileCommand command) {
        if (word.startsWith("-")) return false;
        try {
            return command.directory().resolve(word).toAbsolutePath().normalize()
                    .equals(command.file().toAbsolutePath().normalize());
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }
}
