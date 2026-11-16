# SPDX-License-Identifier: Apache-2.0 OR EPL-2.0
# Copyright (c) 2026 Synexia, Hitesh Soliwal (hsoliwal@gmail.com) and others
"""leaf_packet.py <repo> <base-ref> <out-dir> --invariant <ID> --delta "<text>" --pattern <File.java>=<PATTERN>:<Role>...
                  [--classes <dir-or-jar>]... [--proof <Proof.java> --proof-main <class> [--proof-cp <jar>]...]

LEAF_PACKET v1.2 (2026-11-16; v1.1 runs existing tests of the changed classes with --existing-test; v1.2 takes its
places from the environment). The recipe for "write the gate task packet of a leaf": it derives the canon gate packet
from the Java files a working tree changes against <base-ref>, so no packet is written by hand. CPU only.
  targets     the changed Java files (sources under src/, tests under test/)
  atom_pattern  atomize the tree, write ATOM_PATTERN.tsv with the given pattern per file, refuse a changed class in the
              atomization plan (method or class over the limits), run leetcodify and refuse a site in a changed file
  lint        javac -Xlint:all -Werror -Xdoclint on the changed sources, then the stamp gate
  compile     the changed sources, then the changed tests against them
  test        every changed test class on the JUnit console
  runtime     the proof program, when one is given (compiled against the leaf's classes and --proof-cp)
--classes adds compiled classes the leaf depends on (the rest of its module). --existing-test <path> names a test file
that the leaf does not change but that exercises a changed class: it is compiled and run too (a JUnit class, or a
program with a main method when the file has no @Test). It writes <out-dir>/packet.tsv and
<out-dir>/atom-map.tsv and prints the command that runs the gate. Places are taken from the environment when set:
LEAF_KIT (the kit's tools folder), LEAF_CANON (canon gate classes, pattern catalogue and atoms.py), LEAF_JAVA_BIN
(a JDK 21 bin folder, with the trailing slash), LEAF_JUNIT (the JUnit console jar). Exit status: 0 written, 1 refused, 2 usage."""
import os
import re
import subprocess
import sys

args = sys.argv[1:]


def options(name):
    values = []
    while name in args:
        at = args.index(name)
        values.append(args[at + 1])
        del args[at:at + 2]
    return values


invariant, delta = options("--invariant"), options("--delta")
patterns, classes = options("--pattern"), options("--classes")
proof, proof_main, proof_cp = options("--proof"), options("--proof-main"), options("--proof-cp")
existing = [e.replace(os.sep, "/") for e in options("--existing-test")]
if len(args) != 3 or not invariant or not delta or not patterns:
    print(__doc__)
    sys.exit(2)
repo, base, out = (a.replace(os.sep, "/") for a in args)
NL, TAB = chr(10), chr(9)
K = os.environ.get("LEAF_KIT", "C:/m3-scratch-sup/lanes/atomize")
CANON = os.environ.get("LEAF_CANON", "C:/m3-scratch-sup/burn/canon")
J = '"' + os.environ.get("LEAF_JAVA_BIN", "/c/Program Files/Java/jdk-21/bin/")
M2 = os.path.expanduser("~").replace(os.sep, "/") + "/.m2/repository"
JUNIT = os.environ.get("LEAF_JUNIT", M2 + "/org/junit/platform/junit-platform-console-standalone/1.11.4/"
                       "junit-platform-console-standalone-1.11.4.jar")
RUN = "$CANON_RUN_DIR"
subprocess.run(["git", "-C", repo, "add", "-A", "-N"], check=True)
changed = [l.split(TAB)[-1] for l in subprocess.run(["git", "-C", repo, "diff", "--name-status", "--no-renames", base],
                                                     capture_output=True, text=True, check=True).stdout.splitlines()
           if l.split(TAB)[0] in ("A", "M") and l.endswith(".java")]
sources = [p for p in changed if not p.startswith("test/")]
tests = [p for p in changed if p.startswith("test/")] + existing
if not sources:
    print("refused: no added or modified Java source against " + base)
    sys.exit(1)
named = {p.split("=")[0] for p in patterns}
missing = sorted({os.path.basename(p) for p in changed if not p.startswith("test/") or p in changed} - named)
if missing:
    print("refused: no --pattern for " + ", ".join(missing))
    sys.exit(1)


def test_class(path):
    text = open(os.path.join(repo, path), encoding="utf-8").read()
    package = re.search(r"^package\s+([\w.]+)\s*;", text, re.M)
    return (package.group(1) + "." if package else "") + os.path.basename(path)[:-5]


def is_junit(path):
    return "@Test" in open(os.path.join(repo, path), encoding="utf-8").read()


cp = ";".join(classes)
names = "|".join(os.path.basename(p)[:-5] for p in changed)
paths = "|".join(os.path.basename(p) for p in changed)
src = " ".join(sources)
quoted = lambda parts: '"' + ";".join(p for p in parts if p) + '"'
stages = {
    "diff": "git add -A -N && git diff --check",
    "atom_pattern": " && ".join([
        'bash %s/atomize.sh com.synexia.rewrite.M3AtomizeInventory "$CANON_ROOT" "%s/atomize"' % (K, RUN)
        + ' ; python -I %s/atoms.py "$CANON_ROOT" HEAD "%s/ATOM_PATTERN.tsv" "%s/atomize" %s/atom-map.tsv' % (CANON, RUN, RUN, out),
        'python -I %s/atom_static.py "%s/atomize" "%s/static"' % (K, RUN, RUN),
        '! grep -E "(%s)" "%s/static/ATOMIZE_PLAN.tsv"' % (names, RUN),
        'bash %s/atomize.sh com.synexia.rewrite.mop.leetcodify.M3LeetCodifyRecipe "$CANON_ROOT" "%s/leetcodify"' % (K, RUN),
        '! cat "%s"/leetcodify/*LeetCodifySites.tsv 2>/dev/null | grep -E "(%s)"' % (RUN, paths)]),
    "lint": J + 'javac" -encoding UTF-8 -Xlint:all -Werror -Xdoclint:all,-missing -proc:none -cp ' + quoted([cp])
            + ' -d "%s/lint" %s && python -I %s/stamp_gate.py "$CANON_ROOT" HEAD "%s/STAMP_GATE.tsv"' % (RUN, src, K, RUN),
    "compile": J + 'javac" -encoding UTF-8 -nowarn -proc:none -cp ' + quoted([cp]) + ' -d "%s/classes" %s' % (RUN, src)
               + ("" if not tests else " && " + J + 'javac" -encoding UTF-8 -nowarn -proc:none -cp '
                  + quoted([RUN + "/classes", cp, JUNIT]) + " -sourcepath " + quoted(sorted({os.path.dirname(t) for t in tests}))
                  + ' -d "%s/testclasses" %s' % (RUN, " ".join(tests))),
    "test": " && ".join(
        (["MSYS_NO_PATHCONV=1 " + J + 'java" -jar ' + JUNIT + " execute --class-path "
          + quoted([RUN + "/classes", RUN + "/testclasses", cp]) + " "
          + " ".join("--select-class " + test_class(t) for t in tests if is_junit(t))
          + " --details=summary --disable-banner"] if any(is_junit(t) for t in tests) else [])
        + ["MSYS_NO_PATHCONV=1 " + J + 'java" -cp ' + quoted([RUN + "/classes", RUN + "/testclasses", cp]) + " "
           + test_class(t) for t in tests if not is_junit(t)]) or "true",
    "runtime": "true" if not proof else J + 'javac" -encoding UTF-8 -nowarn -proc:none -cp '
               + quoted([RUN + "/classes", cp] + proof_cp) + ' -d "%s/proof" %s' % (RUN, proof[0])
               + " && MSYS_NO_PATHCONV=1 " + J + 'java" -cp ' + quoted([RUN + "/classes", RUN + "/proof", cp] + proof_cp)
               + " " + proof_main[0],
}
rows = [("task_packet", "active"), ("source_doc", CANON + "/M3Scale-v3.0.txt"), ("invariant_id", invariant[0]),
        ("run.mode", "apply"), ("run.profile", "full"), ("run.workers", "1"), ("run.shard_count", "1"),
        ("run.shard_index", "0"), ("targets.files", ",".join(changed)),
        ("invariant_locks", "PUBLIC_API:LOCKED,EXTERNAL_BEHAVIOR:LOCKED,DISABLED_PATHS:FORBIDDEN,"
                            "EXCLUDED_PATHS:FORBIDDEN,BUILD_SCRIPTS:LOCKED"),
        ("allowed_delta", delta[0]), ("forbidden", "any other file; any product stamp in a name, label or path"),
        ("stop_conditions", "first failing stage"), ("pattern_catalog", CANON + "/canon-patterns.tsv"),
        ("verify_order", "diff,atom_pattern,lint,compile,test,runtime"),
        ("output_contract", "STATUS.tsv,FINAL_REPORT.md,RUN_CONTEXT.tsv,PROVENANCE.tsv,VERIFY_CONTRACT.tsv,"
                            "OUTPUT_CONTRACT.tsv,ATOM_PATTERN.tsv")]
rows += [("verify." + stage, command) for stage, command in stages.items()]
os.makedirs(out, exist_ok=True)
with open(out + "/packet.tsv", "w", encoding="utf-8", newline=NL) as w:
    w.write("".join(key + TAB + value + NL for key, value in rows))
with open(out + "/atom-map.tsv", "w", encoding="utf-8", newline=NL) as w:
    for pattern in patterns:
        name, rest = pattern.split("=")
        w.write(name + TAB + rest.replace(":", TAB) + NL)
print("packet: %d sources, %d tests -> %s/packet.tsv" % (len(sources), len(tests), out))
print('run: MSYS_NO_PATHCONV=1 "/c/Program Files/Java/jdk-21/bin/java" -cp %s/out/c '
      'io.synexia.maven.smart.canon.CanonGate run %s/packet.tsv %s/run %s' % (CANON, out, out, repo))
