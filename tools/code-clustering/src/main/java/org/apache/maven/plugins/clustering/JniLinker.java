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
package org.apache.maven.plugins.clustering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Links the Java methods declared {@code native} to the C, C++ and Objective-C functions that implement them, as the
 * JVM looks them up: a function with the method's short name is LINKED, else one with its long name is
 * LINKED_OVERLOAD, else an entry of a RegisterNatives table is REGISTERED. Only native code that serves the Java file's
 * OS counts ({@link JniOs}); a method that only native code of other OSes names is LINKED_OTHER_OS. A method with none
 * is VM_BOUND when HotSpot implements it ({@link JniVmBound}), else MISSING_NATIVE. A {@code Java_} function that no
 * native method of the scan names is ORPHAN_NATIVE when {@link JniOrphans} reports it. Functions are the METHOD atoms
 * of native files, by their own name, and the functions a macro names ({@link JniMacros}). A table entry fits a method
 * with its name and, when both are known, its signature, of a class the table's file writes as a string literal or
 * defines {@code Java_} functions of; when the file has neither a class literal nor a {@code Java_} function, any class
 * fits. Writes {@value #FILE}: java_path, java_symbol, short_name, long_name, status, native_atoms
 * ({@code path#symbol|...}; a table entry is {@code path#table[name]}, a macro-named function {@code path#MACRO(name)}),
 * os_variants (the OS directories among the native paths, for example {@code share,unix,windows}) and java_os (the
 * Java file's OS directory). Every row is a proposal: the build that compiles and loads the library is the proof.
 */
final class JniLinker {
    static final String FILE = "JNI.tsv";
    static final Set<String> NATIVE = Preprocessor.EXTENSIONS;
    private static final String HEADER = "java_path\tjava_symbol\tshort_name\tlong_name\tstatus\tnative_atoms\tos_variants"
            + "\tjava_os\n";
    private static final List<String> STATES = List.of("LINKED", "LINKED_OVERLOAD", "REGISTERED", "LINKED_OTHER_OS",
            "VM_BOUND", "MISSING_NATIVE", "ORPHAN_NATIVE");
    private static final String LAST = String.valueOf(Character.MAX_VALUE);
    private static final String JAVA = "Java_";

    private final TreeMap<String, List<Function>> functions;
    private final Set<String> classes;
    private final Set<String> javaFiles;
    private final JniRegistrations registrations;
    private final Set<String> used = new HashSet<>();
    private final Map<String, Set<String>> owners = new HashMap<>();

    /** A native function: its file and how a row refers to it. */
    private record Function(String path, String ref) { }

    private record Row(String javaPath, String javaSymbol, String shortName, String longName, String status,
            List<String> refs, String javaOs) {
        String line() {
            return String.join("\t", javaPath, javaSymbol, shortName, longName, status, String.join("|", refs),
                    JniOs.variants(refs), javaOs) + "\n";
        }
    }

    /** What the functions of some OSes give a method: a status (empty when nothing fits) and the native atoms. */
    private record Match(String status, List<String> refs) {
        boolean found() {
            return !status.isEmpty();
        }
    }

    private JniLinker(TreeMap<String, List<Function>> functions, List<JniMethod> natives,
            JniRegistrations registrations) {
        this.functions = functions;
        this.classes = natives.stream().map(JniMethod::className).collect(Collectors.toSet());
        this.registrations = registrations;
        registrations.named().forEach(n -> functions.computeIfAbsent(n.function(), k -> new ArrayList<>())
                .add(new Function(n.path(), n.path() + "#" + n.written())));
        this.javaFiles = functions.entrySet().stream().filter(e -> e.getKey().startsWith(JAVA))
                .flatMap(e -> e.getValue().stream()).map(Function::path).collect(Collectors.toSet());
    }

    /**
     * Writes {@value #FILE} when the javac front ends found a native method or the atoms hold a {@code Java_}
     * function, and removes a stale one otherwise. Returns the summary fragment, empty without JNI.
     */
    static String write(Path out, List<Path> roots, List<Atom> atoms, List<AtomFrontEnd> frontEnds) throws IOException {
        List<AtomFrontEnd.JavacFrontEnd> javac = javac(frontEnds);
        List<JniMethod> natives = javac.stream().flatMap(f -> f.natives().stream()).toList();
        TreeMap<String, List<Function>> functions = functions(atoms);
        if (natives.isEmpty() && functions.keySet().stream().noneMatch(n -> n.startsWith(JAVA))) {
            Files.deleteIfExists(out.resolve(FILE));
            return "";
        }
        JniOrphans orphans = new JniOrphans(javac.stream().flatMap(f -> f.classes().stream()).collect(Collectors.toSet()));
        List<Row> rows = new JniLinker(functions, natives, JniRegistrations.scan(roots)).rows(natives, orphans);
        Files.createDirectories(out);
        Files.writeString(out.resolve(FILE), HEADER + rows.stream().map(Row::line).collect(Collectors.joining()),
                StandardCharsets.UTF_8);
        return summary(natives.size(), rows);
    }

    private static List<AtomFrontEnd.JavacFrontEnd> javac(List<AtomFrontEnd> frontEnds) {
        return frontEnds.stream().filter(AtomFrontEnd.JavacFrontEnd.class::isInstance)
                .map(AtomFrontEnd.JavacFrontEnd.class::cast).toList();
    }

    static boolean nativeFile(Path file) {
        return NATIVE.contains(AtomFrontEnd.extension(file));
    }

    /** The METHOD atoms of native files by their own name: the symbol without owner and parameter count. */
    private static TreeMap<String, List<Function>> functions(List<Atom> atoms) {
        return atoms.stream().filter(a -> a.kind().equals("METHOD") && NATIVE.contains(extension(a.path())))
                .collect(Collectors.groupingBy(a -> name(a.symbol()), TreeMap::new, Collectors.mapping(
                        a -> new Function(a.path(), a.path() + "#" + a.symbol()), Collectors.toCollection(ArrayList::new))));
    }

    private List<Row> rows(List<JniMethod> natives, JniOrphans orphans) {
        List<Row> rows = natives.stream().sorted(Comparator.comparing(JniMethod::path)).map(this::link)
                .collect(Collectors.toCollection(ArrayList::new));
        functions.entrySet().stream().filter(e -> e.getKey().startsWith(JAVA) && !used.contains(e.getKey())
                && orphans.reported(e.getKey())).forEach(e -> rows.add(orphan(e.getKey(), e.getValue())));
        return rows;
    }

    /** The method's status from the native code of its own OS first, then from any OS. */
    private Row link(JniMethod m) {
        String os = JniOs.of(m.path());
        Match any = match(m, path -> true);
        Match local = JniOs.everywhere(os) ? any : match(m, path -> JniOs.compatible(os, JniOs.of(path)));
        String status = local.found() ? local.status() : any.found() ? "LINKED_OTHER_OS"
                : JniVmBound.bound(m) ? "VM_BOUND" : "MISSING_NATIVE";
        return new Row(m.path(), m.symbol(), m.shortName(), m.longName(), status, (local.found() ? local : any).refs(), os);
    }

    /** The functions and table entries in files that {@code at} accepts that fit the method; their names count as used. */
    private Match match(JniMethod m, Predicate<String> at) {
        List<Function> byShort = located(m.shortName(), at);
        List<String> longNames = functions.subMap(m.shortName() + "__", m.shortName() + "__" + LAST).keySet().stream()
                .filter(m::longMatches).filter(n -> !located(n, at).isEmpty()).toList();
        List<String> registered = registered(m, at);
        if (!byShort.isEmpty()) used.add(m.shortName());
        used.addAll(longNames);
        String status = !byShort.isEmpty() ? "LINKED" : !longNames.isEmpty() ? "LINKED_OVERLOAD"
                : registered.isEmpty() ? "" : "REGISTERED";
        Stream<Function> named = Stream.concat(byShort.stream(), longNames.stream().flatMap(n -> located(n, at).stream()));
        return new Match(status, Stream.concat(named.map(Function::ref), registered.stream()).distinct().toList());
    }

    private List<Function> located(String name, Predicate<String> at) {
        return functions.getOrDefault(name, List.of()).stream().filter(f -> at.test(f.path())).toList();
    }

    /** The table entries that fit the method, each followed by the function its pointer names. */
    private List<String> registered(JniMethod m, Predicate<String> at) {
        return registrations.entries().stream().filter(e -> at.test(e.path()) && e.name().equals(m.method()) && fits(e, m))
                .flatMap(e -> entryRefs(e).stream()).toList();
    }

    private boolean fits(JniRegistrations.Entry e, JniMethod m) {
        boolean signature = e.signature().map(s -> !m.resolved() || s.startsWith("(" + m.arguments() + ")")).orElse(true);
        Set<String> owned = owners(e.path());
        return signature && (owned.isEmpty() ? !javaFiles.contains(e.path()) : owned.contains(m.className()));
    }

    /** The entry, then the function its pointer names: defined in the table's file when it is there, else anywhere. */
    private List<String> entryRefs(JniRegistrations.Entry e) {
        List<Function> named = e.function().map(f -> functions.getOrDefault(f, List.of())).orElse(List.of());
        List<Function> local = named.stream().filter(f -> f.path().equals(e.path())).toList();
        e.function().filter(functions::containsKey).ifPresent(used::add);
        return Stream.concat(Stream.of(e.path() + "#" + e.table() + "[" + e.name() + "]"),
                (local.isEmpty() ? named : local).stream().map(Function::ref)).toList();
    }

    /** The classes a table's file registers: every class name it writes as a string literal, and the classes with native methods it defines {@code Java_} functions of. */
    private Set<String> owners(String path) {
        return owners.computeIfAbsent(path, p -> Stream.concat(registrations.classes(p).stream(),
                classes.stream().filter(c -> definesFunctionOf(p, c))).collect(Collectors.toSet()));
    }

    /** Whether the file defines a {@code Java_} function of the class (not of a nested class: {@code _0} follows). */
    private boolean definesFunctionOf(String path, String className) {
        String prefix = JniNames.shortName(className, "");
        return functions.subMap(prefix, prefix + LAST).entrySet().stream()
                .anyMatch(e -> e.getKey().length() > prefix.length() && !Character.isDigit(e.getKey().charAt(prefix.length()))
                        && e.getValue().stream().anyMatch(f -> f.path().equals(path)));
    }

    private static Row orphan(String name, List<Function> defined) {
        int separator = JniNames.overloadSeparator(name);
        return new Row("", "", separator < 0 ? name : name.substring(0, separator), separator < 0 ? "" : name,
                "ORPHAN_NATIVE", defined.stream().map(Function::ref).toList(), "");
    }

    private static String summary(int natives, List<Row> rows) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(Row::status, Collectors.counting()));
        return " | JNI " + natives + " native methods:"
                + STATES.stream().map(s -> " " + s + " " + counts.getOrDefault(s, 0L)).collect(Collectors.joining());
    }

    /** A symbol's own name: {@code Owner.name(2)} gives {@code name}, {@code Vec.operator()(1)} gives {@code operator()}. */
    private static String name(String symbol) {
        String head = symbol.indexOf('(') < 0 ? symbol : symbol.substring(0, symbol.lastIndexOf('('));
        return head.substring(head.lastIndexOf('.') + 1);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
