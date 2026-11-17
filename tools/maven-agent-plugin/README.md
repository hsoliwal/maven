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
# Apache Maven Agent Plugin

Describes a build for coding agents. Nothing of the build runs: the goals are Maven's own execution plan.

| Goal | Writes |
| --- | --- |
| `agent:describe-project` | `target/agent/project.json`: modules in reactor order with module dependencies, source and test roots and the goals bound up to `agent.phase` (default `verify`), and the commands to build, build in agent mode, build one module and run one test |
| `agent:llms-txt` | `llms.txt` in the top-level project: the same description in the [llms.txt](https://llmstxt.org) layout, with Build, Test, Results, Modules and Plugin goals sections |

```sh
mvn org.apache.maven.plugins:maven-agent-plugin:0.1.0-SNAPSHOT:describe-project
mvn org.apache.maven.plugins:maven-agent-plugin:0.1.0-SNAPSHOT:llms-txt
mvn org.apache.maven.plugins:maven-agent-plugin:0.1.0-SNAPSHOT:llms-txt -Dagent.check=true   # fails when stale
```

Both files are deterministic (no timestamps, root-relative paths), so `-Dagent.check=true` in a build keeps
`llms.txt` current. The command spellings (`MavenCommands`) and the JSON writer (`JsonText`) come from the build
events extension (`tools/m3-smart-builder/extension`), whose agent mode writes the files the Results section links:
`summary.txt`, `failure.md`, `failure.json`, `build-events.jsonl`
([formats](../m3-smart-builder/extension/docs/agent-build-events.md)).

| Property | Default | Meaning |
| --- | --- | --- |
| `maven.agent.dir` | `target/agent` of the top-level project | where `project.json` goes |
| `agent.phase` | `verify` | last phase whose goals are listed |
| `agent.llmsTxt` | `llms.txt` of the top-level project | the llms.txt file |
| `agent.check` | `false` | fail instead of writing when the file differs |

Requires Java 17 and Maven 3.9 or newer. Tests: `mvn verify` runs `LlmsTxtRendererTestMain` (llms.txt and
project.json of a fixed description, byte for byte).
