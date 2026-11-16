<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

<!-- SPDX-License-Identifier: Apache-2.0 -->
# ChromeLLM MCP adviser — strict response validation

The Maven Smart Builder invokes one explicitly configured ChromeLLM stdio process. Model
output is untrusted commentary. This contract tightens the existing protocol checks
without changing public Java/Maven signatures, model-provider selection, Maven lifecycle,
provider timeouts, or output receipt schemas.

## Request/response invariants

1. Initialization sends JSON-RPC `2.0`, numeric ID `1` and the pinned MCP protocol
   version `2025-06-18`. The response must carry `jsonrpc: "2.0"`, the matching ID,
   an object `result`, no JSON-RPC `error`, and the exact negotiated version.
2. The tool call sends ID `2`, selects only `infer_text`, and accepts a response with
   the same JSON-RPC version and matching ID, no JSON-RPC `error`, a `result` object,
   `isError: false`, and exactly one `content` element of `type: "text"` containing
   a nonempty UTF-8 candidate of at most 32,768 Java characters.
3. Any mismatch fails closed. Neither error response nor a malformed/incomplete
   reply may be converted into a candidate. No provider fallback or command execution.
4. The reply reader remains bounded to 1,048,576 characters per JSON frame.
   The complete subprocess is subject to the caller's existing deadline and disposed
   on failure. No credentials, source content or raw Maven logs enter the prompt.
5. The existing JSON parser intentionally represents numeric JSON values as strings;
   matching ID values therefore compares their canonical decimal renderings. This
   change does not introduce a new parser representation or alter other callers.

## Fixture/acceptance matrix

The fixture emits one valid response pair and malformed variants for initialization
ID/version/error, call ID/version/error, missing/true `isError`, non-text content and
empty text. The JDK-only runtime gate must accept the valid pair and reject every
negative fixture. Tests invoke an isolated synthetic Java module, not Chrome, a trained
model, real MCP network transport, native JNI, or hardware inference.
