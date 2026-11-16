<!-- SPDX-License-Identifier: Apache-2.0 -->
# Canon gate recipes

The CPU checks a canon gate packet uses, and where each one lives.

| Check | Owner | How a packet uses it |
| --- | --- | --- |
| product stamp in a path, identifier or label | the gate: built-in check `stamp` | `checks<TAB>stamp` |
| new abstract method on an existing interface or abstract class | the gate: built-in check `extension_points` | `checks<TAB>extension_points` |
| a workflow, make file, build descriptor, script or test list that still uses a retired name | the gate: built-in check `text_refs` | `checks<TAB>text_refs` and `checks.renamed<TAB><old-name>,...` |
| the task packet of a leaf, written from the Java files the working tree changes | `scripts/gates/leaf_packet.py` | run before the gate; it lists `stamp,extension_points` in `checks` |

The three checks were Python scripts in `scripts/gates/` until 2026-11-16. They are now Java classes of the gate
(`StampCheck`, `ExtensionPoints`, `TextRefs`, registered in `Checks`), described in
[CANON_GATE.md](CANON_GATE.md#built-in-checks), tested offline by `ChecksTest`, and callable on their own from the
gate's command line. The scripts were removed after both implementations gave identical rows on one change set that
contained every finding kind (3 stamp rows, 4 extension point rows, 1 text reference row).

Two deliberate differences from the scripts: the stamp check counts a name once per added line, so a line that repeats
an existing name twice is carried, not new; and the checks never touch the index or the network (the scripts ran
`git add -A -N`, and the text reference script could fetch blobs of a partial clone).

## The packet writer

`leaf_packet.py` writes a packet whose stages are:

1. `diff`: `git diff --check`.
2. `atom_pattern`: atomize the tree, write `ATOM_PATTERN.tsv` with an admitted pattern per changed file, refuse a
   changed class that is over the method or class limit (or, for existing classes, one that got worse), refuse a
   changed file that has more leetcodify sites than its base version.
3. `lint`: `javac -Xlint:all -Werror -Xdoclint` on the changed sources; the gate then runs the built-in checks.
4. `compile`, `test`: the changed sources and tests; `--existing-test <path>` adds a test the leaf does not change.
5. `runtime`: the proof program, when one is given.

It takes its places from the environment: `LEAF_KIT` (the atomize tools), `LEAF_CANON` (the canon gate classes, the
pattern catalogue and `atoms.py`), `LEAF_JAVA_BIN` (a JDK 21 `bin/` folder), `LEAF_JUNIT` (the JUnit console jar).
The authoritative copy is in com.synexia (`synexia-openrewrite-recipes/recipe-crates/convergence-kit/tools`);
`scripts/gates/GATES.sha256` lists the SHA-256 of the copy here. Update both places together.

## Why these checks

Each one exists because a change went wrong without it on 2026-11-16: a stamped label was carried into a new test; a
rename was merged while two workflows still compiled the old file names; packets were written by hand and differed.
The corrections are recorded in the kit's `RECIPE_MEMORY.tsv`.
