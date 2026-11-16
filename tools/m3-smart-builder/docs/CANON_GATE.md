# Canon gate

The canon gate is the hard, executable form of the M3-SCALE v3.0 run contract (task packet, verify order, scope locks,
idempotence, output contract) plus the mandatory atomization and patternization proof. It has no warning mode: any
violation fails the run, and the final report is rendered from recorded evidence only.

## Run it

```sh
sh m3-build.sh canon <packet.tsv> <run-dir>          # from the repository root
mvn m3:canon -Dm3.canon.packet=<packet.tsv> -Dm3.canon.runDir=<run-dir>
```

Exit status: 0 pass, 1 stage, scope, atomization or contract failure, 2 invalid packet or invocation. The run directory
must be outside the repository or git-ignored. A run stopped by a crash resumes from its last completed stage when the
same command is repeated; a sealed run directory is immutable.

## Packet

UTF-8 `key<TAB>value` lines; `#` comments and blank lines are ignored; unknown or repeated keys are refused. Lists are
comma-separated. Relative file paths resolve from the packet's directory; target paths are repository-relative.

| Key | Rule |
| --- | --- |
| `task_packet` | `active` |
| `source_doc`, `source_doc_sha256` | readable file; optional pinned SHA-256 must match |
| `invariant_id` | required |
| `run.mode` | `dry-run` (no file may change during the run) or `apply` |
| `run.profile` | `fast`, `full` (must include `test`) or `custom` |
| `run.kind` | `change` (default) or `inventory` (dry-run only; adds the inventory artifacts) |
| `run.workers`, `run.shard_count`, `run.shard_index` | integers; more than one worker or shard requires `compute_resources` |
| `targets.modules`, `targets.files` | at least one; `.` names the whole repository |
| `invariant_locks` | must contain `PUBLIC_API:LOCKED,EXTERNAL_BEHAVIOR:LOCKED,DISABLED_PATHS:FORBIDDEN,EXCLUDED_PATHS:FORBIDDEN,BUILD_SCRIPTS:LOCKED` |
| `allowed_delta`, `forbidden`, `stop_conditions` | required |
| `verify_order` | ordered subset of `diff, atom_pattern, lint, compile, test, runtime`; `diff, atom_pattern, lint, compile` are mandatory |
| `verify.<stage>` | shell command for each declared stage; written to `logs/<n>-<stage>.sh` and run by `sh` in the repository root |
| `output_contract` | must list `STATUS.tsv, FINAL_REPORT.md, RUN_CONTEXT.tsv, PROVENANCE.tsv, VERIFY_CONTRACT.tsv, OUTPUT_CONTRACT.tsv, ATOM_PATTERN.tsv` |
| `pattern_catalog` | readable file of admitted pattern ids (column 1); [canon-patterns.tsv](canon-patterns.tsv) is the seed |
| `excluded_paths`, `disabled_paths` | optional; any change below them fails |
| `base_ref` | optional, default `HEAD`; scope and atom checks apply to every path that differs from it |
| `baseline_run` | optional earlier run directory; a repeat with a different post-hash digest fails |

Stage commands see `CANON_RUN_DIR`, `CANON_ROOT`, `CANON_STAGE`, `CANON_MODE`, `CANON_WORKERS`, `CANON_SHARD_INDEX`,
`CANON_SHARD_COUNT` and `CANON_SHARD_MANIFEST`; `CANON_SHELL` replaces `sh`.

## Atomization and patternization

The `atom_pattern` stage must write `$CANON_RUN_DIR/ATOM_PATTERN.tsv`, tab-separated, with the header
`path sha256 atom pattern role exact_hash logic_hash structure_hash sim_hash`.

- Every row must hash-match the file as it is at the seal (`sha256`).
- Every pattern must be admitted by `pattern_catalog` or be a typed residue written `RESIDUE:<reason>`; `UNKNOWN` is
  never admitted. A residue that keeps recurring is the candidate for a new catalog row.
- Every file that differs from `base_ref` needs at least one row.
- Only canonical repository-relative paths are accepted in rows (no absolute paths, `.`/`..` components,
  backslashes, empty components or control characters). A symbolic link does not qualify as a regular
  source even if its target's digest matches. **Every path component, including intermediate
  directories, must be an ordinary non-symlink component**; rows through linked parent directories
  fail with `ATOM_NONREGULAR_ROW` rather than hashing the target.
- The current table has no deletion/nonregular-path proof representation. **Any changed path that is
  deleted, a symlink, a gitlink or another nonregular entry is refused**, rather than silently
  counted as atomized. A future explicit deletion evidence format must be separately designed,
  tested and versioned before deletion can be admitted.
- Hash planes: `exact_hash`, `logic_hash` and `structure_hash` are 64 hex digits and `sim_hash` (SimHash-64) is 16.
  Every atom of a `.java` file must carry all four; other files may write `-`.
- Atoms that share a plane value are listed in `ATOM_CLUSTERS.tsv` as proposals ("These atoms may be similar. Please
  confirm via hash or diff."); the gate never decides equivalence.

## Violations

`STAGE_FAILED`, `HEAD_MOVED`, `DRY_RUN_MUTATION`, `OUT_OF_SCOPE`, `EXCLUDED_PATH`, `DISABLED_PATH`, `BUILD_SCRIPT_LOCKED`
(a build script changes without being named in `targets.files`), `ATOM_PATTERN_SCHEMA`, `ATOM_STALE`, `PATTERN_UNKNOWN`,
`PATTERN_NOT_ADMITTED`, `ATOM_PLANES_MISSING`, `ATOM_UNCOVERED`, `ATOM_PATTERN_MISSING`, `ATOM_NONREGULAR_ROW`, `ATOM_NONREGULAR_CHANGE`,
`NON_IDEMPOTENT`, `BASELINE_UNREADABLE`, `OUTPUT_CONTRACT_MISSING`.

## Artifacts

Always: `STATUS.tsv` (append-only stage ledger), `RUN_CONTEXT.tsv`, `PROVENANCE.tsv`, `VERIFY_CONTRACT.tsv` (stages,
locks and the atom check), `OUTPUT_CONTRACT.tsv`, `FINAL_REPORT.md` (files changed, exact delta summary, exact
verification executed, exact blockers/failures, artifact paths), `BUILD_LOG.tsv` (file, line, column, severity from every
stage log), `PRE_HASH.tsv`/`POST_HASH.tsv`, `PRE_DIRTY.tsv`/`POST_DIRTY.tsv`, `SHARD_MANIFEST.txt`, `ATOM_CLUSTERS.tsv` and `logs/`.
Inventory runs add `INVENTORY.tsv`, `CLUSTERS_EXACT.tsv`, `CLUSTERS_NORMALIZED.tsv`, `ACTION_QUEUE.tsv` and
`SUMMARY.tsv`; every action is proposal-only and carries "These files may be similar. Please confirm via hash or diff."

## Not enforced by the gate

`PUBLIC_API` and `EXTERNAL_BEHAVIOR` are delegated to the declared stages (for example an API-compatibility check in
`lint` and the test suite); `VERIFY_CONTRACT.tsv` records that delegation. NUMA binding is not used.

The same sources ship in M3JDK as `make/jdk/src/classes/build/tools/canongate`; the two copies differ only in their
package line.
