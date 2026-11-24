<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# M3File → Maven M3DB: authenticated file-generation reuse gate

## Scope and current proof

Maven's module-local success receipts are owned by M3DB at `.m3/module.m3db`.
The installed M3JDK `M3FileDeltaLedger` / `M3FileWatcher` owns exact
source-content observations and the distinct `.m3-watch/state.bin` watch
checkpoint. Do not add a third file-digest ledger or move M3File's authority
into Maven.

The existing module-state admission re-hashes declared input and output
contents across independent Maven processes. A matching receipt authorizes
skipping eligible original goals **only after** those exact contents, plugins,
toolchains, environment, dependency closure and output bytes are validated.
Current watch notifications are not durable, gap-free evidence. This document
defines the **unimplemented** condition for using saved file SHA-256 digests
across Maven process restarts without another full content read.

## Required M3File evidence

The original file owner must provide an immutable, bounded generation claim
over a complete declared path inventory, including:

- canonical real root and underlying volume/provider identity;
- identities of every file, directory, rename, deletion and re-creation,
  including missing-path negative lookups and file-key reuse;
- a durable checkpoint, a monotonic evidence range through the present
  generation and explicit **COMPLETE / GAP / UNSUPPORTED** classification;
- independently qualified OS/provider journal coverage, including recorded
  wrap/overflow, observer restart, mount changes and missed-event intervals;
- accepted SHA-256 facts for unchanged content generations, not timestamps
  or caller-declared equality.

A WatchService notification, file length, mtime, ctime, inode/file-key,
SimHash, structural signature, or an old receipt **alone** never certifies
unchanged bytes. Linux inotify/WatchService is not a durable restart-spanning
journal. Windows directory notifications do not by themselves establish NTFS
USN coverage. Any uncertain or unsupported provider must fall back to a
fresh full-content SHA-256 observation; never silently classify a gap as
unchanged.

## Admission protocol

1. Acquire the original module-state lease. Read the sealed M3DB session
   marker, previously successful receipts and M3File checkpoint.
2. Resolve the current exact Maven input closure: effective project and
   ancestor POMs, settings, profiles, plugins, inherited environment,
   selected JDK/toolchain, ordered dependency classpath, sources, resources,
   generated inputs, and declared output trees.
3. Ask M3File to reconcile the complete inventory against its last
   checkpoint. For proven unchanged file generations only, reuse their
   original authenticated SHA-256 facts. Re-hash any changed, untracked or
   unsupported paths, including changed directory membership.
4. Compute existing (format-compatible) module task/goal identity. A hit
   requires a clean previous session, verified prerequisite generations and
   the current exact output identity. Unknown plugins, unqualified ABI
   graphs, processors, tests and publication still run through the original
   Maven lifecycle.
5. Before execution, revoke the affected task and dependent receipts. After
   successful original execution, revalidate the admitted input generation,
   verify outputs and publish the module batch once. A crash, cancellation,
   observer gap or incomplete run cannot publish a clean checkpoint.

No Maven hot-path LLM decision, timestamp-only shortcut or speculative
compiler optimization is allowed.

## Differential qualification

| Gate | Required result |
|---|---|
| Repeated unchanged invocation with gap-free OS journal | Previously authenticated file payload need not be re-read; old and new keys remain identical |
| Same-length, restored-mtime byte mutation | Input digest changes; affected goal executes |
| Create, delete, rename, replace, symlink flip, inode reuse | Complete inventory changes or source scan falls back |
| Modified POM, plugin, toolchain, dependency JAR or processor graph | Original goal context invalidates correctly |
| Deleted or tampered compiled output | Rebuild or independently verified restoration; never stale success |
| Nested reactor outputs and internal `.m3` updates | No spurious parent-source invalidation; explicit dependency changes still verified |
| Restart between observation and commit | No new success receipt; previous checkpoint remains authoritative |
| Journal overflow, missing segment, daemon restart, filesystem change | Explicit GAP / UNSUPPORTED, followed by full SHA-256 reconciliation |
| Linux, Windows/NTFS, macOS and unsupported/exFAT/network providers | Per-provider proof matrix and real negative tests; no fabricated supported status |
| Parallel Maven processes | Per-module lease, deterministic revocation and verified final fan-in |

Record measurements of **file payload bytes read**, **files hashed**,
**journal-admitted files**, **full-scan fallbacks by cause**, **skipped
eligible goals** and **actual original goals executed**. Reproducible
Java 21/Windows/Linux/macOS tests must compare the journal-assisted path
against a permanently available full-SHA oracle over identical filesystem
edits and process restarts. An unchanged second process can be called
`zero-rehash` only when its measured admitted file-payload read count is
zero and all unsupported cases demonstrably fall back.

## Integration sequence

Implement and qualify the M3JDK M3File evidence interface first, then
an OS-specific durable journal adapter, then M3DB's existing-receipt binding,
then Maven lifecycle/polyglot opt-in consumers. Deliver original
OpenRewrite/Maven recipes, source/ABI contract tests, safe failure modes
and a reproducible benchmark with each corresponding PR. Keep
`m3.lifecycle.incremental` opt-in and maintain existing public APIs.

**Status: design and acceptance criteria only.** No existing PR in this
Maven fork establishes an authenticated cross-process zero-rehash mechanism.
