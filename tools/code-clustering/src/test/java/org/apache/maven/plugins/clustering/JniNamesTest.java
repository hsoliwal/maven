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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * JNI name checks. The escaping cases of the JNI specification: underscores, a nested class ({@code $}), arrays and
 * reference types in an argument descriptor, an overload without arguments, non-ASCII characters (lower-case hex,
 * surrogate halves one by one). The long-name pattern for an unresolved argument type, and the overload separator of
 * a function name. Then a differential check against the JDK: a fixture with native methods (overloads, arrays, a
 * nested class, a type variable, imported, same-package, on-demand and nested platform types, varargs, a non-ASCII
 * name) is compiled with {@code javac -h}, and every function name in the generated headers must be the name that
 * the javac front end and {@link JniNames} give the method: the long name for an overloaded one, else the short one.
 */
final class JniNamesTest {
    private static final String LIB = String.join("\n",
            "package p;",
            "",
            "import java.util.List;",
            "import java.util.Map;",
            "import java.util.*;",
            "import q.Helper;",
            "",
            "public class My_Lib<E extends Number> {",
            "    native int f(int x);",
            "    native int f(String s, int[][] grid);",
            "    native int f(List<String> l, Map.Entry<String, String> e, Helper h, Peer peer);",
            "    native <T extends CharSequence> void g(T t, E e, Set<T> s);",
            "    native <T extends CharSequence> void g(T[] ts);",
            "    native void größe();",
            "    static native void f_g(Object... rest);",
            "    public static class Inner {",
            "        native void run(Inner other, byte[] data);",
            "        native void run();",
            "    }",
            "}",
            "");
    private static int failures;

    private JniNamesTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        escaping();
        patterns();
        javacHeaders(dir);
    }

    private static void escaping() {
        check("escape: '/' and '_'", JniNames.escape("my_pkg/A_B").equals("my_1pkg_A_1B"));
        check("escape: nested class '$'", JniNames.escape("p/Outer$Inner").equals("p_Outer_00024Inner"));
        check("escape: arrays and ';'", JniNames.escape("[I[Ljava/lang/String;").equals("_3I_3Ljava_lang_String_2"));
        check("escape: non-ASCII, lower-case hex", JniNames.escape("größe").equals("gr_000f6_000dfe"));
        check("escape: surrogate halves", JniNames.escape("𝄞").equals("_0d834_0dd1e"));
        check("short name", JniNames.shortName("java/lang/Object", "hashCode").equals("Java_java_lang_Object_hashCode"));
        check("long name of an overload without arguments", JniNames.longName("Java_p_C_f", "").equals("Java_p_C_f__"));
        JniMethod m = new JniMethod("p/C.java", "C.f(int[],String)", "p/C", "f", "[ILjava/lang/String;");
        check("long name: " + m.longName(), m.longName().equals("Java_p_C_f___3ILjava_lang_String_2"));
    }

    private static void patterns() {
        JniMethod open = new JniMethod("p/C.java", "C.f(X,int)", "p/C", "f", JniNames.UNKNOWN + "I");
        check("unresolved long name", open.longName().equals("UNRESOLVED") && !open.resolved());
        check("unresolved: one class for the unknown type", open.longMatches("Java_p_C_f__Lq_r_X_2I"));
        check("unresolved: two classes are not one", !open.longMatches("Java_p_C_f__Lq_X_2Lr_Y_2I"));
        check("unresolved: primitive is not a class", !open.longMatches("Java_p_C_f__II"));
        check("separator after short name", JniNames.overloadSeparator("Java_p_C_f__I") == 10);
        check("separator before an array", JniNames.overloadSeparator("Java_p_C_f___3I") == 10);
        check("separator of an overload without arguments", JniNames.overloadSeparator("Java_p_C_f__") == 10);
        check("no separator: package starting with '_'", JniNames.overloadSeparator("Java_a__1b_C_f") == -1);
        check("no separator: short name", JniNames.overloadSeparator("Java_p_Outer_00024Inner_run") == -1);
    }

    /** Compiles the fixture with {@code javac -h} and compares the header names with the computed ones. */
    private static void javacHeaders(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("p/My_Lib.java"), LIB);
        write(src.resolve("p/Peer.java"), "package p;\n\nclass Peer { }\n");
        write(src.resolve("q/Helper.java"), "package q;\n\npublic class Helper { }\n");
        int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-h",
                dir.resolve("h").toString(), "-d", dir.resolve("classes").toString(), src.resolve("p/My_Lib.java").toString(),
                src.resolve("p/Peer.java").toString(), src.resolve("q/Helper.java").toString());
        Set<String> headers = headerNames(dir.resolve("h"));
        Set<String> computed = computedNames(src);
        check("javac -h compiles the fixture: " + javac, javac == 0);
        check("javac -h names " + headers + " equal computed " + computed, headers.equals(computed) && headers.size() == 9);
    }

    private static Set<String> headerNames(Path dir) throws Exception {
        Pattern name = Pattern.compile("JNICALL\\s+(Java_[A-Za-z0-9_]+)");
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path header : files.collect(Collectors.toList())) {
                Matcher m = name.matcher(Files.readString(header, StandardCharsets.UTF_8));
                while (m.find()) names.add(m.group(1));
            }
        }
        return names;
    }

    private static Set<String> computedNames(Path src) throws Exception {
        AtomFrontEnd.JavacFrontEnd javac = new AtomFrontEnd.JavacFrontEnd();
        AtomPlanes.scan(List.of(src), List.of(javac));
        List<JniMethod> natives = javac.natives();
        Map<String, Long> overloads = natives.stream()
                .collect(Collectors.groupingBy(m -> m.className() + "." + m.method(), Collectors.counting()));
        Function<JniMethod, String> name = m -> overloads.get(m.className() + "." + m.method()) > 1 ? m.longName() : m.shortName();
        return natives.stream().map(name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
