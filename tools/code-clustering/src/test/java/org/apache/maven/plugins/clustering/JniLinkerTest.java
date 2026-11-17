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

/**
 * JNI linking on a JDK-shaped tree: Java classes under {@code share/classes}, C under {@code share/native},
 * {@code unix/native} and {@code windows/native}, Objective-C under {@code macosx/native}. It checks a method linked
 * by its short name in four OS variants, overloads linked by their long names (one through an unresolved argument
 * type), a nested class, a method bound by a RegisterNatives table, a table entry whose signature does not fit, a
 * table that names its class with {@code FindClass} (so a same-named method of another class stays missing), a
 * method with no function, a {@code Java_} function with no declaration, a function named through a token-pasting
 * macro under {@code linux/native}, functions defined by instances of a macro, a function after an {@code #if} line,
 * the summary line, and that JNI.tsv goes away when the sources no longer have JNI code.
 */
final class JniLinkerTest {
    private static final String LIB = String.join("\n",
            "package p;",
            "",
            "import java.util.List;",
            "",
            "public class Lib {",
            "    private static native void registerNatives();",
            "    static native int open(String path);",
            "    native void write(byte[] data, int off);",
            "    native long seek(long pos);",
            "    native long seek(long pos, int whence);",
            "    native void close();",
            "    native boolean flush(List<String> lines);",
            "    native void sync(Missing m);",
            "    native void put(Missing m);",
            "    native void put(int x);",
            "    native int id();",
            "    native int pid();",
            "    native void sortInt0(int[] a);",
            "    native void sortLong0(long[] a);",
            "    native void flushAll();",
            "    native int size();",
            "",
            "    static class Cursor {",
            "        native int next();",
            "    }",
            "}",
            "");
    private static final String PEER = "package p;\n\nclass Peer {\n    native int id();\n}\n";
    private static final String SHARE_C = String.join("\n",
            "#include <jni.h>",
            "",
            "static void closeImpl(JNIEnv *env, jobject self) {",
            "    (void) env;",
            "}",
            "",
            "static JNINativeMethod methods[] = {",
            "    {\"close\", \"()V\", (void *)&closeImpl},",
            "    {\"flush\", \"(I)Z\", (void *)&closeImpl},",
            "};",
            "",
            "JNIEXPORT void JNICALL Java_p_Lib_registerNatives(JNIEnv *env, jclass cls) {",
            "    (*env)->RegisterNatives(env, cls, methods, sizeof(methods) / sizeof(methods[0]));",
            "}",
            "JNIEXPORT jint JNICALL Java_p_Lib_open(JNIEnv *env, jclass cls, jstring path) { return 0; }",
            "JNIEXPORT jlong JNICALL Java_p_Lib_seek__J(JNIEnv *env, jobject self, jlong pos) { return pos; }",
            "JNIEXPORT jlong JNICALL Java_p_Lib_seek__JI(JNIEnv *env, jobject self, jlong pos, jint w) { return pos + w; }",
            "JNIEXPORT void JNICALL Java_p_Lib_sync(JNIEnv *env, jobject self, jobject m) { }",
            "JNIEXPORT void JNICALL Java_p_Lib_put__Lq_Missing_2(JNIEnv *env, jobject self, jobject m) { }",
            "JNIEXPORT void JNICALL Java_p_Lib_put__I(JNIEnv *env, jobject self, jint x) { }",
            "JNIEXPORT jint JNICALL Java_p_Lib_00024Cursor_next(JNIEnv *env, jobject self) { return 1; }",
            "JNIEXPORT void JNICALL Java_p_Gone_vanished(JNIEnv *env, jclass cls) { }",
            "");
    private static final String WINDOWS_C = String.join("\n",
            "#include <jni.h>",
            "JNIEXPORT jint JNICALL Java_p_Lib_open(JNIEnv *env, jclass cls, jstring path) { return 1; }",
            "JNIEXPORT void JNICALL Java_p_Lib_write(JNIEnv *env, jobject self, jbyteArray d, jint off) { }",
            "");
    private static final String UNIX_C = String.join("\n",
            "#include <jni.h>",
            "#if !defined(LEGACY_ABI)",
            "JNIEXPORT jint JNICALL",
            "Java_p_Lib_open(JNIEnv *env, jclass cls, jstring path) { return 2; }",
            "#endif",
            "");
    private static final String SORT_C = String.join("\n",
            "#include <jni.h>",
            "#define SORT(NAME, ARRAY) \\",
            "JNIEXPORT void JNICALL Java_p_Lib_sort##NAME##0(JNIEnv *env, jobject self, ARRAY a) { \\",
            "    (void) a; \\",
            "}",
            "SORT(Int, jintArray)",
            "SORT(Long, jlongArray)",
            "",
            "JNIEXPORT void JNICALL Java_p_Lib_flushAll(JNIEnv *env, jobject self) {",
            "}",
            "");
    private static final String SIZE_CPP = String.join("\n",
            "#include <jni.h>",
            "extern \"C\" JNIEXPORT jint JNICALL Java_p_Lib_size(JNIEnv *env, jobject self) noexcept(true) {",
            "    return 0;",
            "}",
            "");
    private static final String PEER_C = String.join("\n",
            "#include <jni.h>",
            "static jint peerId(JNIEnv *env, jobject self) { return 7; }",
            "static const JNINativeMethod peerMethods[] = {",
            "    {\"id\", \"()I\", (void *)peerId},",
            "};",
            "jint registerPeer(JNIEnv *env) {",
            "    jclass c = (*env)->FindClass(env, \"p/Peer\");",
            "    return (*env)->RegisterNatives(env, c, peerMethods, 1);",
            "}",
            "");
    private static final String LINUX_MACRO_C = String.join("\n",
            "#include <jni.h>",
            "#define LIB_NAME(name) Java_p_Lib_##name",
            "JNIEXPORT jint JNICALL LIB_NAME(pid)(JNIEnv *env, jobject self);",
            "JNIEXPORT jint JNICALL LIB_NAME(pid)(JNIEnv *env, jobject self) {",
            "    return 5;",
            "}",
            "");
    private static final String MACOSX_M = String.join("\n",
            "#import <Foundation/Foundation.h>",
            "#include <jni.h>",
            "",
            "@implementation PathHelper",
            "- (NSString *)normalize:(NSString *)path {",
            "    return [path stringByStandardizingPath];",
            "}",
            "@end",
            "",
            "JNIEXPORT jint JNICALL Java_p_Lib_open(JNIEnv *env, jclass cls, jstring path) { return 3; }",
            "");
    private static int failures;

    private JniLinkerTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Path out = dir.resolve("out");
        String line = CodeClustering.cluster(out, List.of(tree(dir.resolve("tree"))), 12, 3);
        check("jni summary " + line, line.endsWith(" | JNI 18 native methods: LINKED 10 LINKED_OVERLOAD 4 REGISTERED 2"
                + " LINKED_OTHER_OS 0 VM_BOUND 0 MISSING_NATIVE 2 ORPHAN_NATIVE 1"));
        List<String> rows = Files.readAllLines(out.resolve(JniLinker.FILE), StandardCharsets.UTF_8);
        check("header", rows.get(0).equals("java_path\tjava_symbol\tshort_name\tlong_name\tstatus\tnative_atoms\tos_variants"
                + "\tjava_os"));
        check("rows: header, 18 methods, 1 orphan", rows.size() == 20);
        linked(rows);
        overloads(rows);
        registered(rows);
        others(rows);
        stale(dir, out);
    }

    private static final String LIB_JAVA = "share/classes/p/Lib.java\t";
    private static final String LIB_C = "share/native/libp/Lib.c#";

    private static Path tree(Path root) throws Exception {
        write(root.resolve("share/classes/p/Lib.java"), LIB);
        write(root.resolve("share/classes/p/Peer.java"), PEER);
        write(root.resolve("share/native/libp/Lib.c"), SHARE_C);
        write(root.resolve("windows/native/libp/Lib_md.c"), WINDOWS_C);
        write(root.resolve("unix/native/libp/Lib_md.c"), UNIX_C);
        write(root.resolve("unix/native/libp/Peer.c"), PEER_C);
        write(root.resolve("macosx/native/libp/Lib_md.m"), MACOSX_M);
        write(root.resolve("linux/native/libp/Lib_macro.c"), LINUX_MACRO_C);
        write(root.resolve("share/native/libp/Sort.c"), SORT_C);
        write(root.resolve("share/native/libp/Size.cpp"), SIZE_CPP);
        return root;
    }

    private static void linked(List<String> rows) {
        String lib = LIB_JAVA;
        String c = LIB_C;
        check("short name in four OS variants", has(rows, lib + "Lib.open(String)\tJava_p_Lib_open\t"
                + "Java_p_Lib_open__Ljava_lang_String_2\tLINKED\tmacosx/native/libp/Lib_md.m#Java_p_Lib_open(3)|" + c
                + "Java_p_Lib_open(3)|unix/native/libp/Lib_md.c#Java_p_Lib_open(3)|windows/native/libp/Lib_md.c#"
                + "Java_p_Lib_open(3)\tmacosx,share,unix,windows"));
        check("one OS variant", has(rows, lib + "Lib.write(byte[],int)\tJava_p_Lib_write\tJava_p_Lib_write___3BI\tLINKED\t"
                + "windows/native/libp/Lib_md.c#Java_p_Lib_write(4)\twindows"));
        check("nested class", has(rows, lib + "Lib.Cursor.next()\tJava_p_Lib_00024Cursor_next\t"
                + "Java_p_Lib_00024Cursor_next__\tLINKED\t" + c + "Java_p_Lib_00024Cursor_next(2)\tshare"));
        check("registerNatives linked", has(rows, lib + "Lib.registerNatives()\tJava_p_Lib_registerNatives\t"));
        check("unresolved type, short name", has(rows, lib + "Lib.sync(Missing)\tJava_p_Lib_sync\tUNRESOLVED\tLINKED\t"));
    }

    private static void overloads(List<String> rows) {
        String lib = LIB_JAVA;
        check("overload by long name", has(rows, lib + "Lib.seek(long)\tJava_p_Lib_seek\tJava_p_Lib_seek__J\t"
                + "LINKED_OVERLOAD\t" + LIB_C + "Java_p_Lib_seek__J(3)\tshare")
                && has(rows, lib + "Lib.seek(long,int)\tJava_p_Lib_seek\tJava_p_Lib_seek__JI\tLINKED_OVERLOAD\t"));
        check("unresolved type, overload", has(rows, lib + "Lib.put(Missing)\tJava_p_Lib_put\tUNRESOLVED\tLINKED_OVERLOAD\t"
                + LIB_C + "Java_p_Lib_put__Lq_Missing_2(3)\tshare")
                && has(rows, lib + "Lib.put(int)\tJava_p_Lib_put\tJava_p_Lib_put__I\tLINKED_OVERLOAD\t"));
    }

    private static void registered(List<String> rows) {
        String lib = LIB_JAVA;
        check("registered by a table", has(rows, lib + "Lib.close()\tJava_p_Lib_close\tJava_p_Lib_close__\tREGISTERED\t"
                + LIB_C + "methods[close]|" + LIB_C + "closeImpl(2)\tshare"));
        check("table signature does not fit: missing", has(rows, lib + "Lib.flush(List<String>)\tJava_p_Lib_flush\t"
                + "Java_p_Lib_flush__Ljava_util_List_2\tMISSING_NATIVE\t\t"));
        check("FindClass names the table's class", has(rows, "share/classes/p/Peer.java\tPeer.id()\tJava_p_Peer_id\t"
                + "Java_p_Peer_id__\tREGISTERED\tunix/native/libp/Peer.c#peerMethods[id]|unix/native/libp/Peer.c#peerId(2)\tunix")
                && has(rows, lib + "Lib.id()\tJava_p_Lib_id\tJava_p_Lib_id__\tMISSING_NATIVE\t\t"));
    }

    private static void others(List<String> rows) {
        check("orphan native", has(rows, "\t\tJava_p_Gone_vanished\t\tORPHAN_NATIVE\t" + LIB_C + "Java_p_Gone_vanished(2)\tshare"));
        check("name built by a token-pasting macro, declaration not counted", has(rows, LIB_JAVA + "Lib.pid()\tJava_p_Lib_pid\t"
                + "Java_p_Lib_pid__\tLINKED\tlinux/native/libp/Lib_macro.c#LIB_NAME(pid)\tlinux"));
        check("functions defined by a macro instantiation", has(rows, LIB_JAVA + "Lib.sortInt0(int[])\tJava_p_Lib_sortInt0\t"
                + "Java_p_Lib_sortInt0___3I\tLINKED\tshare/native/libp/Sort.c#SORT(Int,jintArray)\tshare")
                && has(rows, LIB_JAVA + "Lib.sortLong0(long[])\tJava_p_Lib_sortLong0\tJava_p_Lib_sortLong0___3J\tLINKED\t"
                + "share/native/libp/Sort.c#SORT(Long,jlongArray)\tshare"));
        check("C function after macro instances without semicolons", has(rows, LIB_JAVA + "Lib.flushAll()\t"
                + "Java_p_Lib_flushAll\tJava_p_Lib_flushAll__\tLINKED\tshare/native/libp/Sort.c#Java_p_Lib_flushAll(2)\tshare"));
        check("C++ function with noexcept(true)", has(rows, LIB_JAVA + "Lib.size()\tJava_p_Lib_size\tJava_p_Lib_size__\t"
                + "LINKED\tshare/native/libp/Size.cpp#Java_p_Lib_size(2)\tshare"));
    }

    /** JNI.tsv is removed when the same output directory gets sources without JNI code. */
    private static void stale(Path dir, Path out) throws Exception {
        Path plain = dir.resolve("plain");
        write(plain.resolve("p/Plain.java"), "package p;\n\nclass Plain {\n    int one() { return 1; }\n}\n");
        String line = CodeClustering.cluster(out, List.of(plain), 12, 3);
        check("no JNI part and no JNI.tsv without JNI code: " + line, !line.contains("JNI")
                && !Files.exists(out.resolve(JniLinker.FILE)));
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
