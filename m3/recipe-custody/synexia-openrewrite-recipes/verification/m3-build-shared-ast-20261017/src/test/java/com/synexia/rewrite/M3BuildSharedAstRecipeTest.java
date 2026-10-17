// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.text.PlainText;

/** Finite application fixture around the canonical SDK recipes, not a transformation engine. */
final class M3BuildSharedAstRecipeTest {
    private record Entry(String path, String before, String after, String template) {}

    @Test
    void qualifiedSdkApplicationFixedPointAndRefusal() throws Exception {
        assertEquals(21, Runtime.version().feature(), "Pinned compiler/parser generation required");
        String crate = required("m3.crate");
        String kind = required("m3.kind");
        assertTrue(kind.equals("java") || kind.equals("text"));
        String resources = "/com/synexia/rewrite/hash-pinned-" + kind + "/" + crate + "/";
        Recipe recipe = kind.equals("java")
                ? new M3HashPinnedJavaSnapshotRecipe(crate)
                : new M3HashPinnedTextSnapshotRecipe(crate);
        Path input = Path.of(required("m3.input")).toRealPath();
        Path output = Path.of(required("m3.output")).toAbsolutePath().normalize();
        assertFalse(Files.exists(output, LinkOption.NOFOLLOW_LINKS), "Fresh generated output required");
        Map<String, Entry> entries = new TreeMap<>();
        for (String line : resource(resources + "manifest.tsv").split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            assertEquals(4, fields.length);
            assertEquals(null, entries.put(fields[0], new Entry(fields[0], fields[1], fields[2], fields[3])));
        }
        assertFalse(entries.isEmpty());
        Map<String, String> before = new TreeMap<>();
        Map<String, String> expected = new TreeMap<>();
        for (Entry entry : entries.values()) {
            Path target = confined(input, entry.path());
            String after = resource(resources + entry.template());
            assertEquals(entry.after(), sha256(after));
            expected.put(entry.path(), after);
            if (entry.before().equals("ABSENT")) {
                assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), entry.path());
            } else {
                String source = Files.readString(target, StandardCharsets.UTF_8);
                assertEquals(entry.before(), sha256(source), entry.path());
                before.put(entry.path(), source);
            }
        }
        var context = context();
        var changes = recipe.run(new InMemoryLargeSourceSet(parse(kind, before, context)), context, 1)
                .getChangeset().getAllResults();
        Map<String, String> actual = new TreeMap<>(before);
        List<String> receipt = new ArrayList<>();
        receipt.add("evidenceKind\tSDK_EXECUTION");
        receipt.add("recipe\t" + recipe.getName());
        receipt.add("crate\t" + crate);
        for (var change : changes) {
            SourceFile after = change.getAfter();
            assertNotNull(after, "Deletion is not admitted");
            if (kind.equals("java")) assertTrue(after instanceof J.CompilationUnit);
            String path = after.getSourcePath().toString().replace('\\', '/');
            assertTrue(entries.containsKey(path), "Unexpected target " + path);
            actual.put(path, after.printAll());
            receipt.add("result\t" + path + "\t" + entries.get(path).before() + "\t" + sha256(after.printAll()));
        }
        assertEquals(expected, actual, "Only SDK outputs establish the postimage");
        var replayContext = context();
        assertTrue(recipe.run(new InMemoryLargeSourceSet(parse(kind, actual, replayContext)), replayContext, 1)
                .getChangeset().getAllResults().isEmpty(), "Whole delivered stage must be unchanged");

        String first = entries.keySet().iterator().next();
        Map<String, String> drift = new TreeMap<>(actual);
        drift.put(first, actual.get(first) + "\n// M3 frozen source-drift counterexample\n");
        var driftContext = context();
        assertRefusal("source drift", () -> recipe.run(
                new InMemoryLargeSourceSet(parse(kind, drift, driftContext)), driftContext, 1));
        var duplicateContext = context();
        List<SourceFile> duplicate = new ArrayList<>(parse(kind, actual, duplicateContext));
        duplicate.add(duplicate.getFirst());
        assertRefusal("duplicate target", () -> recipe.run(
                new InMemoryLargeSourceSet(duplicate), duplicateContext, 1));
        if (!before.isEmpty()) {
            Map<String, String> missing = new TreeMap<>(before);
            missing.remove(missing.keySet().iterator().next());
            var missingContext = context();
            assertRefusal("required source missing", () -> recipe.run(
                    new InMemoryLargeSourceSet(parse(kind, missing, missingContext)), missingContext, 1));
        }
        for (var entry : before.entrySet()) {
            assertEquals(entry.getValue(), Files.readString(confined(input, entry.getKey()), StandardCharsets.UTF_8),
                    "The SDK must not mutate its exact input tree");
        }
        // Validate the complete batch and its confinement before writing any target.
        for (String path : actual.keySet()) confined(output, path);
        Files.createDirectories(output);
        for (var entry : actual.entrySet()) {
            Path target = confined(output, entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        receipt.add("fixedPoint\tPASSED");
        receipt.add("driftRefusal\tPASSED");
        receipt.add("duplicateRefusal\tPASSED");
        receipt.add("missingRefusal\t" + (before.isEmpty() ? "NOT_APPLICABLE_ALL_TARGETS_ABSENT" : "PASSED"));
        receipt.add("scope\tsealed-recipe-SDK-only; runtime-contracts-and-source-mirror-gates-separate");
        Files.write(output.resolve("application.tsv"), receipt, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("M3_SDK_APPLICATION_PASS crate=" + crate + " changes=" + changes.size()
                + " fixedPoint=true output=" + output);
    }

    private static List<SourceFile> parse(String kind, Map<String, String> sources, InMemoryExecutionContext context) {
        if (kind.equals("text")) return sources.entrySet().stream()
                .map(entry -> (SourceFile) PlainText.builder().sourcePath(Path.of(entry.getKey()))
                        .text(entry.getValue()).build()).toList();
        List<Parser.Input> inputs = sources.entrySet().stream()
                .map(entry -> Parser.Input.fromString(Path.of(entry.getKey()), entry.getValue())).toList();
        return JavaParser.fromJavaVersion().build().parseInputs(inputs, null, context).toList();
    }

    private static InMemoryExecutionContext context() {
        return new InMemoryExecutionContext(error -> { throw new AssertionError("SDK error", error); });
    }

    private static void assertRefusal(String expected, Executable operation) {
        Throwable failure = assertThrows(Throwable.class, operation);
        StringBuilder diagnostic = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            diagnostic.append(cause.getClass().getName()).append(':').append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) break;
        }
        assertTrue(diagnostic.toString().contains(expected), diagnostic::toString);
    }

    private static Path confined(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Target escapes image: " + relative);
        for (Path path = target; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("Symlink in candidate path: " + path);
        }
        return target;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("${")) throw new IllegalArgumentException(name);
        return value;
    }

    private static String resource(String path) throws IOException {
        try (var stream = M3BuildSharedAstRecipeTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
