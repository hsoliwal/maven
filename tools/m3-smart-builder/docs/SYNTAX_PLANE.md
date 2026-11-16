<!-- SPDX-License-Identifier: Apache-2.0 -->
# Syntax plane

The smart builder carries a copy of the AST module of M3JDK, so build tooling can hold source structure in the same
form the JDK owner uses. The owner is `m3/ast` in hsoliwal/M3jdk21; this copy was taken at commit
`b7539452eb6c8ad45b5801db5617a907e7137119`. Nothing in the builder calls it yet; it is JDK only and has no Maven
dependency.

| Class | What it holds |
| --- | --- |
| `SyntaxTree` | one source file as primitive columns: provider kind and field names, token, trivia, missing and error flags, half-open byte spans, parent and sibling links; the exact source bytes are kept once |
| `SyntaxIndex` | lookups over a tree: nodes by kind (posting lists), facts by key |
| `SyntaxDelta` | the byte-coordinate difference between two versions of a source |
| `SyntaxOrigins` | where generated source came from |
| `SyntaxCoordinates` | byte offsets to and from UTF-16 offsets |
| `ASTHierarchy` | facts summed up level by level: file, package, module, project, repository |
| `internal.NullFlowFacts`, `NullFlowVariableIds`, `NullFlowWorklist` | method-local nullness facts over an existing tree |

A grammar provider (javac, or any other parser) stays the authority for structure; a tree is filled through a
six-method callback and no parser is bundled.

## It is a copy: regenerate, do not edit

The sources under `src/main/java/io/synexia/maven/smart/ast` and the proof programs under `src/test/syntax` are
written by the recipe `receive_syntax_plane.py` (com.synexia, `convergence-kit/tools`). A change is made in the owner
and received again. The recipe applies exactly five changes and refuses the result if any other difference in naming
is left:

1. package and import lines: `jdk.m3.ast` becomes `io.synexia.maven.smart.ast`, `jdk.internal.m3.ast` becomes
   `io.synexia.maven.smart.ast.internal`;
2. two class names inside strings of the test agent;
3. each donor anchor becomes an anchor that names the owner file and commit;
4. nine phrases in comments and one test label that named owner-side types;
5. the product prefix of the labels the proof programs print.

The JUnit wrappers of the owner are not copied (this module has no JUnit); the programs they wrap are.

## Proof

`scripts/verify.sh` compiles the nine sources and twenty proof programs with `-Xlint:all -Werror` and runs eighteen
programs. Seventeen run plainly. `SyntaxTreeAtomicFailureContract` runs with the test agent, which injects a failure
before each of the ten array copies of a grow and checks that nothing is published and a retry succeeds.

The proof programs are in `src/test/syntax`, not `src/test/java`, because the agent reads the JDK's internal
class-file library and the Maven build of this module compiles tests without that export.

## Known limits

- Three of the classes are longer than 300 lines (`ASTHierarchy` 397, `SyntaxIndex` 382, `SyntaxTree` 336). They are
  split in the owner first and then received again.
- `FactsFirstPerformanceProof` prints timings as advisory values; it fails only on its memory and correctness checks.
