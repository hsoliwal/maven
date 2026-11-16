// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Offline phases for the built-in checks ({@link Checks}), run by {@link CanonGateTest} on its throw-away repository
 * after its own phases. The stamped example names are assembled from parts so this file introduces none.
 */
final class ChecksTest {
    private static final String LOWER = "m" + "3";
    private static final String UPPER = "M" + "3";
    private static final String PLAIN = "checks/Plain.java";
    private static final String SHAPE = "checks/Shape.java";
    private static final String WORKFLOW = "ci/build.yml";

    private ChecksTest() {
    }

    static void run() throws Exception {
        fixture();
        packetRules();
        stampPaths();
        stampNames();
        stampAtSeal();
        extensionPoints();
        textRefs();
        standAlone();
    }

    private static void fixture() throws Exception {
        CanonGateTest.write(PLAIN, "public final class Plain { }\n");
        CanonGateTest.write("checks/Legacy.java", "final class Legacy { String name = \"" + UPPER + "Legacy\"; }\n");
        CanonGateTest.write(SHAPE, "public interface Shape {\n    int area();\n\n    abstract class Base {\n"
                + "        abstract int a();\n    }\n}\n");
        CanonGateTest.write(WORKFLOW, "run: javac checks/OldThing.java\n");
        CanonGateTest.sh("git add -A && " + CanonGateTest.commit("checks"));
    }

    private static void packetRules() throws Exception {
        CanonGateTest.expect(2, CanonGateTest.check(packet("bogus")), "unknown check id");
        CanonGateTest.expect(2, CanonGateTest.check(packet("stamp,stamp")), "repeated check id");
        CanonGateTest.expect(2, CanonGateTest.check(packet("text_refs")), "text_refs without retired names");
        Map<String, String> p = packet("stamp");
        p.put("checks.renamed", "OldThing");
        CanonGateTest.expect(2, CanonGateTest.check(p), "retired names without text_refs");
        CanonGateTest.expect(0, CanonGateTest.check(packet("stamp,extension_points")), "known check ids");
    }

    private static void stampPaths() throws Exception {
        CanonGateTest.append(PLAIN, "// plain\n");
        CanonGateTest.expect(0, CanonGateTest.run(packet("stamp", PLAIN), "stamp-clean"), "unstamped change passes");
        CanonGateTest.contains(dir("stamp-clean").resolve("VERIFY_CONTRACT.tsv"), "check\tstamp\tno finding\tPASS",
                "check row in the verify contract");
        CanonGateTest.present(dir("stamp-clean").resolve(StampCheck.TABLE), "stamp evidence table");
        CanonGateTest.contains(dir("stamp-clean").resolve("PROVENANCE.tsv"), "checks\tstamp: new paths 0",
                "check summary in the provenance");
        CanonGateTest.restore();
        String path = LOWER + "-tools/x.txt";
        CanonGateTest.write(path, "x\n");
        Map<String, String> p = packet("stamp", path);
        p.put("verify.compile", "touch \"$CANON_RUN_DIR/compiled\"");
        CanonGateTest.expect(1, CanonGateTest.run(p, "stamp-path"), "new stamped folder refused");
        CanonGateTest.contains(dir("stamp-path").resolve("FINAL_REPORT.md"), "STAMP_NEW_PATH " + path + " " + LOWER + "-tools",
                "new stamped path reported");
        CanonGateTest.contains(dir("stamp-path").resolve("STATUS.tsv"), "\tlint\tFAIL\t", "finding fails the lint stage");
        absent(dir("stamp-path").resolve("compiled"), "compile ran after a check finding");
        CanonGateTest.expect(0, CanonGateTest.run(packet("", path), "stamp-off"), "a packet without the key is unchanged");
        lacks(dir("stamp-off").resolve("VERIFY_CONTRACT.tsv"), "check\tstamp", "no check row without the key");
        CanonGateTest.restore();
    }

    private static void stampNames() throws Exception {
        CanonGateTest.append(PLAIN, "// " + UPPER + "Widget\n");
        CanonGateTest.expect(1, CanonGateTest.run(packet("stamp", PLAIN), "stamp-name"), "new stamped name refused");
        CanonGateTest.contains(dir("stamp-name").resolve("FINAL_REPORT.md"), "STAMP_NEW_NAME " + PLAIN + ":2 " + UPPER + "Widget",
                "new stamped name reported with its line");
        CanonGateTest.restore();
        CanonGateTest.append(PLAIN, "// " + UPPER + "Legacy and " + UPPER + "Legacy again\n");
        CanonGateTest.expect(0, CanonGateTest.run(packet("stamp", PLAIN), "stamp-carried"), "a name the tree already has passes");
        CanonGateTest.contains(dir("stamp-carried").resolve(StampCheck.TABLE), "CARRIED\t" + PLAIN + "\t2\t" + UPPER + "Legacy",
                "carried name listed");
        CanonGateTest.restore();
    }

    private static void stampAtSeal() throws Exception {
        Map<String, String> p = packet("stamp");
        p.put("verify.compile", "printf '// %sLate\\n' " + UPPER + " >> " + PLAIN);
        CanonGateTest.expect(1, CanonGateTest.run(p, "stamp-seal"), "stamp introduced after lint refused at the seal");
        CanonGateTest.contains(dir("stamp-seal").resolve("STATUS.tsv"), "\tlint\tPASS\t", "lint had passed");
        CanonGateTest.contains(dir("stamp-seal").resolve("FINAL_REPORT.md"), "STAMP_NEW_NAME " + PLAIN + ":2 " + UPPER + "Late",
                "seal reports the late stamp");
        CanonGateTest.restore();
    }

    private static void extensionPoints() throws Exception {
        String shape = Files.readString(CanonGateTest.repo.resolve(SHAPE));
        edit(shape, "int area();", "int area();\n    int perimeter(int scale);");
        CanonGateTest.expect(1, CanonGateTest.run(packet("extension_points", SHAPE), "ext-abstract"),
                "abstract method on an existing interface refused");
        CanonGateTest.contains(dir("ext-abstract").resolve("FINAL_REPORT.md"),
                "EXTENSION_ABSTRACT_ADDED " + SHAPE + " Shape perimeter(int)", "abstract addition reported");
        edit(shape, "int area();", "int area();\n    default int perimeter(@Deprecated final int scale) { return scale; }\n"
                + "    // int ghost();\n    String TEXT = \"int fake();\";\n    static int helper() { return 0; }");
        CanonGateTest.expect(0, CanonGateTest.run(packet("extension_points", SHAPE), "ext-default"), "default method allowed");
        Path table = dir("ext-default").resolve(ExtensionPoints.TABLE);
        CanonGateTest.contains(table, "interface\tShape\tperimeter(int)\tDEFAULT_ADDED", "default addition listed");
        lacks(table, "ghost", "comment text is not a method");
        lacks(table, "fake", "string text is not a method");
        lacks(table, "helper", "static method is not an extension point");
        edit(shape, "abstract int a();", "abstract int a();\n        abstract int b(java.util.List<String> names, int[] sizes);");
        CanonGateTest.expect(1, CanonGateTest.run(packet("extension_points", SHAPE), "ext-class"),
                "abstract method on an existing abstract class refused");
        CanonGateTest.contains(dir("ext-class").resolve("FINAL_REPORT.md"),
                "EXTENSION_ABSTRACT_ADDED " + SHAPE + " Shape.Base b(java.util.List<String>,int[])", "nested type path and signature");
        edit(shape, "abstract int a();", "int a() { return 0; }\n        int b(int count) { return count; }");
        CanonGateTest.expect(0, CanonGateTest.run(packet("extension_points", SHAPE), "ext-body"), "method with a body allowed");
        CanonGateTest.contains(dir("ext-body").resolve(ExtensionPoints.TABLE), "Shape.Base\tb(int)\tCONCRETE_ADDED", "bodied addition listed");
        CanonGateTest.contains(dir("ext-body").resolve(ExtensionPoints.TABLE), "Shape.Base\ta()\tABSTRACT_REMOVED", "abstract removal listed");
        CanonGateTest.restore();
    }

    private static void textRefs() throws Exception {
        CanonGateTest.append(PLAIN, "// renamed\n");
        Map<String, String> p = packet("text_refs", PLAIN);
        p.put("checks.renamed", "OldThing,Gone");
        CanonGateTest.expect(1, CanonGateTest.run(p, "refs-stale"), "workflow still names the retired class");
        CanonGateTest.contains(dir("refs-stale").resolve("FINAL_REPORT.md"), "TEXT_REF_STALE " + WORKFLOW + ":1 OldThing",
                "stale reference reported with its line");
        CanonGateTest.write(WORKFLOW, "run: javac checks/NewThing.java # OldThingy and anOldThing are other words\n");
        p = packet("text_refs", PLAIN, WORKFLOW);
        p.put("checks.renamed", "OldThing,Gone");
        CanonGateTest.expect(0, CanonGateTest.run(p, "refs-clean"), "no retired name left");
        CanonGateTest.contains(dir("refs-clean").resolve("VERIFY_CONTRACT.tsv"), "check\ttext_refs\tno finding\tPASS",
                "text reference row in the verify contract");
        CanonGateTest.restore();
    }

    private static void standAlone() throws Exception {
        String repo = CanonGateTest.repo.toString();
        String out = CanonGateTest.base.resolve("standalone").toString();
        CanonGateTest.expect(0, alone("stamp", repo, "HEAD", out), "stand-alone stamp check on a clean tree");
        CanonGateTest.expect(0, alone("extension_points", repo, "HEAD", out), "stand-alone extension point check");
        CanonGateTest.expect(1, alone("text_refs", repo, "HEAD", out, "OldThing"), "stand-alone text reference check");
        CanonGateTest.append(PLAIN, "// " + UPPER + "Solo\n");
        CanonGateTest.expect(1, alone("stamp", repo, "HEAD", out), "stand-alone stamp check finds the new name");
        CanonGateTest.contains(Path.of(out, StampCheck.TABLE), "NEW_NAME\t" + PLAIN + "\t2\t" + UPPER + "Solo", "stand-alone table");
        CanonGateTest.restore();
        CanonGateTest.expect(2, alone("stamp", repo, "no-such-ref", out), "unknown base ref");
        CanonGateTest.expect(2, alone("stamp", repo, "HEAD"), "missing output directory argument");
        CanonGateTest.expect(2, alone("text_refs", repo, "HEAD", out), "text_refs without names");
        CanonGateTest.expect(2, alone("stamp", repo, "HEAD", out, "Extra"), "names given to a check that takes none");
    }

    private static int alone(String... args) {
        return CanonGate.execute(args, CanonGateTest.quiet());
    }

    /** An apply-mode packet over the check fixture with atom rows for the changed paths. */
    private static Map<String, String> packet(String checks, String... changed) throws IOException {
        Map<String, String> p = CanonGateTest.apply(CanonGateTest.atoms("VALUE_OBJECT", changed));
        p.put("targets.modules", "checks,ci," + LOWER + "-tools");
        if (!checks.isEmpty()) p.put("checks", checks);
        return p;
    }

    private static void edit(String original, String anchor, String replacement) throws IOException {
        CanonGateTest.write(SHAPE, original.replace(anchor, replacement));
    }

    private static Path dir(String run) {
        return CanonGateTest.base.resolve("runs/" + run);
    }

    private static void absent(Path file, String what) {
        if (Files.exists(file)) throw new AssertionError(what + ": " + file);
        CanonGateTest.checks++;
    }

    private static void lacks(Path file, String text, String what) throws IOException {
        if (Files.readString(file).contains(text)) throw new AssertionError(what + ": " + file + " has [" + text + "]");
        CanonGateTest.checks++;
    }
}
