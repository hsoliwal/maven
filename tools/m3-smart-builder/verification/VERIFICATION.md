# M3 Smart Maven Builder — verification receipt (2026-11-15)

This opt-in plugin is isolated under `tools/m3-smart-builder` and does not modify the fork's root POM, Maven Core, Maven Resolver, existing public APIs, or default lifecycle.

## Executed local checks

| Gate | Observation |
| --- | --- |
| Source inventory | 19 new files, absent on the base branch before creation |
| Shell syntax | `sh -n scripts/verify.sh` passed |
| POM syntax | Python stdlib XML parser passed (not Maven model validation) |
| JDK core | OpenJDK 21.0.12.1; `javac -Xlint:all -Werror` passed |
| Maven adapter | Compiled with isolated test stubs only; **not** actual Maven runtime |
| Core regression | **9/9 test groups passed** |
| ChromeLLM MCP | Synthetic stdio server handshake and infer_text fixture passed |
| Replay | Second JDK test run passed; source hashes unchanged |
| GitHub source transport | **19/19 remote files read back exactly** after creation |

Test categories: strict manifest parsing, duplicate/license/digest rejection, deterministic read-only JSON planning, untrusted LLM candidates, SHA-256 verified cache reuse, offline no-network behavior, corrupt cache hard failure, size/digest rejection without publication, cache symlink defense, strict JSON handling, and synthetic ChromeLLM MCP protocol exchange.

## Unverified gates

`mvn verify` and plugin descriptor generation (Maven executable unavailable in the test container); installation and execution within real Maven; real Chrome built-in AI; real ChromeLLM inference; live HTTPS asset download; native Dawn/llama.cpp/JNI build; GPU driver qualification; WebGPU inference; Windows/macOS installations.

No browser-managed Gemini Nano weights are accessed, copied, or redistributed. Only explicit SHA-256-pinned, license-approved native/test binaries may be provisioned; no downloaded binary is unpacked or executed.

To re-run the JDK-only proof: `sh tools/m3-smart-builder/scripts/verify.sh`. For full packaging, from the separate plugin folder run `mvn clean verify` on a network-enabled, approved build host.

Source package: `m3-smart-builder-maven-plugin.zip` SHA-256 `3f9f64b784978a050f2ccbdcaa86157b3530a1d6d24dc0800b8ca4ad13af1e8f`. The ZIP includes local logs and per-file hashes; these are not GitHub CI results.
