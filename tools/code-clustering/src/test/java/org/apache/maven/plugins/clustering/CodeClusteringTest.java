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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * Checks each plane on a fixture: an identical method shares the exact hash; a method with renamed locals and
 * parameters shares the logic hash but not the exact one; a method that differs only in a literal shares the structure
 * hash but not the logic one; a method with one extra statement is nearer on SimHash than an unrelated method. It
 * checks the atoms' source lines (also with CRLF line ends), then the cluster and join tables, the command line, the
 * atom table's line columns and reading a table written without them. Runs as a main class (argument: a scratch
 * directory).
 */
public final class CodeClusteringTest {
    private static final String SUM = "    int sum(int[] values) {\n        int total = 0;\n"
            + "        for (int v : values) {\n            total += v;\n        }\n        return total;\n    }\n";
    private static final String A = "package p;\n\nclass A {\n" + SUM
            + "    int add(int[] xs) {\n        int acc = 0;\n        for (int x : xs) {\n            acc += x;\n"
            + "        }\n        return acc;\n    }\n"
            + "    int start(int[] values) {\n        int total = 1;\n        for (int v : values) {\n"
            + "            total += v;\n        }\n        return total;\n    }\n"
            + "    int sumLogged(int[] values) {\n        int total = 0;\n        for (int v : values) {\n"
            + "            total += v;\n        }\n        System.err.println(total);\n        return total;\n    }\n"
            + "    String name(Map<String, String> m) {\n        return m.getOrDefault(\"name\", \"none\").trim();\n    }\n}\n";
    private static final String B = "package q;\n\nclass B {\n" + SUM + "}\n";
    private static int failures;

    private CodeClusteringTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/code-clustering-test");
        Path src = sources(dir);
        planes(src);
        clusters(dir, src);
        joinAndCli(dir, src);
        families(dir);
        setters(dir);
        nearJoin(dir);
        suites(dir, src);
        if (failures > 0) throw new AssertionError(failures + " code clustering checks failed");
        System.err.println("code clustering: all checks passed");
    }

    /** Writes A with LF line ends and B with CRLF line ends under {@code dir/src}. */
    private static Path sources(Path dir) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src.resolve("p"));
        Files.createDirectories(src.resolve("q"));
        Files.writeString(src.resolve("p/A.java"), A, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("q/B.java"), B.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        return src;
    }

    private static void planes(Path src) throws Exception {
        Map<String, Atom> atoms = AtomPlanes.scan(List.of(src)).stream()
                .collect(Collectors.toMap(a -> a.path() + "#" + a.symbol(), Function.identity()));
        hashes(atoms);
        lines(atoms);
    }

    private static void hashes(Map<String, Atom> atoms) {
        Atom sum = atoms.get("p/A.java#A.sum(int[])");
        Atom copy = atoms.get("q/B.java#B.sum(int[])");
        Atom add = atoms.get("p/A.java#A.add(int[])");
        Atom start = atoms.get("p/A.java#A.start(int[])");
        Atom logged = atoms.get("p/A.java#A.sumLogged(int[])");
        Atom name = atoms.get("p/A.java#A.name(Map<String, String>)");
        check("eight atoms (2 types, 6 methods)", atoms.size() == 8);
        check("exact: copy with CRLF line ends", sum.exact().equals(copy.exact()));
        check("logic: renamed locals", sum.logic().equals(add.logic()) && !sum.exact().equals(add.exact()));
        check("structure: literal changed", sum.structure().equals(start.structure()) && !sum.logic().equals(start.logic()));
        check("simhash: extra statement nearer than unrelated code",
                PlaneHash.hamming(sum.simhash(), logged.simhash()) < PlaneHash.hamming(sum.simhash(), name.simhash()));
    }

    private static void lines(Map<String, Atom> atoms) {
        Atom sum = atoms.get("p/A.java#A.sum(int[])");
        Atom copy = atoms.get("q/B.java#B.sum(int[])");
        Atom type = atoms.get("p/A.java#A");
        check("javac lines: method 4-10, CRLF copy 4-10, type 3-" + A.lines().count(), sum.line() == 4
                && sum.endLine() == 10 && copy.line() == 4 && copy.endLine() == 10 && type.line() == 3
                && type.endLine() == A.lines().count());
    }

    private static void clusters(Path dir, Path src) throws Exception {
        Path out = dir.resolve("out");
        String line = CodeClustering.cluster(out, List.of(src), 12, 3);
        String clusters = Files.readString(out.resolve("CLUSTERS.tsv"));
        check("cluster line " + line, line.startsWith("atoms 8"));
        check("EXACT cluster", has(clusters, "EXACT", "p/A.java#A.sum(int[])|q/B.java#B.sum(int[])"));
        check("LOGIC cluster", has(clusters, "LOGIC", "p/A.java#A.add(int[])") && has(clusters, "LOGIC", "q/B.java#B.sum(int[])"));
        check("STRUCTURE cluster", has(clusters, "STRUCTURE", "p/A.java#A.start(int[])"));
    }

    /** The other test classes, each in its own scratch directory. */
    private static void suites(Path dir, Path src) throws Exception {
        LexicalAtomsTest.run(dir.resolve("lexical"));
        failures += LexicalAtomsTest.failures();
        PlaneCompareTest.run(dir.resolve("compare"), src, B);
        failures += PlaneCompareTest.failures();
        FrontEndTest.run(dir.resolve("front-ends"));
        failures += FrontEndTest.failures();
        ObjectiveCTest.run(dir.resolve("objc"));
        failures += ObjectiveCTest.failures();
        nativeSuites(dir);
    }

    /** The JNI and C-family test classes. */
    private static void nativeSuites(Path dir) throws Exception {
        JniNamesTest.run(dir.resolve("jni-names"));
        failures += JniNamesTest.failures();
        JniLinkerTest.run(dir.resolve("jni"));
        failures += JniLinkerTest.failures();
        CFamilyTest.run(dir.resolve("c-family"));
        failures += CFamilyTest.failures();
        CFamilyBlocksTest.run(dir.resolve("c-family-blocks"));
        failures += CFamilyBlocksTest.failures();
        JniCasesTest.run(dir.resolve("jni-cases"));
        failures += JniCasesTest.failures();
        JniTypesTest.run(dir.resolve("jni-types"));
        failures += JniTypesTest.failures();
    }

    /**
     * Member-select names are kept: setters that write different fields differ on LOGIC (they share STRUCTURE); the
     * same setter with a renamed parameter shares LOGIC.
     */
    private static void setters(Path dir) throws Exception {
        Path src = dir.resolve("setters");
        Files.createDirectories(src.resolve("s"));
        Files.writeString(src.resolve("s/Auth.java"), String.join("\n", "package s;", "class Auth {",
                "    String password, passphrase;",
                "    void setPassword(String password) { this.password = password; }",
                "    void setPassphrase(String passphrase) { this.passphrase = passphrase; }",
                "    void setSecret(String value) { this.password = value; }", "}", ""), StandardCharsets.UTF_8);
        setterPlanes(AtomPlanes.scan(List.of(src)).stream().collect(Collectors.toMap(Atom::symbol, Function.identity())));
    }

    private static void setterPlanes(Map<String, Atom> atoms) {
        Atom password = atoms.get("Auth.setPassword(String)");
        Atom passphrase = atoms.get("Auth.setPassphrase(String)");
        Atom secret = atoms.get("Auth.setSecret(String)");
        check("setters writing different fields differ on LOGIC", !password.logic().equals(passphrase.logic())
                && password.structure().equals(passphrase.structure()));
        check("renamed parameter, same field: same LOGIC", password.logic().equals(secret.logic()));
    }

    /**
     * NEAR through the SimHash band index: distance 1 and distance 3 (differing bits spread over three bands) are found,
     * a distant hash is ABSENT, and a tie goes to the first atom in path order whatever band found it.
     */
    private static void nearJoin(Path dir) throws Exception {
        Atom a1 = synthetic("a/One.java", "00000000000000ff");
        Atom a3 = synthetic("a/Three.java", "0001000100010000");
        Atom far = synthetic("a/Far.java", "ffffffffffff0000");
        Atom b1 = synthetic("b/Z.java", "00000000000000fe");
        Atom b2 = synthetic("b/Y.java", "00000000000000fd");
        Atom b3 = synthetic("b/X.java", "0000000000000000");
        PlaneJoin.join(List.of(a1, a3, far), List.of(b1, b2, b3), dir.resolve("near"), 3);
        String rows = Files.readString(dir.resolve("near/JOIN.tsv"));
        check("near: distance 1, tie to first path", rows.contains("a/One.java\tMETHOD\tm()\tNEAR\tb/X.java")
                || rows.contains("a/One.java\tMETHOD\tm()\tNEAR\tb/Y.java"));
        check("near: distance 3 across three bands", rows.contains("a/Three.java\tMETHOD\tm()\tNEAR\tb/X.java"));
        check("near: distant hash absent", rows.contains("a/Far.java\tMETHOD\tm()\tABSENT"));
        nearTie(dir, a1, b1);
    }

    private static void nearTie(Path dir, Atom a1, Atom b1) throws Exception {
        Atom tieLow = synthetic("b/A.java", "00000000000000fe");
        PlaneJoin.join(List.of(a1), List.of(b1, tieLow), dir.resolve("tie"), 3);
        check("near: tie goes to first path", Files.readString(dir.resolve("tie/JOIN.tsv"))
                .contains("a/One.java\tMETHOD\tm()\tNEAR\tb/A.java"));
    }

    private static Atom synthetic(String path, String simhash) {
        return new Atom(path, "METHOD", "m()", 20, "exact:" + path, "logic:" + path, "structure:" + path, simhash, 0, 0);
    }

    /** A file that repeats A under another class name and other local names joins A's file family; B does not. */
    private static void families(Path dir) throws Exception {
        Path src = dir.resolve("families");
        Files.createDirectories(src.resolve("p"));
        Files.createDirectories(src.resolve("r"));
        Files.createDirectories(src.resolve("q"));
        Files.writeString(src.resolve("p/A.java"), A, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("r/C.java"), A.replace("class A", "class C").replace("total", "t"), StandardCharsets.UTF_8);
        Files.writeString(src.resolve("q/B.java"), B, StandardCharsets.UTF_8);
        String line = CodeClustering.cluster(dir.resolve("families-out"), List.of(src), 12, 3, 0.5);
        String families = Files.readString(dir.resolve("families-out/FILE_CLUSTERS.tsv"));
        String pairs = Files.readString(dir.resolve("families-out/FILE_PAIRS.tsv"));
        check("file family line " + line, line.endsWith("FILES 1/2"));
        check("file family members", families.contains("1\t2\tp/A.java|r/C.java"));
        check("file pair jaccard", pairs.contains("p/A.java\tr/C.java\t4\t4\t4\t1.000"));
    }

    private static void joinAndCli(Path dir, Path src) throws Exception {
        List<Atom> all = AtomPlanes.scan(List.of(src));
        join(dir, all);
        cli(dir, src, all);
        oldTable(dir);
    }

    private static void join(Path dir, List<Atom> all) throws Exception {
        Path b = dir.resolve("b");
        Files.createDirectories(b.resolve("q"));
        Files.writeString(b.resolve("q/B.java"), B, StandardCharsets.UTF_8);
        List<Atom> left = all.stream().filter(a -> a.path().startsWith("p/")).collect(Collectors.toList());
        List<Atom> right = AtomPlanes.scan(List.of(b));
        String joined = PlaneJoin.join(left, right, dir.resolve("join"), 3);
        String join = Files.readString(dir.resolve("join/JOIN.tsv"));
        check("join line " + joined, joined.startsWith("joined 6 atoms: EXACT 1 LOGIC 1 STRUCTURE 1"));
        check("join rows", join.contains("p/A.java\tMETHOD\tA.sum(int[])\tEXACT\tq/B.java\tB.sum(int[])")
                && join.contains("p/A.java\tMETHOD\tA.add(int[])\tLOGIC\tq/B.java\tB.sum(int[])"));
        String coverage = Files.readString(dir.resolve("join/COVERAGE.tsv"));
        check("coverage row", coverage.contains("p/A.java\t6\t1\t1\t1\t") && coverage.contains("OWN_CODE"));
    }

    /** The command line: usage, cluster, a self join, and the atom table it writes. */
    private static void cli(Path dir, Path src, List<Atom> all) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int usage = CodeClustering.execute(new String[] {"nothing"}, out);
        int cluster = CodeClustering.execute(new String[] {"cluster", dir.resolve("cli").toString(), src.toString()}, out);
        Path tsv = dir.resolve("cli/ATOM_HASHES.tsv");
        int join2 = CodeClustering.execute(new String[] {"join", tsv.toString(), tsv.toString(), dir.resolve("self").toString()}, out);
        check("cli exit codes " + usage + cluster + join2, usage == 2 && cluster == 0 && join2 == 0);
        check("self join is all exact", bytes.toString(StandardCharsets.UTF_8).contains("joined 8 atoms: EXACT 8 "));
        check("atom table round trip", PlaneTables.readAtoms(tsv).equals(all));
        check("atom table line columns", Files.readAllLines(tsv).get(0).endsWith("\tsimhash\tline\tend_line")
                && PlaneTables.readAtoms(tsv).stream().anyMatch(a -> a.symbol().equals("A.sum(int[])") && a.line() == 4
                && a.endLine() == 10));
    }

    /** An atom table written before the line columns existed reads with lines 0. */
    private static void oldTable(Path dir) throws Exception {
        Path old = dir.resolve("old/ATOM_HASHES.tsv");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "path\tkind\tsymbol\ttokens\texact\tlogic\tstructure\tsimhash\n"
                + "p/A.java\tMETHOD\tA.sum(int[])\t30\te\tl\ts\t0123456789abcdef\n", StandardCharsets.UTF_8);
        List<Atom> read = PlaneTables.readAtoms(old);
        check("old 8-column atom table reads with lines 0", read.size() == 1 && read.get(0).line() == 0
                && read.get(0).endLine() == 0 && read.get(0).simhash().equals("0123456789abcdef"));
    }

    private static boolean has(String clusters, String plane, String members) {
        return clusters.lines().anyMatch(l -> l.startsWith(plane + "\t") && l.contains(members));
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.err.println((ok ? "PASS " : "FAIL ") + name);
    }
}
