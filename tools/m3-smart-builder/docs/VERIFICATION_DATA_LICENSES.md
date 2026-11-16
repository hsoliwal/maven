<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Historical verification records: license and provenance

The 17 repository-produced records below are distributed under the project's
Apache-2.0 license; see [LICENSE](../../../LICENSE). They comprise 15 captured
data files and two historical verification narratives. Their exact bytes are
retained so that recorded digests, replay results, and original qualification
limits remain auditable. License information for this closed set is recorded
here without inserting new text into the historical records.

## Source and scope

Repository: [hsoliwal/maven](https://github.com/hsoliwal/maven).
Source snapshot: [`328e7c25e8a47ac83a4984fa3bf1b74442d782cc`](https://github.com/hsoliwal/maven/tree/328e7c25e8a47ac83a4984fa3bf1b74442d782cc).
Each row identifies the file's Git blob at that snapshot and its SHA-256 digest.
The byte count is the complete stored file, with no newline normalization.

`VERIFICATION.md` and `DIAGNOSE_VERIFICATION.md` are contemporaneous descriptions
of earlier runs. The other files contain toolchain observations, lint and test
output, source digest inventories, change events, and replay observations from
those runs. Their original descriptions and unverified gates remain intact.
The OpenJDK names and version strings in `10-toolchain.log` identify the observed
toolchain; no OpenJDK implementation or binary is included in these records.

The existing Apache RAT configuration in the root `pom.xml` enumerates these
17 paths individually. That configuration preserves the recorded bytes while
continuing to inspect authored source, configuration, and documentation,
including this inventory. A new verification file requires its own licensing
treatment; the list does not automatically cover another file in the directory.

## Exact inventory

Every path below is relative to the repository root. The recorded license for
each row is `Apache-2.0` under the repository's license above.

| Path | Kind | Bytes | SHA-256 | Git blob SHA-1 |
| --- | --- | ---: | --- | --- |
| `tools/m3-smart-builder/verification/10-toolchain.log` | historical verification data | 294 | `0c270c13cd918052f36abfa1a624431b607767af6396fc1afe6ffbd645eecf64` | `c49a1a678157a7feb07b3af8e7719083cef4417c` |
| `tools/m3-smart-builder/verification/11-lint.log` | historical verification data | 74 | `adf42343ba42d4fef62f1c319a990019257fb81a26f46ad6529d383a27893606` | `121c228c399fde5b5d576f0a9dcb821c71604c28` |
| `tools/m3-smart-builder/verification/12-compile-test-runtime.log` | historical verification data | 89 | `f476b184c12e9aa58d83f5d467d217dc064fe232981092a40889b6b7255087e8` | `bf223dfc64dd21962c63844441406ca440548647` |
| `tools/m3-smart-builder/verification/13-idempotence.log` | historical verification data | 89 | `f476b184c12e9aa58d83f5d467d217dc064fe232981092a40889b6b7255087e8` | `bf223dfc64dd21962c63844441406ca440548647` |
| `tools/m3-smart-builder/verification/14-replay.log` | historical verification data | 89 | `f476b184c12e9aa58d83f5d467d217dc064fe232981092a40889b6b7255087e8` | `bf223dfc64dd21962c63844441406ca440548647` |
| `tools/m3-smart-builder/verification/15-source-replay-before.tsv` | historical verification data | 3152 | `be3b97de1a61cc576f128f0a756074c23a836c592b929516a424551ce207b348` | `f94534ee7a83389e2cca5793ee4a2c3162d20b71` |
| `tools/m3-smart-builder/verification/16-source-replay-result.log` | historical verification data | 19 | `cfe61630b6e00b2ea7839c277df7f57b9c16cbddc15d80f0914878ddb2054fe3` | `ddd8c2ad5f2e6f0dd3d9011d2c5aab5ca362788b` |
| `tools/m3-smart-builder/verification/CHANGE_EVENT_LOG.jsonl` | historical verification data | 1544 | `fbf2b561f43b8bc1fe67e86f4d8d41b71c666e5c700f851c44fe17f419ddbf49` | `836119ab7028dfd808d78c155335748f50c5a094` |
| `tools/m3-smart-builder/verification/DIAGNOSE_SOURCE_SHA256.tsv` | historical verification data | 2346 | `1857231cc309fe5b6e7dd9e8f0a339053f55c699e35ad35fc85751bcd580d747` | `ffa5fbd483dd478986073bb3e25bf0b22b66542c` |
| `tools/m3-smart-builder/verification/DIAGNOSE_VERIFICATION.md` | historical verification narrative | 2847 | `8c648d6c65d2ae4957d1f0fbdcfd4f3ed166e2e29cc74d1524302487a7c36599` | `0702fe16be3d8bf2004e468c968b69afdba1d740` |
| `tools/m3-smart-builder/verification/MCP_REPLY_CHANGE_EVENTS.tsv` | historical verification data | 1011 | `7412d70a815f1feea11754da7fce282e3bdfdbfa9d92d2be784eb7c3f97b35b1` | `baf15f6b392c220cab6a36e052e44dd5592d9929` |
| `tools/m3-smart-builder/verification/MCP_REPLY_REPLAY.log` | historical verification data | 89 | `f476b184c12e9aa58d83f5d467d217dc064fe232981092a40889b6b7255087e8` | `bf223dfc64dd21962c63844441406ca440548647` |
| `tools/m3-smart-builder/verification/MCP_REPLY_RUNTIME.log` | historical verification data | 89 | `f476b184c12e9aa58d83f5d467d217dc064fe232981092a40889b6b7255087e8` | `bf223dfc64dd21962c63844441406ca440548647` |
| `tools/m3-smart-builder/verification/MCP_REPLY_SHA256_AFTER_REPLAY.tsv` | historical verification data | 480 | `c8ff99651f24456b2fb9af3a17da2ab380745bf1429051f94cf3b8d1bda2ca2e` | `f9370181c12af1d3759e4b7868ed07b28052b5b4` |
| `tools/m3-smart-builder/verification/MCP_REPLY_SHA256_BEFORE_REPLAY.tsv` | historical verification data | 480 | `c8ff99651f24456b2fb9af3a17da2ab380745bf1429051f94cf3b8d1bda2ca2e` | `f9370181c12af1d3759e4b7868ed07b28052b5b4` |
| `tools/m3-smart-builder/verification/PRE_CHANGE_SHA256.tsv` | historical verification data | 598 | `48b3b59f3ef7e99b1f62b9c63eaa9c479bf02780d9ec9333e6130c104722f213` | `475e9cbfd284863a6e1a6f830061c61417a82225` |
| `tools/m3-smart-builder/verification/VERIFICATION.md` | historical verification narrative | 2364 | `032d5e8ad3b294e24c2405e4d3207cb496f9d88a16cd34e4e58b5202b2bcb6cd` | `05d34bccc4eff047a3bb556f5b33c0954750536b` |

## Checking preservation

The existing Maven Enforcer plugin runs `m3-preserve-verification-records` during the root
project's `validate` phase. Its 17 built-in `requireTextFileChecksum` rules
check every exact RAT exception above with SHA-256, UTF-8, and UNIX line
separator normalization. The execution has `inherited=false`, so child
modules do not reinterpret these repository-root paths. A missing file or
changed text fails validation before the normal Apache RAT scan.

All pinned Git files already use UNIX line separators, so the normalized
checksums equal the raw SHA-256 values in the table. Platform checkout line
ending conversion is normalized for the build check; the Git blob IDs and
raw byte counts continue to bind the exact committed source snapshot.

Compare the recorded Git blobs with the source snapshot and run the normal
Maven validation and Apache RAT gates after source changes. The historical
records continue to describe only their original execution scope.
