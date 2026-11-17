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
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * C-family block headers in the lexical front end: a C++ constructor with a member-initializer list is named after
 * itself (not a member or base it initializes), a destructor is {@code ~Name}, operators are {@code operator+},
 * {@code operator==}, {@code operator[]} and {@code operator bool}, a macro call on the line above does not name the
 * function, and a token-pasting name macro still names its use. A function with {@code #ifdef} lines among its
 * parameters, or {@code #if ... __attribute__ ... #endif} between its parameter list and its brace, is still an atom.
 * In Objective-C, {@code case -kLimit: {} } is not a method, and two C++ operators in {@code .mm} are not both
 * {@code Vec.const}; a method sign counts only as the first token of a header or of a line after macro names.
 */
final class CFamilyTest {
    private static final String CPP = String.join("\n",
            "#include \"ctor.h\"",
            "#define TRACE(x) x",
            "",
            "Foo::Foo(int a) : Base(a), m(0) {",
            "    m = a;",
            "}",
            "Foo::~Foo() {",
            "    release(m);",
            "}",
            "Vec Vec::operator+(const Vec& o) const {",
            "    return Vec(x + o.x);",
            "}",
            "bool Vec::operator==(const Vec& o) const {",
            "    return x == o.x;",
            "}",
            "int& Vec::operator[](int i) {",
            "    return x;",
            "}",
            "TRACE(1)",
            "int Foo::get() const {",
            "    return m;",
            "}",
            "class Bar {",
            "    Bar() : n(1) { }",
            "    ~Bar() { }",
            "    operator bool() const { return n != 0; }",
            "    int n;",
            "};",
            "");
    private static final String C = String.join("\n",
            "#include <jni.h>",
            "#define LIB_NAME(name) Java_p_Lib_##name",
            "",
            "JNIEXPORT jint JNICALL LIB_NAME(pid)(JNIEnv *env, jobject self) {",
            "    return 5;",
            "}",
            "JNIEXPORT void JNICALL Java_p_Lib_cond(JNIEnv *env,",
            "#ifdef _WIN64",
            "    jlong handle,",
            "#else",
            "    jint handle,",
            "#endif",
            "    jobject self) {",
            "    (void) handle;",
            "}",
            "JNIEXPORT void JNICALL Java_p_Lib_hot(JNIEnv *env, jclass cls)",
            "#if defined(__GNUC__)",
            "__attribute__((hot))",
            "#endif",
            "{",
            "    (void) cls;",
            "}",
            "");
    private static final String SWITCH_M = String.join("\n",
            "@implementation Switch",
            "- (int)pick:(int)v {",
            "    switch (v) {",
            "    case -kLimit: {",
            "        return 0;",
            "    }",
            "    default:",
            "        return v;",
            "    }",
            "}",
            "@end",
            "");
    private static final String MACRO_M = String.join("\n",
            "@implementation Window",
            "WINDOW_METHODS",
            "",
            "- (void)post:(int)x as:(int)y {",
            "}",
            "@end",
            "");
    private static final String VEC_MM = String.join("\n",
            "class Vec {",
            "public:",
            "    Vec operator-() const { return Vec(); }",
            "    Vec operator+() const { return *this; }",
            "};",
            "");
    private static int failures;

    private CFamilyTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ctor.cpp"), CPP, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("jni.c"), C, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Switch.m"), SWITCH_M, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Vec.mm"), VEC_MM, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Window.m"), MACRO_M, StandardCharsets.UTF_8);
        List<String> atoms = AtomPlanes.scan(List.of(dir)).stream().map(CFamilyTest::key).collect(Collectors.toList());
        cpp(atoms);
        directives(atoms);
        objectiveC(atoms);
    }

    private static void cpp(List<String> atoms) {
        check("constructor with an initializer list is named after itself", has(atoms, "ctor.cpp METHOD Foo(1) 4-6"));
        check("destructor is ~Foo", has(atoms, "ctor.cpp METHOD ~Foo(0) 7-9"));
        check("operators: operator+, operator==, operator[]", has(atoms, "ctor.cpp METHOD operator+(1) 10-12")
                && has(atoms, "ctor.cpp METHOD operator==(1) 13-15") && has(atoms, "ctor.cpp METHOD operator[](1) 16-18"));
        check("macro call on the line above does not name the function", has(atoms, "ctor.cpp METHOD get(0) 20-22"));
        check("in-class constructor, destructor and conversion operator", has(atoms, "ctor.cpp METHOD Bar.Bar(0) 24-24")
                && has(atoms, "ctor.cpp METHOD Bar.~Bar(0) 25-25") && has(atoms, "ctor.cpp METHOD Bar.operator bool(0) 26-26"));
        check("no atom named after a member or base: " + atoms, atoms.stream().noneMatch(a -> a.contains(" m(")
                || a.contains(" Base(") || a.contains(".n(") || a.contains(".bool(")));
    }

    private static void directives(List<String> atoms) {
        check("token-pasting name macro names its use", has(atoms, "jni.c METHOD LIB_NAME(1) 4-6"));
        check("#ifdef among parameters: still an atom (both branches count)", has(atoms, "jni.c METHOD Java_p_Lib_cond(4) 7-15"));
        check("#if __attribute__ #endif before the brace: still an atom", has(atoms, "jni.c METHOD Java_p_Lib_hot(2) 16-22"));
    }

    private static void objectiveC(List<String> atoms) {
        check("case -kLimit: { is not a method", has(atoms, "Switch.m METHOD Switch.pick: 2-10")
                && atoms.stream().noneMatch(a -> a.contains("kLimit")));
        check(".mm operators are not Vec.const", has(atoms, "Vec.mm METHOD Vec.operator-(0) 3-3")
                && has(atoms, "Vec.mm METHOD Vec.operator+(0) 4-4") && atoms.stream().noneMatch(a -> a.contains("const")));
        check("sign only as the first header token", ObjectiveC.method(Lexer.tokens("case - kLimit :", false)).isEmpty()
                && ObjectiveC.method(Lexer.tokens("Vec operator - ( ) const", false)).isEmpty());
        check("a method after a macro line (AWT_NS_WINDOW_IMPLEMENTATION) is still a method",
                has(atoms, "Window.m METHOD Window.post:as: 4-5"));
        check("a keyword alone is not a selector", ObjectiveC.method(Lexer.tokens("- ( ) const", false)).isEmpty()
                && ObjectiveC.method(Lexer.tokens("- (Class)class", false)).isPresent());
    }

    private static String key(Atom a) {
        return a.path() + " " + a.kind() + " " + a.symbol() + " " + a.line() + "-" + a.endLine();
    }

    private static boolean has(List<String> atoms, String key) {
        boolean found = atoms.contains(key);
        if (!found) System.err.println("  missing atom: " + key);
        return found;
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
