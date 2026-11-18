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
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * C-family block boundaries and names beyond {@link CFamilyTest}: an access label after semicolon-less macro calls is
 * not a member-initializer list (the function keeps its own name); a header is not joined across {@code #if} lines
 * inside a function body or an array initializer; a member or base initialized with braces, a lambda or
 * {@code ({150,150})} belongs to the constructor's initializer list; {@code case FOO(1): {} } is not a function; and
 * one-word Objective-C selectors that are words of the shared keyword set ({@code object}, {@code range},
 * {@code type}) are methods.
 */
final class CFamilyBlocksTest {
    private static final Map<String, String> FILES = Map.of(
            "label.cpp", String.join("\n", "class FractalNoiseNode {", "    SG_ATTRIBUTE(Seed, float, fSeed)",
                    "    SG_ATTRIBUTE(Octaves, float, fOctaves)", "private:",
                    "    sk_sp<SkRuntimeEffect> getEffect() const {", "        return fEffect;", "    }", "};",
                    "class K {", "  DECLARE_THING(K)", "public:", "  K(int a) : v(a) { }", "  int v;", "};", ""),
            "join.c", String.join("\n", "void body(int message, int mode) {", "#ifndef W", "   UNUSED(message)",
                    "#endif", "#if defined(R)", "   if (mode != 0)", "#endif", "   {", "      go(message);", "   }", "}",
                    "static FT_Vector vecs[2] =", "{", "#if 1", "    XVEC( 1, 2 ),", "    XVEC( 3, 4 )", "#else",
                    "    { 1, 2 },", "    { 3, 4 }", "#endif", "};", ""),
            "init.cpp", String.join("\n", "TestSVGTypeface::Glyph::Glyph() : fOrigin{0, 0}, fAdvance(0) {}",
                    "Foo::Foo()", "    : fA{1}", "    , fB(2) {", "    go();", "}",
                    "ActiveEdge::ActiveEdge() : fChild{ nullptr, nullptr }, fAbove(nullptr), fRed(false) {}",
                    "Derived::Derived(int a) : Base{a}, fList({150, 150}), fFn([](int x) { return x; }) {",
                    "    use(a);", "}", "ParagraphCache::ParagraphCache()",
                    "    : fChecker([](ParagraphImpl* impl, const char*, bool){ })", "    , fLRUCacheMap(kMaxEntries)",
                    "#ifdef PARAGRAPH_CACHE_STATS", "    , fTotalRequests(0)", "#endif", "{ }", "int after(int x) {",
                    "    return x;", "}", "struct Session {", "  Session (Funcs *f, void *d)",
                    "    : funcs {f}, data {d}, st STATE_DEFAULT", "  {}", "  ~Session () { close (); }", "};", ""),
            "spec.cpp", String.join("\n", "struct Flags {",
                    "  VULKAN_HPP_CONSTEXPR Flags() VULKAN_HPP_NOEXCEPT : m_mask( 0 ) {}",
                    "  ArrayProxy() VULKAN_HPP_NOEXCEPT", "    : m_count( 0 )", "    , m_ptr( nullptr )", "  {}",
                    "  Monitor(SkStrike* strike) SK_ACQUIRE(strike->fStrikeLock)", "          : fStrike{strike} {",
                    "      fStrike->lock();", "  }", "  Context( Context && rhs ) VULKAN_HPP_NOEXCEPT",
                    "#if VULKAN_HPP_ENABLE_DYNAMIC_LOADER_TOOL", "    : m_loader( std::move( rhs.m_loader ) )",
                    "    , m_dispatcher( rhs.m_dispatcher.release() )", "#else",
                    "    : m_dispatcher( rhs.m_dispatcher.release() )", "#endif", "  {", "  }", "};", ""),
            "defaults.cpp", String.join("\n", "struct ShapedRun {", "    ShapedRun(Range r, SkVector advance = {0, 0})",
                    "        : fUtf8Range(r), fAdvance(advance)", "    {}", "};", "int draw(SkPoint tl = { 0.0f, 0.0f }) {",
                    "    return 0;", "}", "static auto reg = Register([](int x) { int y = x; return y; });",
                    "int later(int x) {", "    return x;", "}", ""),
            "stuck.h", String.join("\n", "#define CHECK(x) \\", "  go();          \\", "  /* comment     \\",
                    "   * more */     \\", "  if ((x)        \\", "", "namespace n {", "int kept(int a) {", "  return a;", "}",
                    "}", ""),
            "case.c", String.join("\n", "int pick(int v) {", "    switch (v) {", "    case FOO(1): {", "        return 0;",
                    "    }", "    default:", "        return v;", "    }", "}", ""),
            "Obj.m", String.join("\n", "@implementation Obj", "- (id)object {", "    return nil;", "}",
                    "- (NSRange)range {", "    return r;", "}", "- (NSEventType)type {", "    return t;", "}", "@end", ""));
    private static int failures;

    private CFamilyBlocksTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        for (Map.Entry<String, String> file : FILES.entrySet()) {
            Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
        List<String> atoms = AtomPlanes.scan(List.of(dir)).stream().map(CFamilyBlocksTest::key).collect(Collectors.toList());
        labels(atoms);
        joins(atoms);
        initializers(atoms);
        caseAndSelectors(atoms);
    }

    private static void labels(List<String> atoms) {
        check("access label after macro calls: the function keeps its name",
                has(atoms, "label.cpp METHOD FractalNoiseNode.getEffect(0) 5-7"));
        check("access label after a macro call, then a constructor with an initializer list",
                has(atoms, "label.cpp METHOD K.K(1) 12-12"));
        check("no atom named after a macro call: " + atoms, none(atoms, "SG_ATTRIBUTE", "DECLARE_THING"));
        check("an upper-case specifier macro between the parameters and the initializer list",
                has(atoms, "spec.cpp METHOD Flags.Flags(0) 2-2") && has(atoms, "spec.cpp METHOD Flags.ArrayProxy(0) 3-6")
                && has(atoms, "spec.cpp METHOD Flags.Monitor(1) 7-10") && has(atoms, "spec.cpp METHOD Flags.Context(1) 11-19"));
        check("no atom named after a member or a specifier macro", none(atoms, "m_mask", "m_count", "SK_ACQUIRE",
                "m_loader", "m_dispatcher", "fStrike"));
    }

    private static void joins(List<String> atoms) {
        check("no join across #if inside a function body", has(atoms, "join.c METHOD body(2) 1-11")
                && none(atoms, "UNUSED"));
        check("no join across #if inside an array initializer: " + atoms, none(atoms, "XVEC"));
        check("a parenthesis left open by a macro body continued after a comment does not swallow a namespace",
                has(atoms, "stuck.h METHOD kept(1) 8-10"));
    }

    private static void initializers(List<String> atoms) {
        check("brace member initializer on one line", has(atoms, "init.cpp METHOD Glyph(0) 1-1")
                && has(atoms, "init.cpp METHOD ActiveEdge(0) 7-7"));
        check("brace member initializer on its own line", has(atoms, "init.cpp METHOD Foo(0) 2-6"));
        check("base{a}, ({150, 150}) and a lambda in the initializer list", has(atoms, "init.cpp METHOD Derived(1) 8-10"));
        check("lambda and #ifdef in the initializer list", has(atoms, "init.cpp METHOD ParagraphCache(0) 11-17")
                && has(atoms, "init.cpp METHOD after(1) 18-20"));
        check("a member initialized by a macro (st STATE_DEFAULT): the next brace is the body",
                has(atoms, "init.cpp METHOD Session.Session(2) 22-24") && has(atoms, "init.cpp METHOD Session.~Session(0) 25-25"));
        check("braces in default arguments and a lambda in parentheses outside functions: " + atoms,
                has(atoms, "defaults.cpp METHOD ShapedRun.ShapedRun(2) 2-4") && has(atoms, "defaults.cpp METHOD draw(1) 6-8")
                && has(atoms, "defaults.cpp METHOD later(1) 10-12") && none(atoms, "fUtf8Range"));
        check("no atom named after a member or base: " + atoms, none(atoms, "fAdvance", "fB(", "fAbove", "Base(",
                "fList", "fFn", "fChecker", "fLRUCacheMap", "fTotalRequests", "<lambda>"));
    }

    private static void caseAndSelectors(List<String> atoms) {
        check("case FOO(1): { is not a function", has(atoms, "case.c METHOD pick(1) 1-9") && none(atoms, "FOO"));
        check("one-word selectors object, range, type", has(atoms, "Obj.m METHOD Obj.object 2-4")
                && has(atoms, "Obj.m METHOD Obj.range 5-7") && has(atoms, "Obj.m METHOD Obj.type 8-10"));
        check("keywords that cannot be selectors stay rejected", ObjectiveC.method(Lexer.tokens("- ( ) const", false))
                .isEmpty() && ObjectiveC.method(Lexer.tokens("- ( int ) return", false)).isEmpty());
    }

    private static String key(Atom a) {
        return a.path() + " " + a.kind() + " " + a.symbol() + " " + a.line() + "-" + a.endLine();
    }

    private static boolean none(List<String> atoms, String... names) {
        return atoms.stream().noneMatch(a -> List.of(names).stream().anyMatch(a::contains));
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
