# M3 Smart Builder follow-up verification — 2026-11-15

Scope: additive `m3:diagnose` plus a shared guarded receipt writer for existing
`m3:plan` and `m3:provision`. Public Maven APIs, existing POM values, selected
inference backends, artifact cache contract and existing receipt schemas remain
unchanged. Documentation was written before patching production Java classes.

## Executed gates, in required order

| Stage | Exact check | Result |
| --- | --- | --- |
| Inventory | Prior PR #38 merged; remote master equals its merge SHA; audited all existing plugin Java files | PASS |
| Baseline | `sha256sum` of six pre-existing files | Recorded in `PRE_CHANGE_SHA256.tsv` |
| Patch | Added read-only diagnostic core, Mojo, tests and safe-output writer | APPLIED locally |
| Lint | `sh -n scripts/verify.sh`; Python stdlib `ElementTree.parse('pom.xml')` | PASS (POM well-formedness only) |
| Compile | `sh scripts/verify.sh` uses Java 21 `javac -Xlint:all -Werror` on core, stub adapters and synthetic MCP fixture | PASS |
| Test | 13 core groups (without native fixture); 3 Mojo adapter groups (using Maven stubs) | PASS |
| Runtime | 14 core groups (including synthetic MCP subprocess fixture) | PASS |
| Replay | reran `scripts/verify.sh` twice and checked 27 source/config hashes before vs after execution | PASS, 0 changed files |

The regression tests cover Maven-log pattern classification without raw-log
leakage, bounded source/JNI marker inventory, deterministic diagnostic JSON,
source digest changes, linked source denial, module output symlink denial,
receipt symlink denial, explicit output whitelist, original 10 core groups,
and execution of all three goal bodies against minimal Maven API stubs.

## Evidence files

`10-toolchain.log`, `11-lint.log`, `12-compile-test-runtime.log`,
`13-idempotence.log`, `14-replay.log`, `15-source-replay-before.tsv`,
`16-source-replay-result.log`, `PRE_CHANGE_SHA256.tsv` and
`POST_CHANGE_SHA256.tsv` are stored alongside this receipt.

## Unverified gates

- `mvn` is not installed in the local verification environment. Real Maven
  model loading, goal descriptor generation, packaging, installation,
  plugin execution in Maven 3.9.x / Maven 4.x, and fork compatibility
  are **UNVERIFIED**.
- The synthetic MCP fixture is not a real ChromeLLM inference session;
  current Chrome Prompt API/Gemini Nano and browser extension runtime
  remain **UNVERIFIED**.
- No live GitHub HTTPS download, GPU/Dawn/llama.cpp binary qualification,
  native-JNI model execution, or Windows/macOS test was run.
- Filesystem safety assumes an exclusively owned checkout and cache; actively
  malicious concurrent directory renaming is out of scope.

Do not merge solely on these offline observations. Do not claim a passing GitHub
Actions job without a job result. Keep PR draft pending real Maven packaging.
