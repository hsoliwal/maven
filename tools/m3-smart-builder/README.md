# M3 Smart Maven Builder — opt-in Java 21 Maven plugin

Status: experimental. This project lives **outside the upstream Maven reactor** and does not change Maven's default lifecycle, POM APIs, dependency resolution or repository credentials.

## Contract

- `m3:plan` reads the project's `pom.xml` and an optional immutable TSV artifact manifest. It writes one review-only JSON plan under the project's existing Maven build directory (`target/m3-smart/plan.json` unless overridden). It does not build, alter the POM, download models, start Chrome or execute planned instructions.
- Optional `-Dm3.smart.llmBackend=cli|jni` enables **ChromeLLM's already existing** local MCP stdio server, selected using an explicit module JAR and local executable/JNI/model paths. The model returns *candidate commentary only* in the plan; it cannot set executable commands, artifact URLs, profiles or success statuses. No fallback to another model. A file from the existing Chrome browser agent can instead supply a candidate via `-Dm3.smart.candidateFile=/approved/candidate.txt`. The two modes are exclusive.
- `m3:provision` requires `-Dm3.smart.approveProvision=true`, an existing, explicit absolute cache directory, a manifest, a nonempty named profile and online permission. It processes only manifest rows selected by that profile. HTTPS transfers follow no redirects, are bounded, are SHA-256 verified, and are exclusively published into the approved cache. Nothing is executed or unpacked. A deterministic `target/m3-smart/provision.json` receipt is written only after all selected artifacts are verified. Normal Maven dependencies remain Maven Resolver's responsibility.
- `m3:provision` with Maven's `-o` checks and rehashes existing entries only. A corrupt entry fails rather than being overwritten; an absent entry fails rather than initiating networking. Cache entries are content-addressed: SHA-256 plus a fixed suffix, not user-provided filenames.
- No root or home writes are performed implicitly. The output/build directory must resolve within the explicitly invoked project checkout, under Maven's normal build directory. A cache directory must already exist and be operator approved. Plan/receipt filenames are fixed; no AI-controlled path, command or URL is accepted.
- GitHub native assets or model weights must be independently licensed and immutable SHA-256 pinned. A repository tag alone is not a digest. CDN/archive auto extraction, driver installation, unsigned execution, automatic model downloads at build time, and downloaded binary execution are outside this version.
- LLM advisory text is untrusted. No automated patch, Maven module selection, build invocation or API change is derived from it.

## Manifest v1

The strict UTF-8, tab-separated `m3-artifacts.tsv` schema is:

`id<TAB>profile<TAB>kind<TAB>url<TAB>sha256<TAB>maxBytes<TAB>licenseApproved`

Header required, no duplicate keys, no extra columns. `kind` is one of `model-gguf`, `native-bin`, `test-data`; `url` is a directly operator-approved HTTPS URL (no credentials, fragment, literal IP or redirect); `sha256` is lowercase 64-hex digest; `maxBytes` is 1..68719476736; `licenseApproved` must be `true`. No artifact URL is inferred from a GitHub repository name. All rows are validated even if not selected.

Example (replace the placeholders with independently verified values; **not runnable as-is**):

```text
id	profile	kind	url	sha256	maxBytes	licenseApproved
webgpu-runtime	webgpu-smoke	native-bin	https://example.org/approved-webgpu-package	REPLACE_WITH_64_HEX	10485760	true
```

## Commands (after building and installing this plugin)

```sh
mvn -f tools/m3-smart-builder/pom.xml clean verify
mvn -f tools/m3-smart-builder/pom.xml install
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.smart.profile=webgpu-smoke -Dm3.smart.manifest=/approved/m3-artifacts.tsv
mvn -o io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:provision \
    -Dm3.smart.profile=webgpu-smoke -Dm3.smart.manifest=/approved/m3-artifacts.tsv \
    -Dm3.smart.cacheDir=/approved/existing-cache -Dm3.smart.approveProvision=true
```

For ChromeLLM planning (local, browser-independent):

```sh
mvn io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.smart.llmBackend=cli \
    -Dm3.smart.chromeLlmJar=/approved/chromellm-standalone.jar \
    -Dm3.smart.llamaBin=/approved/llama-cli -Dm3.smart.modelPath=/approved/model.gguf
```

For the browser-managed Chrome AI model, use the separately installed Chrome agent to create candidate commentary, and pass the reviewed UTF-8 text using `-Dm3.smart.candidateFile`. This plugin does **not** claim to extract Chrome-managed model bytes or provide a browser-independent built-in Chrome AI runtime.

## Verification

`scripts/verify.sh` compiles the dependency-free Java core and runs its offline tests with JDK 21. A real Maven plugin build, a real ChromeLLM MCP session, a browser extension test, network artifact fetching and native GPU execution require separate evidence. Never treat the offline fixtures as those qualifications.

## Scope boundaries and repository ownership

`hsoliwal/maven` owns this opt-in plugin and plugin goal semantics. `hsoliwal/ChromeLLM` owns MCP stdio, lazy GGUF and JNI providers. `hsoliwal/dawn` and `hsoliwal/synexia-llmcpp` own native WebGPU components. `hsoliwal/com.synexia` owns reusable recipes, pattern evaluation and proof policy. Do not merge these contracts into a single mutable Maven Core file.
