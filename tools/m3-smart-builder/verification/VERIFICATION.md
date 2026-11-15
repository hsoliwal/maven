# M3 Smart Maven — actual verification receipt, 2026-11-15

Repository intended: `hsoliwal/maven`, isolated folder `tools/m3-smart-builder` on a review branch. Existing Maven Core and Maven root POM remain unchanged. All 19 original paths were absent on the checked default branch before GitHub create-file calls.

Environment: Linux, OpenJDK / javac 21.0.12.1. Local `mvn` executable is not installed, and no external Maven/plugin dependencies were downloaded. This is **not** a full Maven plugin descriptor, lifecycle or packaging qualification.

| Gate | Executed command | Observed |
|---|---|---|
| Syntax | `sh -n scripts/verify.sh` | PASS |
| POM XML | Python stdlib `xml.etree.ElementTree.parse('pom.xml')` | PASS (well-formed only) |
| Java core lint + compile | `javac -Xlint:all -Werror` via `scripts/verify.sh` | PASS |
| Maven adapter compile | `javac -Xlint:all -Werror` with *local API stubs* | PASS (not actual Maven API compatibility) |
| Java core tests | `SmartBuilderTest` via `scripts/verify.sh` | **10 groups passed** |
| MCP protocol integration | Synthetic `io.synexia.chromellm.standalone` test fixture module and subprocess | PASS (not a real model) |
| Repeat test and source SHA-256 | Rerun `scripts/verify.sh`; compare source hashes | PASS, zero changed source files |
| GitHub transport | Checked remote read-back against the exact local bundle text | **19/19 matched** |

The tested cases include secure direct-only POM inspection with DTD/XXE rejection, strict manifest parsing, duplicate/license/digest rejection, deterministic review JSON, no execution of candidate text, approved cache provisioning, SHA-256 verification, offline no-network behavior, corrupt cache hard failure, oversize and digest failure without publication, symlink rejection, JSON parser strictness, and a synthetic MCP infer_text exchange.

Not executed: `mvn verify`, actual plugin descriptor generation, real Chrome AI availability, real ChromeLLM inference, live HTTPS downloads, native Dawn/llama.cpp/JNI compilation, GPU driver qualification, real WebGPU inference, Windows/macOS installer. Do not promote any such gate to PASS.

See `00-toolchain.log`, `01-lint.log`, `02-compile-test.log`, `03-runtime-fixture.log`, `04-idempotent-replay.log` and `SOURCE_SHA256.tsv`. `pre-sha256=ABSENT` denotes newly created files, not a guessed previous digest.
