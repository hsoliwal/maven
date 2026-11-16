<!-- SPDX-License-Identifier: Apache-2.0 OR EPL-2.0 -->
# Canon gate recipes

Four CPU checks that a canon gate packet calls from its stages. They live in `scripts/gates/`, need only Python 3 and
`git`, change nothing, and exit non-zero on a finding. The authoritative copies are in com.synexia
(`synexia-openrewrite-recipes/recipe-crates/convergence-kit/tools`); `scripts/gates/GATES.sha256` lists the SHA-256 of
the copies here, taken 2026-11-16. Update both places together.

| Script | Stage | What it refuses | Command |
| --- | --- | --- | --- |
| `stamp_gate.py` | lint | a change that introduces a product stamp in a path, identifier or label | `python -I scripts/gates/stamp_gate.py "$CANON_ROOT" <base-ref> "$CANON_RUN_DIR/STAMP_GATE.tsv"` |
| `interface_additions.py` | lint | a new abstract method on an existing interface or abstract class (new methods need a body) | `python -I scripts/gates/interface_additions.py "$CANON_ROOT" <base-ref>` |
| `text_refs.py` | lint, for a rename | a workflow, make file, build descriptor, script or test list that still uses an old name | `python -I scripts/gates/text_refs.py "$CANON_ROOT" HEAD "$CANON_RUN_DIR/TEXT_REFS.tsv" <old-name>...` |
| `leaf_packet.py` | before the run | nothing: it writes the task packet of a leaf from the Java files the working tree changes | `python -I scripts/gates/leaf_packet.py <repo> <base-ref> <out> --invariant <ID> --delta <text> --pattern <File.java>=<PATTERN>:<Role>...` |

## How a packet uses them

`leaf_packet.py` writes a packet whose stages are:

1. `diff`: `git diff --check`.
2. `atom_pattern`: atomize the tree, write `ATOM_PATTERN.tsv` with an admitted pattern per changed file, refuse a
   changed class that is over the method or class limit, refuse a leetcodify site in a changed file.
3. `lint`: `javac -Xlint:all -Werror -Xdoclint` on the changed sources, then `stamp_gate.py`.
4. `compile`, `test`: the changed sources and tests; `--existing-test <path>` adds a test the leaf does not change.
5. `runtime`: the proof program, when one is given.

It takes its places from the environment: `LEAF_KIT` (the atomize tools), `LEAF_CANON` (the canon gate classes, the
pattern catalogue and `atoms.py`), `LEAF_JAVA_BIN` (a JDK 21 `bin/` folder), `LEAF_JUNIT` (the JUnit console jar).

## Why these four

Each one exists because a change went wrong without it on 2026-11-16: a stamped label was carried into a new test; a
rename was merged while two workflows still compiled the old file names; packets were written by hand and differed.
The corrections are recorded in the kit's `RECIPE_MEMORY.tsv`.
