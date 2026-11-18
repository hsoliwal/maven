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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Objective-C in the lexical front end: instance and class methods named by their full selectors, with a
 * {@code #pragma mark - ...} line before them; {@code @implementation ... @end} and a category as TYPE atoms; C
 * functions before and after them; a brace inside {@code @"..."}; {@code @synchronized} and {@code @interface} are
 * not atoms; a renamed argument keeps the LOGIC plane; a C++ class next to an Objective-C class in {@code .mm}.
 */
final class ObjectiveCTest {
    private static final String VIEW = String.join("\n",
            "#import <Cocoa/Cocoa.h>",
            "#pragma mark - Helpers",
            "static int clamp(int v, int lo, int hi) {",
            "    return v < lo ? lo : (v > hi ? hi : v);",
            "}",
            "",
            "@interface View ()",
            "@property (nonatomic) int count;",
            "@end",
            "",
            "@implementation View",
            "- (void)drawRect:(NSRect)rect {",
            "    NSString *s = @\"{ not a block\";",
            "    [super drawRect:rect];",
            "}",
            "- (id)initWithFrame:(NSRect)frame style:(int)style {",
            "    self = [super initWithFrame:frame];",
            "    @synchronized (self) {",
            "        _count = style;",
            "    }",
            "    return self;",
            "}",
            "+ (instancetype)sharedView {",
            "    static View *shared = nil;",
            "    return shared;",
            "}",
            "#pragma mark - Teardown",
            "- (void)dealloc {",
            "    [super dealloc];",
            "}",
            "@end",
            "",
            "int helper(int x) {",
            "    return clamp(x, 0, 2);",
            "}",
            "");
    private static final String TWICE = String.join("\n",
            "@implementation A",
            "- (int)twice:(int)x {",
            "    return x * 2;",
            "}",
            "@end",
            "@implementation B (Math)",
            "- (int)twice:(int)y {",
            "    return y * 2;",
            "}",
            "@end",
            "");
    private static final String BRIDGE = String.join("\n",
            "class Box {",
            "    int size() { return 1; }",
            "};",
            "@implementation Bridge",
            "- (int)size { return 2; }",
            "@end",
            "");
    private static int failures;

    private ObjectiveCTest() { }

    static int failures() {
        return failures;
    }

    static void run(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("View.m"), VIEW, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Twice.m"), TWICE, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("Bridge.mm"), BRIDGE, StandardCharsets.UTF_8);
        Map<String, Atom> atoms = AtomPlanes.scan(List.of(dir)).stream()
                .collect(Collectors.toMap(a -> a.path() + "#" + a.kind() + "#" + a.symbol(), Function.identity()));
        symbols(atoms);
        lines(atoms);
        Atom a = atoms.get("Twice.m#METHOD#A.twice:");
        Atom b = atoms.get("Twice.m#METHOD#B+Math.twice:");
        check("objc logic: renamed argument", a.logic().equals(b.logic()) && !a.exact().equals(b.exact()));
    }

    /** Symbols: selectors without a parameter count, Class+Category, C and C++ functions with one. */
    private static void symbols(Map<String, Atom> atoms) {
        List.of("View.m#METHOD#clamp(3)", "View.m#TYPE#View", "View.m#METHOD#View.drawRect:",
                "View.m#METHOD#View.initWithFrame:style:", "View.m#METHOD#View.sharedView", "View.m#METHOD#View.dealloc",
                "View.m#METHOD#helper(1)", "Twice.m#TYPE#A", "Twice.m#METHOD#A.twice:", "Twice.m#TYPE#B+Math",
                "Twice.m#METHOD#B+Math.twice:", "Bridge.mm#TYPE#Box", "Bridge.mm#METHOD#Box.size(0)",
                "Bridge.mm#TYPE#Bridge", "Bridge.mm#METHOD#Bridge.size").forEach(k -> check("atom " + k, atoms.containsKey(k)));
        check("15 atoms, none for @synchronized or @interface: " + atoms.keySet(), atoms.size() == 15);
    }

    /** Lines: the class from @implementation to @end, methods from their sign to their brace, past a #pragma line. */
    private static void lines(Map<String, Atom> atoms) {
        check("objc lines: View 11-31", lines(atoms.get("View.m#TYPE#View"), 11, 31));
        check("objc lines: drawRect: 12-15 (brace in @\"...\")", lines(atoms.get("View.m#METHOD#View.drawRect:"), 12, 15));
        check("objc lines: initWithFrame:style: 16-22", lines(atoms.get("View.m#METHOD#View.initWithFrame:style:"), 16, 22));
        check("objc lines: class method sharedView 23-26", lines(atoms.get("View.m#METHOD#View.sharedView"), 23, 26));
        check("objc lines: dealloc 28-30 after #pragma mark", lines(atoms.get("View.m#METHOD#View.dealloc"), 28, 30));
        check("objc lines: C function after @end 33-35", lines(atoms.get("View.m#METHOD#helper(1)"), 33, 35));
        check("lines: C function after #import and #pragma lines 3-5", lines(atoms.get("View.m#METHOD#clamp(3)"), 3, 5));
    }

    private static boolean lines(Atom atom, int line, int endLine) {
        return atom != null && atom.line() == line && atom.endLine() == endLine;
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
