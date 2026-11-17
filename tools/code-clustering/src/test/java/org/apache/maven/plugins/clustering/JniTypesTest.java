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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Member types inherited from superclasses and superinterfaces in argument descriptors. A fixture compiled with
 * {@code javac -h}: a class whose superclass declares {@code Entry} (shadowing a top-level {@code Entry} of the same
 * package) and a protected {@code Listener}, and a nested class extending a platform class
 * ({@code java.util.AbstractMap}) that inherits {@code SimpleEntry} from it and {@code Entry} from the interface
 * {@code java.util.Map}. Every long name must be the one {@code javac -h} writes. Then a class whose superclass is not
 * known: a reference argument could be an inherited member type, so its long name is {@code UNRESOLVED}, while a
 * primitive overload keeps its long name.
 */
final class JniTypesTest {
    private static final String BASE = "package p;\n\npublic class Base {\n    public static class Entry { }\n"
            + "    protected interface Listener { }\n}\n";
    private static final String ENTRY = "package p;\n\npublic class Entry { }\n";
    private static final String CHILD = String.join("\n",
            "package p;",
            "",
            "import java.util.AbstractMap;",
            "",
            "public class Child extends Base {",
            "    native void f(Entry e);",
            "    native void f(Listener l);",
            "    native void f();",
            "    static class Deep extends AbstractMap<String, String> {",
            "        native void g(SimpleEntry<String, String> e);",
            "        native void g();",
            "        native void h(Entry e);",
            "        native void h();",
            "        public java.util.Set<java.util.Map.Entry<String, String>> entrySet() { return null; }",
            "    }",
            "}",
            "");
    private static final String ODD = "package u;\n\nclass Odd extends z.Unknown {\n    native void k(String s);\n"
            + "    native void k(int x);\n}\n";
    private static int failures;

    private JniTypesTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        inherited(dir);
        unknownSuperclass(dir);
    }

    /** The inheritance fixture: every computed long name equals the one {@code javac -h} writes. */
    private static void inherited(Path dir) throws Exception {
        Path src = dir.resolve("src");
        write(src.resolve("p/Base.java"), BASE);
        write(src.resolve("p/Entry.java"), ENTRY);
        write(src.resolve("p/Child.java"), CHILD);
        int javac = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-h", dir.resolve("h").toString(), "-d",
                dir.resolve("classes").toString(), src.resolve("p/Base.java").toString(), src.resolve("p/Entry.java").toString(),
                src.resolve("p/Child.java").toString());
        Set<String> headers = headerNames(dir.resolve("h"));
        Set<String> computed = natives(src).stream().map(JniMethod::longName).collect(Collectors.toCollection(TreeSet::new));
        check("javac -h compiles the inheritance fixture: " + javac, javac == 0);
        check("inherited member types: javac -h " + headers + " equal computed " + computed,
                headers.equals(computed) && headers.size() == 7);
    }

    /** A class whose superclass is not known: a reference argument is UNRESOLVED, a primitive overload is not. */
    private static void unknownSuperclass(Path dir) throws Exception {
        Path odd = dir.resolve("odd");
        write(odd.resolve("u/Odd.java"), ODD);
        Set<String> oddNames = natives(odd).stream().map(JniMethod::longName).collect(Collectors.toCollection(TreeSet::new));
        check("unknown superclass: reference argument UNRESOLVED, primitive resolved " + oddNames,
                oddNames.equals(Set.of("UNRESOLVED", "Java_u_Odd_k__I")));
    }

    private static List<JniMethod> natives(Path src) throws Exception {
        AtomFrontEnd.JavacFrontEnd javac = new AtomFrontEnd.JavacFrontEnd();
        AtomPlanes.scan(List.of(src), List.of(javac));
        return javac.natives();
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

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
