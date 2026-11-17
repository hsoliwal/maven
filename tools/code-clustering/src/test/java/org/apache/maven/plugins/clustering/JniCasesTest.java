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

/**
 * JNI name forms and statuses beyond {@link JniLinkerTest}: an object-like alias {@code #define FN Java_...} defined
 * once per {@code #if} branch, a name macro defined in a header and used in a {@code .c} file, a table written
 * {@code name[] {...}} without {@code =}, a function-defining macro whose instance passes the full {@code Java_} name
 * (as in AccessBridge), a table whose file names an unscanned class (so a scanned class with a same-named method stays
 * missing), the Java file's OS ({@code java_os}, LINKED_OTHER_OS), functions of an anonymous class that are not
 * orphans, methods HotSpot binds itself (VM_BOUND), and a {@code Java_} helper outside every scanned package that is
 * not an orphan.
 */
final class JniCasesTest {
    private static final Map<String, String> TREE = Map.ofEntries(
            Map.entry("share/classes/p/Alias.java", String.join("\n", "package p;", "class Alias {",
                    "    static native int batchAbi0();", "    static native int batchCudaAbi0();",
                    "    static native int fromHeader(int x);", "    native int braced();",
                    "    native void focusGained(Object e);", "}", "")),
            Map.entry("share/native/libp/alias.h", "#define ALIAS_NAME(n) Java_p_Alias_##n\n"),
            Map.entry("share/native/libp/Alias.c", String.join("\n", "#include \"alias.h\"", "#ifdef CUDA",
                    "#define ABI_FN Java_p_Alias_batchCudaAbi0", "#else", "#define ABI_FN Java_p_Alias_batchAbi0", "#endif",
                    "JNIEXPORT jint JNICALL ABI_FN(JNIEnv *env, jclass cls) {", "    return 1;", "}",
                    "JNIEXPORT jint JNICALL ALIAS_NAME(fromHeader)(JNIEnv *env, jclass cls, jint x) {", "    return x;", "}",
                    "#define HANDLE(function, method) \\", "    JNIEXPORT void JNICALL \\",
                    "    function(JNIEnv *env, jobject self, jobject e) { \\", "        method(env, e); \\", "    }",
                    "HANDLE(Java_p_Alias_focusGained, fireFocusGained)", "")),
            Map.entry("share/native/libp/Braced.cpp", String.join("\n",
                    "static jint braced(JNIEnv *env, jobject self) { return 3; }", "static JNINativeMethod methods[] {",
                    "    {\"braced\", \"()I\", (void *)&braced},", "};", "")),
            Map.entry("share/classes/p/Owned.java", "package p;\n\nclass Owned {\n    native int id();\n}\n"),
            Map.entry("share/native/libp/Other.c", String.join("\n",
                    "static jint otherId(JNIEnv *env, jobject self) { return 1; }",
                    "static JNINativeMethod otherMethods[] = {", "    {\"id\", \"()I\", (void *)otherId},", "};",
                    "jint registerOther(JNIEnv *env) {", "    jclass c = (*env)->FindClass(env, \"q/Unscanned\");",
                    "    return (*env)->RegisterNatives(env, c, otherMethods, 1);", "}", "")),
            Map.entry("windows/classes/p/Win.java", "package p;\n\nclass Win {\n    native void only();\n    native void both();\n}\n"),
            Map.entry("unix/native/libp/Win_unix.c", "void Java_p_Win_only(JNIEnv *env, jobject self) { }\n"
                    + "void Java_p_Win_both(JNIEnv *env, jobject self) { }\n"),
            Map.entry("windows/native/libp/Win.c", "void Java_p_Win_both(JNIEnv *env, jobject self) { }\n"),
            Map.entry("linux/classes/p/Lin.java", "package p;\n\nclass Lin {\n    native void f();\n}\n"),
            Map.entry("unix/native/libp/Lin.c", "void Java_p_Lin_f(JNIEnv *env, jobject self) { }\n"),
            Map.entry("macosx/classes/p/Mac.java", "package p;\n\nclass Mac {\n    native void g();\n}\n"),
            Map.entry("linux/native/libp/Mac.c", "void Java_p_Mac_g(JNIEnv *env, jobject self) { }\n"),
            Map.entry("share/classes/p/Anon.java", String.join("\n", "package p;", "class Anon {",
                    "    Runnable r = new Runnable() {", "        native void tick();", "        public void run() { tick(); }",
                    "    };", "}", "")),
            Map.entry("share/native/libp/Anon.c", "void Java_p_Anon_000241_tick(JNIEnv *env, jobject self) { }\n"
                    + "void Java_p_Gone_vanished(JNIEnv *env, jclass cls) { }\n"
                    + "static void Java_CTProvider(JNIEnv *env, jint x) { }\n"
                    + "void Java_org_other_Lib_run(JNIEnv *env, jclass cls) { }\n"),
            Map.entry("share/classes/java/lang/Object.java", "package java.lang;\n\npublic class Object {\n"
                    + "    public native int hashCode();\n}\n"),
            Map.entry("share/classes/java/lang/invoke/MethodHandle.java", String.join("\n", "package java.lang.invoke;",
                    "public abstract class MethodHandle {",
                    "    public final native Object invokeExact(Object... args) throws Throwable;",
                    "    native void notPolymorphic(int x);", "}", "")),
            Map.entry("share/classes/jdk/internal/misc/Unsafe.java", "package jdk.internal.misc;\n\npublic final class Unsafe {\n"
                    + "    public native int getInt(Object o, long offset);\n}\n"));
    private static int failures;

    private JniCasesTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Path root = dir.resolve("tree");
        for (Map.Entry<String, String> e : TREE.entrySet()) write(root.resolve(e.getKey()), e.getValue());
        Path out = dir.resolve("out");
        String line = CodeClustering.cluster(out, List.of(root), 12, 3);
        check("summary " + line, line.endsWith(" | JNI 14 native methods: LINKED 6 LINKED_OVERLOAD 0 REGISTERED 1"
                + " LINKED_OTHER_OS 2 VM_BOUND 3 MISSING_NATIVE 2 ORPHAN_NATIVE 1"));
        List<String> rows = Files.readAllLines(out.resolve(JniLinker.FILE), StandardCharsets.UTF_8);
        check("header with java_os", rows.get(0).equals(
                "java_path\tjava_symbol\tshort_name\tlong_name\tstatus\tnative_atoms\tos_variants\tjava_os"));
        macros(rows);
        owners(rows);
        os(rows);
        orphans(rows);
        vmBound(rows);
    }

    private static final String ALIAS = "share/classes/p/Alias.java\tAlias.";
    private static final String ALIAS_C = "share/native/libp/Alias.c#";

    private static void macros(List<String> rows) {
        check("object-like alias, both #if branches", has(rows, ALIAS + "batchAbi0()\tJava_p_Alias_batchAbi0\t"
                + "Java_p_Alias_batchAbi0__\tLINKED\t" + ALIAS_C + "ABI_FN\tshare\tshare")
                && has(rows, ALIAS + "batchCudaAbi0()\tJava_p_Alias_batchCudaAbi0\tJava_p_Alias_batchCudaAbi0__\tLINKED\t"
                + ALIAS_C + "ABI_FN\tshare\tshare"));
        check("name macro defined in a header", has(rows, ALIAS + "fromHeader(int)\tJava_p_Alias_fromHeader\t"
                + "Java_p_Alias_fromHeader__I\tLINKED\t" + ALIAS_C + "ALIAS_NAME(fromHeader)\tshare\tshare"));
        check("table name[] { without =", has(rows, ALIAS + "braced()\tJava_p_Alias_braced\tJava_p_Alias_braced__\t"
                + "REGISTERED\tshare/native/libp/Braced.cpp#methods[braced]|share/native/libp/Braced.cpp#braced(2)\tshare"));
        check("defining macro given the full Java_ name", has(rows, ALIAS + "focusGained(Object)\tJava_p_Alias_focusGained\t"
                + "Java_p_Alias_focusGained__Ljava_lang_Object_2\tLINKED\t" + ALIAS_C
                + "HANDLE(Java_p_Alias_focusGained,fireFocusGained)\tshare\tshare"));
    }

    private static void owners(List<String> rows) {
        check("table of a file naming an unscanned class: missing", has(rows, "share/classes/p/Owned.java\tOwned.id()\t"
                + "Java_p_Owned_id\tJava_p_Owned_id__\tMISSING_NATIVE\t\t\tshare"));
    }

    private static void os(List<String> rows) {
        check("windows class, function only under unix: LINKED_OTHER_OS", has(rows, "windows/classes/p/Win.java\tWin.only()\t"
                + "Java_p_Win_only\tJava_p_Win_only__\tLINKED_OTHER_OS\tunix/native/libp/Win_unix.c#Java_p_Win_only(2)\tunix\twindows"));
        check("windows class: only the windows function counts", has(rows, "windows/classes/p/Win.java\tWin.both()\t"
                + "Java_p_Win_both\tJava_p_Win_both__\tLINKED\twindows/native/libp/Win.c#Java_p_Win_both(2)\twindows\twindows"));
        check("linux class, unix function: LINKED", has(rows, "linux/classes/p/Lin.java\tLin.f()\tJava_p_Lin_f\t"
                + "Java_p_Lin_f__\tLINKED\tunix/native/libp/Lin.c#Java_p_Lin_f(2)\tunix\tlinux"));
        check("macosx class, linux function: LINKED_OTHER_OS", has(rows, "macosx/classes/p/Mac.java\tMac.g()\tJava_p_Mac_g\t"
                + "Java_p_Mac_g__\tLINKED_OTHER_OS\tlinux/native/libp/Mac.c#Java_p_Mac_g(2)\tlinux\tmacosx"));
    }

    private static void orphans(List<String> rows) {
        check("anonymous class function is not an orphan", rows.stream().noneMatch(r -> r.contains("Java_p_Anon_000241_tick")));
        check("Java_ helpers outside the scanned packages are not orphans", rows.stream()
                .noneMatch(r -> r.contains("Java_CTProvider") || r.contains("Java_org_other_Lib_run")));
        check("orphan in a scanned package", has(rows, "\t\tJava_p_Gone_vanished\t\tORPHAN_NATIVE\t"
                + "share/native/libp/Anon.c#Java_p_Gone_vanished(2)\tshare\t"));
    }

    private static void vmBound(List<String> rows) {
        check("Object, Unsafe and a signature-polymorphic method are VM_BOUND", has(rows,
                "share/classes/java/lang/Object.java\tObject.hashCode()\tJava_java_lang_Object_hashCode\t"
                + "Java_java_lang_Object_hashCode__\tVM_BOUND\t\t\tshare")
                && has(rows, "share/classes/jdk/internal/misc/Unsafe.java\tUnsafe.getInt(Object,long)\t")
                && rows.stream().anyMatch(r -> r.contains("Unsafe.getInt") && r.contains("\tVM_BOUND\t"))
                && rows.stream().anyMatch(r -> r.contains("MethodHandle.invokeExact(Object[])") && r.contains("\tVM_BOUND\t")));
        check("a MethodHandle native that is not signature-polymorphic stays missing", rows.stream()
                .anyMatch(r -> r.contains("MethodHandle.notPolymorphic(int)") && r.contains("\tMISSING_NATIVE\t")));
    }

    private static boolean has(List<String> rows, String prefix) {
        boolean found = rows.stream().anyMatch(r -> r.startsWith(prefix));
        if (!found) System.err.println("  missing row starting with: " + prefix.replace("\t", "<TAB>"));
        return found;
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
