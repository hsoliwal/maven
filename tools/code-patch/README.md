<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
# Apache Maven Code Patch Plugin

Applies unified diffs (`git diff`, `git format-patch`, `diff -u`) to a project in reviewed steps. Nothing changes
on disk until a reviewer approves a plan id, and every apply leaves a receipt that `rollback` can use.

```
plan  ->  preview  ->  verify  ->  apply -Dpatch.approvedPlan=<id>  ->  (rollback -Dpatch.receipt=<id>)
```

## Goals

| Goal | Writes sources? | What it does |
|---|---|---|
| `plan` | no | Reads the patches, resolves every target under the base directory, applies every hunk in memory with exact context, and writes `PLAN.tsv` and `PLAN_ID`. If a hunk does not apply, it fails and names the patch, the file, the hunk, the line, and the expected and found bytes |
| `preview` | no | Plans, then writes the after-images to `stage/<plan-id>/` |
| `verify` | no | Plans, stages the after-images and the current content of the same files, splits both into code atoms with the code clustering plugin and writes `COMPARE.tsv` (`PlaneCompare` states, plus a `NO_ATOMS` row and a warning for every changed file that gives no atoms). `failOn` chooses the states that fail the build |
| `apply` | yes | Needs `-Dpatch.approvedPlan=<plan id>`. Recomputes the plan and refuses if its id is not the approved one. Then writes backups and a receipt and promotes file by file |
| `rollback` | yes | `-Dpatch.receipt=<plan id>`: checks that every file still has the content apply gave it (refuses otherwise, naming the files), then restores the backups. A rollback that stopped halfway is finished by running it again |

## Use

```
mvn org.apache.maven.plugins:maven-code-patch-plugin:0.1.0-SNAPSHOT:plan -Dpatch.patches=fix.patch
mvn ...:preview -Dpatch.patches=fix.patch
mvn ...:verify  -Dpatch.patches=fix.patch -Dpatch.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED
mvn ...:apply   -Dpatch.patches=fix.patch -Dpatch.approvedPlan=<the id plan printed>
mvn ...:rollback -Dpatch.receipt=<the same id>
```

The goals also run in a directory without a `pom.xml`. They are aggregator goals, so a reactor build runs them once,
on the top-level project.

| Parameter | Property | Default | Goals |
|---|---|---|---|
| patches (files, or directories whose `*.patch` and `*.diff` files apply in name order) | `patch.patches` (comma-separated) | none | all but rollback |
| baseDirectory (what patch paths are relative to) | `patch.baseDirectory` | `${project.basedir}` | all |
| outputDirectory | `patch.outputDirectory` | `${project.build.directory}/patch` | all |
| failOn (comma-separated states, `NO_ATOMS` included; empty reports only; an unknown state is refused) | `patch.failOn` | empty | verify |
| approvedPlan (64 hex digits) | `patch.approvedPlan` | none | apply |
| receipt (the plan id of the receipt) | `patch.receipt` | none | rollback |

Several patches apply in the given order, each to the result of the ones before it, so a `git format-patch` series
in one directory works. A plan row describes the net change of each file across the whole series.

For a mechanical refactoring patch, `-Dpatch.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED` requires that every
atom it touches keeps its logic: only UNCHANGED, REFORMATTED (layout, comments, or names the atom declares) and
MOVED (same logic under a new path or symbol) pass. `verify` does not use receipts, so it never reports ACCEPTED.
It atomizes only the files the plan changes, with the languages that the code clustering plugin reads.

A changed file that gives no atoms before and none after (text, XML, properties, a language the code clustering
plugin does not read, or a path it skips such as one with a `build` or `target` directory) cannot be compared. It
gets a `NO_ATOMS` row in `COMPARE.tsv` (kind `FILE`, the new path in `path`, the old path in `baseline_path`) and a
warning naming it. These rows only report unless `failOn` holds `NO_ATOMS`: add it to the gate above
(`-Dpatch.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED,NO_ATOMS`) when every changed file must be compared.

Renaming a member changes more than the member's atom, because an atom's logic plane keeps the names it uses but
does not declare. A method rename makes the method MOVED and its enclosing TYPE atom LITERALS_CHANGED (the type's
text holds the member name). A field rename leaves the TYPE REFORMATTED (it declares the field) and makes every
method that uses the field LITERALS_CHANGED. The refactoring gate above therefore fails a member rename even when
the patch is mechanical; review such a patch by its `COMPARE.tsv` rows, or leave LITERALS_CHANGED out of `failOn`.

## Output

| Path (under `outputDirectory`) | Content |
|---|---|
| `PLAN.tsv`, `PLAN_ID` | the latest plan: `path`, `operation` (ADD, MODIFY, DELETE, RENAME), `hunks`, `before_sha256`, `after_sha256`, `from_path`. `-` means no file (no from path) |
| `plans/<id>/PLAN.tsv` | a copy per plan id, so that a stale approval can name the files that changed |
| `stage/<id>/` | after-images at their relative paths (DELETE rows have none) |
| `verify/<id>/before/`, `verify/<id>/COMPARE.tsv` | current content of the planned files, and the comparison: `state`, `path`, `kind`, `symbol`, `baseline_path`, `baseline_symbol`; the atom rows, then one `NO_ATOMS` row per changed file without atoms |
| `receipts/<id>/RECEIPT.tsv`, `STATUS`, `backup/<path>` | the rows apply promoted, the state (PENDING, APPLIED, ROLLING_BACK, ROLLED_BACK) and the content every file had before, copied with its attributes. Applying the same plan again after a rollback replaces the ROLLED_BACK receipt |
| `patch.lock` | the lock file of apply and rollback |

A RENAME row has the new path in `path` and the old one in `from_path`. Its `before_sha256` is the old file's content.

## What a patch may contain

- `git diff` / `git format-patch` sections: `diff --git`, `new file mode`, `deleted file mode`, `rename from`,
  `rename to` (pure renames without hunks included), `---` / `+++`, hunks. Mail headers, commit messages, diffstat,
  the signature, and the `index`, `similarity` and mode lines are ignored. File modes are not applied. A leading
  UTF-8 byte order mark is dropped. A `git format-patch --stdout` series (one file, one mail per commit) is read mail
  by mail: each `From <40 hex digits> Mon Sep 17 00:00:00 2001` line ends the file section before it, so the commit
  message of the next mail is text before a first section again (see the header-line rule below).
- `diff -u` sections. A tab after a path and the timestamp after it are ignored. `/dev/null` marks an added or
  deleted file. If the two paths differ (for example `file.orig` and `file`), the `+++` path is the target.
- Prefixes: in a git section the `diff --git` line decides. `a/P b/P` means prefixed paths, `P P` means unprefixed
  (`git diff --no-prefix`), so a `--no-prefix` file named `b/x` stays `b/x` even with `/dev/null` on the other side.
  The `---`/`+++` paths and the rename lines must then name the same files as the `diff --git` line, or the section
  is refused. Where the `diff --git` line does not tell (a rename, a `diff -u` section), `a/` and `b/` are removed when
  both sides have them.
- `\ No newline at end of file` applies to the line before it, on either side, and must be on the last line of that
  side in its hunk.
- Refused: binary patches, copies (`copy from`/`copy to`), combined diffs (`diff --cc`, `diff --combined`), quoted
  paths (paths that git quotes because of special characters), a hunk that ends early or has more lines than its
  header counts, hunk numbers too large for an int, a hunk line that the patch file ends in the middle of, and a
  `' '`, `'+'`, `'-'` or `'\'` line after a finished hunk body before the next header line (a header whose counts are
  stale). The format-patch signature `-- ` is allowed there only when plain text follows it (git writes
  `-- `, then its version); followed by the end of the patch, a blank line, a `' '`, `'+'`, `'-'`, `'\'` or `'@'`
  line, a `diff` line or the `From <sha>` line of the next mail it is a removed line `- ` of a stale hunk and is
  refused. A `---`/`+++` pair that starts the next file of a `diff -u` patch (an `@@` header follows it) is allowed
  there too; in a `diff --git` section the next file needs its `diff --git` line, so a bare pair there is refused (it
  would be a removed `-- ` line and an added `++ ` line of a stale hunk).
- Refused also: an unreadable hunk header. Every line that starts with `@@` is read as a hunk header (`@@-3 +3 @@`,
  an `@@@` combined hunk without its `diff --cc` line), and so is every line that starts with `@` after a file
  section's `---`/`+++` lines (`@ -3 +3 @`); a header that does not read is refused with its line, also after a good
  hunk of the same file. And a file section with `---`/`+++` lines and no hunk (`file section without hunks`). So no
  hunk content is dropped silently: an unreadable header never ends a section's hunks.
- Git header lines (`new file mode`, `deleted file mode`, `rename from/to`, `copy from/to`, `Binary files`,
  `GIT binary patch`) are refused outside the header of a `diff --git` section: after a section's `---`/`+++` lines
  or hunks (unless a `From <sha>` mail line came between), and in a `diff -u` section. Before the first section (of
  the patch, or of a mail of a `--stdout` series) only their exact git syntax is refused
  (`Binary files <a> and <b> differ`, `GIT binary patch`, `new file mode`/`deleted file mode` with six octal digits);
  rename and copy lines are git lines only after a `diff --git` line. So a commit message may use these words.

## Safety model

- **Byte-exact, no fuzz.** Every hunk applies at the line its header names. Every context and removed line must be
  equal byte for byte, line terminator included. There is no offset search, no fuzz factor, and no whitespace or
  line-end tolerance: a patch with LF lines does not apply to a CRLF file. Unchanged lines keep their bytes, added
  lines get the patch's bytes, and a missing final newline stays missing unless the patch adds one. A line is never
  written after a line without a terminator (for example an insertion after an unterminated last line), so two lines
  are never joined. The patch is read as bytes, so the platform charset never matters.
- **Contained paths.** A target path must be relative and plain. Refused everywhere: absolute paths, drive letters,
  `..`, `.`, empty segments, backslashes, colons, control characters (below 0x20 and 0x7f, which would break the
  tab-separated `PLAN.tsv` and `RECEIPT.tsv`), a `.git` segment in any case, names that end with a dot or a space
  (Windows drops those, so `.git.` would be `.git`), and symbolic links. Refused on Windows also: `<>"|?*` and the
  device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`, `CONIN$`, `CONOUT$`, `CLOCK$`, also with an
  extension such as `aux.c`). Then the resolved location decides: the real path of the nearest existing ancestor
  (links and junctions resolved; a Windows JVM also resolves 8.3 short names such as `GIT~1` and letter case there)
  followed by the remaining names must be inside the real base directory and outside every `.git` directory
  (compared without case). The existing part of the path must also be spelled as on disk, compared with case, on
  every JVM: from the base directory down, each existing name must be an entry of its directory's listing with exactly
  that name. A Linux JVM on an NTFS volume (WSL `/mnt/c`) finds files by another letter case and by 8.3 short names
  but keeps that spelling in its real path, so the listing decides there. Another letter case, an 8.3 short name or a
  directory link inside the tree names the same file under a second key, and apply plus rollback would leave it
  renamed to the patch's spelling, so it is refused with the real spelling (the entry equal ignoring case, else the
  entry that is the same file); a name whose entry on disk is `.git` (`GIT~1`, `git~1`) is refused as `.git`
  metadata. Each refusal names the path.
- **Case collisions.** When the base directory is on a case-insensitive volume (probed with the other letter case of
  a name in the base directory, else of the base directory or an ancestor; without such a name the volume counts as
  case-insensitive), `plan` refuses two touched paths that differ only in letter case, or whose directories do
  (`d/Foo.txt` and `d/foo.txt`, `NewDir/a.txt` and `newdir/b.txt`): they are one file or one directory there, and
  apply would stop halfway. On a case-sensitive volume they are different files.
- **Writable targets.** `plan` refuses what `apply` could not write: a target inside the output directory, a target
  under an existing ancestor that is not a directory (a regular file, or a dangling link), and on Windows an existing
  target with the read-only attribute (Windows can neither replace nor delete it; clear the attribute and plan again).
- **Plan id.** SHA-256 over each patch file's length and bytes, in order, followed by the sorted plan rows. The rows
  hold the SHA-256 of every target's current content, so the id changes when a patch changes and when a target file
  changes. Patch file names are not part of the id.
- **Approval.** `apply` takes the lock, recomputes the plan from the patches and the files on disk (never from
  PLAN.tsv, which may be stale or edited), and refuses unless the recomputed id is the approved one. The refusal
  names the files whose rows differ from `plans/<approved id>/PLAN.tsv` when that file exists. Before each file is
  promoted, its content is checked against the plan again.
- **Lock.** `apply` and `rollback` hold an operating-system lock on `patch.lock`. A second apply or rollback, in the
  same or another process, is refused instead of waiting.
- **Journal.** `apply` writes all backups (each forced to disk and checked against its plan hash), then `RECEIPT.tsv`
  and `STATUS` PENDING, and only then touches a source. Each file is written to a temporary file in the same
  directory, forced to disk, and moved over the target with an atomic move (a plain replacing move where the file
  system has none), so a file has either its old or its new content, never part of one. After every move or delete,
  and for every directory created on the way, the directory is synced where the platform allows it (POSIX; not on
  Windows, where Java cannot open a directory and NTFS journals its metadata). Each force is a FINE record of the
  `org.apache.maven.plugins.codepatch.FileWrites` logger. POSIX permissions of a rewritten file are kept. A RENAME
  writes the new path before it deletes the old one. After the last file, `STATUS` becomes APPLIED.
- **Failure during apply.** The files promoted so far are restored from the backups right away and `STATUS` becomes
  ROLLED_BACK. If that also fails, the receipt stays PENDING and `rollback` finishes the job: for a PENDING receipt it
  accepts a file that still has its before content (never promoted) as well as one with its after content. Failure
  messages name the exception type (`java.nio.file.AccessDeniedException: <path>`), because a file system
  exception's message is often only a path.
- **Rollback.** Checks every file first: after a complete apply, each must still have its after content, and a
  deleted file must still be absent. Any other content refuses the whole rollback and names the files. Backups are
  checked against their hashes. Then `STATUS` becomes ROLLING_BACK, files are restored in reverse order, with their
  saved attributes (modification time included), added files are removed, and `STATUS` becomes ROLLED_BACK.
  Directories that apply created stay.
- **Interrupted rollback.** If a rollback stops halfway (a file that cannot be replaced, a killed process), `STATUS`
  stays ROLLING_BACK. Run `rollback` again: for a ROLLING_BACK receipt, as for PENDING, a file may already have its
  before content (its backup is still checked against its hash), and the remaining files are restored. A
  ROLLING_BACK receipt blocks a new apply of the same plan until the rollback is finished.
- **Limits.** A multi-file promotion is journaled and serialized, not atomic as a whole: other processes can see the
  tree halfway. Stop editors and builds of the tree while you apply. The lock only excludes other apply and rollback
  runs on the same output directory.

## Donor contract

This plugin replaces a private family of patch goals (plan, stage, verify, apply, publish and their patch, merge,
dry-run and rollback helpers). No code was copied from it.

Kept:

- the order plan -> stage (here `preview`) -> verify -> apply, with no source change before apply;
- apply needs a reviewed 64-hex-digit plan id. It recomputes the plan and refuses a stale approval, and it never
  trusts a report file;
- backups and a transaction receipt per plan id, a journaled and serialized promotion that is not atomic as a whole
  (and says so), and a rollback that restores only from untouched after-images;
- refusals that name what is wrong instead of hiding it behind a flag.

Dropped:

- git and GitHub operations (merge candidates, review branches, draft pull requests, merge-tree previews,
  mergeability checks): this plugin applies patch files and leaves version control to the user;
- the built-in refactoring recipe and the bytecode-identity verification. `verify` here compares code atoms through
  the code clustering plugin instead;
- workspace locality classifiers, editor syntax witnesses, feature-gate command lists and project-specific hashing
  schemas and receipt formats;
- worker, size and pass budgets that only made sense for those engines.

## FOSS decision

See [FOSS_REUSE_DECISION.tsv](FOSS_REUSE_DECISION.tsv). In short:

- `java-diff-utils` 4.18 (Apache-2.0) reads git headers and renames correctly. But it drops the CR of CRLF lines,
  keeps one file-level no-newline flag, and decodes `InputStream` patches with the platform charset. Its
  `DiffUtils.patch` works on lines without terminators. So it cannot keep line endings or a missing final newline
  byte for byte. The plugin has its own byte-level reader (`PatchParser`) and applier (`HunkApplier`), and the tests
  use java-diff-utils as an independent oracle for the header reading (test scope only).
- JGit's `PatchApplier` needs a git repository, applied a hunk at a shifted position in a probe, and refused a CRLF
  hunk without git configuration. Not used.
- `git apply` (2.53) as a command applied a middle hunk at a shifted position with exit 0 and no message. GNU patch
  (2.7.6) applied shifted hunks, applied changed context with fuzz, and rewrote a CRLF file with LF line ends for an
  LF hunk. Neither has a plan id, approval, receipt or rollback. Not used.
- The OpenClaw organization (97 repositories, searched 2026-11-17) has no unified-diff applier: its agent
  `apply_patch` tool reads a different patch format and matches lines after trimming, `clawpatch` fixes in a git
  worktree, `crabbox` calls `git apply`, and `fs-safe` (the same path containment contract) is a Node.js library.
- The name `maven-patch-plugin` belongs to the retired Apache Maven Patch Plugin (1.0 to 1.3 on Maven Central, goal
  prefix `patch`; it ran GNU patch). This plugin is `maven-code-patch-plugin` with goal prefix `code-patch`, like its
  sibling `maven-code-clustering-plugin`. It keeps the `patch.*` user properties.

## Build and test

`mvn verify` compiles with `-Xlint:all -Werror` (release 17; it runs on JDK 17 and 21) and runs `CodePatchTest` as a
main class:

- `PatchParserTest`: the operations and paths of a git patch with a CRLF file, a file without a final newline, an
  added file without a final newline, a deleted file, a pure rename and a rename with a change. It checks that
  `\r\n` is kept, that the marker removes a terminator, that a format-patch mail reads the same, that a `diff -u`
  hunk adds a final newline, the java-diff-utils oracle, and the refusals (binary, copy, quoted path, truncated or
  overlong hunk).
- `PlanApplyTest`: the plan rows, a plan id that is stable and changes with the patch, plan and preview that change
  no file, refused approvals (missing, malformed, stale with the changed file named), a held lock, apply results
  byte for byte (CRLF, no final newline, UTF-8), a receipt with backups, rollback to byte-identical files, POSIX
  permissions kept by apply and rollback (checked where the file system has them), the modification time restored by
  rollback, a refused second rollback, re-apply, a rollback refused for a file edited after apply, and rollback of an
  interrupted (PENDING, half-promoted) apply.
- `SeriesTest`: two patches in one directory applied in name order (a non-patch file ignored). The second renames
  the file the first changed, which gives one RENAME row with both hunks and the net content.
- `RefusalTest`: a second hunk whose context changed (file, hunk, line and bytes named), LF context against a CRLF
  file, a shifted hunk, `..` and absolute paths, a missing file, and an added file that already exists.
- `VerifyTest`: a local-rename patch is REFORMATTED and passes the refactoring gate, a patch that adds a statement is
  CHANGED and fails `failOn=CHANGED`, an empty `failOn` only reports, and a method rename gives a MOVED method and a
  LITERALS_CHANGED TYPE atom.
- `ParserEdgeTest`: hunk lines after a finished hunk body (stale count), with the `---`/`+++` pair and the signature
  allowed; `-- ` refused as a removed line at the end of the patch and before a `diff --git` line, a `---`/`+++` pair,
  an `@@` header or a blank line; hunk numbers beyond int; `--no-prefix` sections with `/dev/null`; `diff --git` paths
  that disagree with `---`/`+++`; a leading byte order mark; git header lines outside a section header (before the
  first section only their exact syntax; rename, copy, binary and mode words in prose are ignored); a no-newline
  marker before the end of its side. `DroppedContentTest`: a removed `-- d` and an added `++ e` after a stale hunk
  (plain and git), `diff --cc` and `diff --combined` sections, `@@@` hunks without their `diff --cc` line, malformed
  headers (`@@-1 +1@@`; after a good hunk `@@-3 +3 @@`, `@@@ ... @@@` and `@ -3 +3 @`) and a git section with
  `---`/`+++` but no hunk are refused, each naming its line; a line starting with `@` before any section is prose.
  `MailProseTest`: a format-patch mail whose commit message uses those words plans like the same mail without them,
  and so does the second mail of a `--stdout` series; without its `From <sha>` line the second message is refused.
  `HunkApplierTest`: no line is written after an unterminated line.
- `TargetPathsTest`: `.git.`, `.git ` and other names ending with a dot or space, the 8.3 name `GIT~1` (also in lower
  case, by the directory listing alone, and through `plan` with a patch that adds a hook and changes the config), a
  symbolic link and a junction into `.git`, control characters (also through `plan`), and Windows names (refusals
  there, not `InvalidPathException`; ordinary names on POSIX). `SpellingTest`: another letter case of a file or
  directory (also through `plan`), an 8.3 short name of a file (also in lower case) and a directory link inside the
  tree are refused with the real spelling, the case and 8.3 refusals also by the directory listing alone.
  `PlanTargetsTest`: an added file under a regular file or a dangling link, a target in the output directory, Windows
  device names. `CaseCollisionTest`: new files and new directories colliding ignoring case are refused on a
  case-insensitive volume and planned, applied and rolled back on a case-sensitive one. `NtfsScratchTest`: on a Linux
  JVM, `TargetPathsTest`, `SpellingTest` and `CaseCollisionTest` run again in a scratch directory on NTFS
  (`$CODE_PATCH_NTFS`, else `/mnt/c/Windows/Temp` or `/mnt/c/Users/Public` under WSL), removed afterwards; skipped on
  Windows and where no such directory can be created.
- `RollbackResumeTest`: a rollback stopped halfway by a file that cannot be replaced leaves ROLLING_BACK, and a second
  rollback finishes. `ReadOnlyTest` (Windows): a read-only target is refused by `plan`, and a plan applies again over
  a read-only backup of its ROLLED_BACK receipt. `FailureMessageTest`: goal, promotion and undo failures name the
  exception type. `DurabilityTest`: every backup and its directory are forced before STATUS PENDING (logger records).
- `NoAtomsTest`: a properties file changed with a Java file gets a `NO_ATOMS` row and a warning, `failOn=NO_ATOMS`
  fails on it, and an unknown `failOn` state and an unreadable hunk header are told apart.
- `MethodLengthTest`: every method of the module has at most 15 lines. `DecisionTableTest`: every
  `FOSS_REUSE_DECISION.tsv` row has eight columns, with the OpenClaw search and the `git apply` and GNU patch probes.

Checks that need a file system feature print `SKIP` with the reason where it is missing: POSIX permissions, symbolic
links, junctions and NTFS deny entries, 8.3 short names, a case-insensitive volume, a directory without write
permission (not as root), the Windows read-only attribute, a writable NTFS directory for a Linux JVM. Run
`CodePatchTest` with a scratch directory on such a volume to exercise them (the source checks run only for the
module's own `target/code-patch-test`).

The plugin depends on `maven-code-clustering-plugin` 0.1.0-SNAPSHOT. Install `tools/code-clustering` first.
