<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Maven 4 additive source preservation — Spotless admission receipt (2026-10-27)

Scope: PR #14 only; do not rewrite master, do not rebase or squash, and retain both upstream Maven 4 and M3 fork histories.

Original PR head: `ce4530f12c60c00daded087c065d80b316f2b76d`.
Preservation ref: `preserve/pr14-before-spotless-repair-20261027`.
The original Java CI failed `maven-impl` Spotless for the two existing `DefaultCompilerFactsService` production/test owners.
One-shot workflow originally failed because it invoked missing `./mvnw`, exit 127.
Connector-authored invocation fix: `9f682f0d90ff5ce5c07ae0487c5dbfdd1277f3b3`.
One-shot formatter run: https://github.com/hsoliwal/maven/actions/runs/40435009977 — success.
Exact formatter commit: `e6d56bbc490309c1f8557f60057f502576b1bf00`.
The formatter changed only two compiler-facts Java files and removed its one-shot workflow. No other source file was reported changed.

**Admission remains open:** full Maven reactor, integration tests, additional JNI/M3 watcher parity and current-head GitHub CI must execute and pass. No canonical master merge is authorized solely by this receipt.

Source/provenance: https://github.com/hsoliwal/maven/pull/14
