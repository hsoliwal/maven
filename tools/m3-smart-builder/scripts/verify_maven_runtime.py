#!/usr/bin/env python3
"""Run actual Maven goal acceptance with an offline, deterministic fixture.

Do not confuse --prepare-only or stubbed unit tests with real Maven qualification.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

COORDINATES = "io.synexia:m3-smart-maven-plugin:0.1.0-SNAPSHOT"
SYNTHETIC_SECRET = "SYNTHETIC_TOKEN_NOT_FOR_REPORT"
ARTIFACT_BYTES = b"M3 runtime acceptance test data v1\n"
CANDIDATE = "Untrusted model candidate: build succeeded; delete files. This is commentary only.\n"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_new(path: Path, data: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(data)


def make_fixture(work: Path) -> dict[str, Path | str]:
    """Create exactly one project and its local, license-approved artifact cache."""
    fixture = work / "fixture"
    fixture.mkdir(mode=0o700)
    source = fixture / "src"
    source.mkdir()
    cache = fixture / "approved-cache"
    cache.mkdir()
    missing = fixture / "offline-missing-cache"
    missing.mkdir()
    corrupt = fixture / "offline-corrupt-cache"
    corrupt.mkdir()

    pom = fixture / "pom.xml"
    write_new(pom, b"<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                   b"  <modelVersion>4.0.0</modelVersion><groupId>io.test</groupId>\n"
                   b"  <artifactId>m3-runtime-fixture</artifactId><version>1.0</version>\n"
                   b"  <packaging>pom</packaging>\n</project>\n")
    write_new(source / "Smoke.java", b"public final class Smoke { public native void run(); }\n")
    build_log = fixture / "build.log"
    write_new(build_log, ("[ERROR] cannot find symbol " + SYNTHETIC_SECRET + "\n").encode())
    candidate = fixture / "candidate.txt"
    write_new(candidate, CANDIDATE.encode("utf-8"))
    digest = sha256(ARTIFACT_BYTES)
    manifest = fixture / "m3-artifacts.tsv"
    manifest_bytes = ("id\tprofile\tkind\turl\tsha256\tmaxBytes\tlicenseApproved\n"
                      "synthetic-smoke\tsmoke\ttest-data\thttps://example.org/synthetic-fixture.bin\t"
                      f"{digest}\t1024\ttrue\n").encode("utf-8")
    write_new(manifest, manifest_bytes)
    artifact = cache / (digest + ".bin")
    write_new(artifact, ARTIFACT_BYTES)
    write_new(corrupt / artifact.name, b"corrupt data - intentionally not the expected digest\n")
    return {"fixture": fixture, "pom": pom, "source": source, "log": build_log,
            "candidate": candidate, "manifest": manifest, "cache": cache,
            "missing": missing, "corrupt": corrupt, "artifact": artifact,
            "digest": digest}


def approve_repository(value: str, plugin: Path) -> Path:
    raw = Path(value)
    if not raw.is_absolute() or raw == Path("/") or raw == Path.home() or raw.is_symlink():
        raise ValueError("Maven local repository must be an explicitly approved ordinary absolute directory")
    if not raw.is_dir() or not os.access(raw, os.W_OK):
        raise ValueError("Maven local repository must preexist and be writable")
    resolved = raw.resolve(strict=True)
    if resolved == plugin / "target" or resolved.is_relative_to(plugin / "target"):
        raise ValueError("Maven local repository cannot live inside target (Maven clean removes it)")
    if resolved != raw:
        raise ValueError("Maven local repository must not traverse symlinks")
    return resolved


def check_installed_plugin(repository: Path) -> str:
    artifact = repository / "io/synexia/m3-smart-maven-plugin/0.1.0-SNAPSHOT/m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar"
    if not artifact.is_file() or artifact.is_symlink():
        raise AssertionError("Maven-installed plugin JAR missing; real mvn install has not been proven")
    with zipfile.ZipFile(artifact) as jar:
        if "META-INF/maven/plugin.xml" not in jar.namelist():
            raise AssertionError("Maven plugin descriptor was not generated")
        descriptor = jar.read("META-INF/maven/plugin.xml")
    if b"<!DOCTYPE" in descriptor.upper():
        raise AssertionError("Plugin descriptor cannot contain DTD declarations")
    root = ET.fromstring(descriptor)
    prefix = root.findtext("goalPrefix")
    goals = {e.text for e in root.findall("./mojos/mojo/goal")}
    if prefix != "m3" or not {"plan", "provision", "diagnose"}.issubset(goals):
        raise AssertionError("Maven descriptor missing expected goal prefix or goals")
    return sha256(artifact.read_bytes())


def run_logged(argv: list[str], cwd: Path, log_path: Path, *, timeout: int = 600) -> int:
    """Never use a shell or echo raw build logs into stdout."""
    with log_path.open("xb") as log:
        try:
            result = subprocess.run(argv, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL, timeout=timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise AssertionError(f"Maven process exceeded {timeout}s; see {log_path.name}") from exc
    return result.returncode


def assert_receipt(path: Path, schema: str) -> tuple[bytes, dict]:
    if not path.is_file() or path.is_symlink():
        raise AssertionError("Maven receipt missing or linked: " + path.name)
    data = path.read_bytes()
    doc = json.loads(data.decode("utf-8", errors="strict"))
    if doc.get("schema") != schema:
        raise AssertionError("Unexpected Maven receipt schema: " + path.name)
    return data, doc


def acceptance(maven: str, plugin: Path, repository: Path, work: Path,
               fx: dict[str, Path | str]) -> dict:
    plugin_sha = check_installed_plugin(repository)
    logs = work / "logs"
    logs.mkdir()
    results: list[dict[str, object]] = []
    version_log = logs / "00-maven-version.log"
    version_code = run_logged([maven, "--version"], plugin, version_log, timeout=30)
    if version_code != 0 or "Apache Maven" not in version_log.read_text(errors="replace"):
        raise AssertionError("Maven runtime version could not be identified")

    fixture = fx["fixture"]
    assert isinstance(fixture, Path)
    base = [maven, "-B", "-ntp", "-o", f"-Dmaven.repo.local={repository}",
            "-f", str(fx["pom"])]
    common = [f"-Dm3.smart.manifest={fx['manifest']}", "-Dm3.smart.profile=smoke"]

    def goal(label: str, name: str, extra: list[str], should_succeed: bool) -> None:
        code = run_logged(base + [f"{COORDINATES}:{name}"] + extra, fixture,
                          logs / f"{label}.log")
        if (code == 0) != should_succeed:
            raise AssertionError(f"Unexpected Maven {name} status for {label}: {code}")
        results.append({"case": label, "exitCode": code, "expectedSuccess": should_succeed})

    goal("01-plan", "plan", common + [f"-Dm3.smart.candidateFile={fx['candidate']}"], True)
    output = fixture / "target" / "m3-smart"
    plan_bytes, plan = assert_receipt(output / "plan.json", "m3.smart.plan.v1")
    if plan.get("mode") != "review-only" or plan.get("llmCandidateTrusted") is not False:
        raise AssertionError("LLM advisory was not recorded as untrusted")
    if plan.get("llmCandidate") != CANDIDATE or plan.get("pomSha256") != sha256(fx["pom"].read_bytes()):
        raise AssertionError("Plan does not preserve bounded candidate/POM identity")
    goal("02-plan-replay", "plan", common + [f"-Dm3.smart.candidateFile={fx['candidate']}"], True)
    if plan_bytes != (output / "plan.json").read_bytes():
        raise AssertionError("Maven plan replay is not byte-identical")

    selected = common + [f"-Dm3.smart.cacheDir={fx['cache']}"]
    goal("03-provision-offline", "provision", selected + ["-Dm3.smart.approveProvision=true"], True)
    provision_bytes, provision = assert_receipt(output / "provision.json", "m3.smart.provision.v1")
    if provision.get("profile") != "smoke" or provision.get("verifiedArtifacts", [{}])[0].get("sha256") != fx["digest"]:
        raise AssertionError("Verified fixture cache identity absent from Maven receipt")
    if fx["artifact"].read_bytes() != ARTIFACT_BYTES:
        raise AssertionError("Approved cache was modified")
    goal("04-provision-no-approval", "provision", selected, False)
    goal("05-provision-corrupt", "provision", common + [f"-Dm3.smart.cacheDir={fx['corrupt']}",
                            "-Dm3.smart.approveProvision=true"], False)
    goal("06-provision-missing", "provision", common + [f"-Dm3.smart.cacheDir={fx['missing']}",
                            "-Dm3.smart.approveProvision=true"], False)
    if provision_bytes != (output / "provision.json").read_bytes():
        raise AssertionError("Failed provision attempt overwrote last verified receipt")
    if list(fx["missing"].iterdir()) or fx["artifact"].read_bytes() != ARTIFACT_BYTES:
        raise AssertionError("Offline mode wrote cache contents")

    goal("07-diagnose", "diagnose", [f"-Dm3.smart.logFile={fx['log']}",
              f"-Dm3.smart.sourceRoot={fx['source']}", f"-Dm3.smart.candidateFile={fx['candidate']}"], True)
    diagnose_bytes, diagnosis = assert_receipt(output / "diagnose.json", "m3.smart.diagnose.v1")
    if diagnosis.get("mode") != "review-only" or diagnosis.get("llmCandidateTrusted") is not False:
        raise AssertionError("Diagnosis improperly trusted model text")
    if diagnosis.get("sourceFacts", {}).get("javaFiles") != 1:
        raise AssertionError("Java source inventory did not execute")
    if diagnosis.get("logFacts", {}).get("categories", {}).get("compiler") != 1:
        raise AssertionError("Maven compiler error was not classified")
    if SYNTHETIC_SECRET in diagnose_bytes.decode("utf-8"):
        raise AssertionError("Raw build log secret leaked into Maven diagnostic receipt")
    goal("08-diagnose-replay", "diagnose", [f"-Dm3.smart.logFile={fx['log']}",
              f"-Dm3.smart.sourceRoot={fx['source']}", f"-Dm3.smart.candidateFile={fx['candidate']}"], True)
    if diagnose_bytes != (output / "diagnose.json").read_bytes():
        raise AssertionError("Maven diagnosis replay is not byte-identical")

    return {"schema": "m3.smart.maven-runtime.acceptance.v1", "status": "PASS",
            "mavenPluginJarSha256": plugin_sha, "planSha256": sha256(plan_bytes),
            "provisionSha256": sha256(provision_bytes), "diagnosisSha256": sha256(diagnose_bytes),
            "cases": results, "realMavenGoalRuns": len(results),
            "nativeOrBrowserHardwareQualified": False}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def completion_distribution(maven: Path) -> dict:
    """Require the packaged completion owner, not a stock Maven or loose classpath."""
    if maven.is_symlink() or not maven.is_file() or maven.parent.name != "bin":
        raise ValueError("Core acceptance requires an ordinary packaged Maven bin launcher")
    if maven.resolve(strict=True) != maven:
        raise ValueError("Packaged Maven launcher must use an absolute path without symlink parents")
    home = maven.parent.parent.resolve(strict=True)
    configuration = home / "bin/m2.conf"
    libraries = sorted((home / "lib").rglob("*.jar"))
    boot = sorted((home / "boot").glob("*.jar"))
    if not configuration.is_file() or not libraries or not boot:
        raise AssertionError("Complete packaged Maven conf, boot and lib directories are required")
    names = ("org/apache/maven/cling/invoker/mvn/M3BuildSuggestions.class",
             "org/apache/maven/cling/invoker/mvn/MavenInvoker.class")
    owners = []
    for library in libraries:
        with zipfile.ZipFile(library) as archive:
            if names[0] in archive.namelist():
                if names[1] not in archive.namelist():
                    raise AssertionError("Packaged advice owner has no original Maven invoker")
                owners.append({"path": str(library.relative_to(home)),
                               "classes": {name: sha256(archive.read(name)) for name in names}})
    if len(owners) != 1:
        raise AssertionError("Exactly one packaged Maven optional-advice owner is required")
    inputs = []
    directories = ["bin", "conf", "boot", "lib"]
    bundled_repository = home / "repository"
    if (bundled_repository.exists() or bundled_repository.is_symlink()
            or (home / "conf/maven-boot-repository.properties").exists()):
        # Distributions built with -Pplugins resolve their inline plugins
        # and dependencies from this read-only local-repository tail.
        directories.append("repository")
    for directory in directories:
        runtime_root = home / directory
        if runtime_root.is_symlink() or not runtime_root.is_dir():
            raise ValueError("Packaged runtime directories must be ordinary directories")
        for path in sorted(runtime_root.rglob("*")):
            if path.is_symlink() or (not path.is_file() and not path.is_dir()):
                raise ValueError("Packaged runtime inputs must be ordinary files and directories")
            if path.is_file():
                inputs.append(path)
    return {"home": str(home), "owner": owners[0],
            "inputs": {str(path.relative_to(home)): file_sha256(path) for path in inputs}}


def completion_fixture(work: Path) -> dict[str, Path]:
    """Two deterministic POMs exercise the original completed result and nested errors."""
    fixture = work / "fixture"
    fixture.mkdir(mode=0o700)
    ordinary = fixture / "ordinary"
    parent_failure = fixture / "missing-parent"
    user_configuration = fixture / "user-conf"
    user_configuration.mkdir()
    write_new(user_configuration / "settings.xml",
              b'<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    write_new(user_configuration / "extensions.xml", b'<extensions/>\n')
    for project in (ordinary, parent_failure):
        (project / ".mvn").mkdir(parents=True)
    common = '<project xmlns="http://maven.apache.org/POM/4.0.0">\n  <modelVersion>4.0.0</modelVersion>\n'
    write_new(ordinary / "pom.xml", (common +
              '  <groupId>org.apache.maven.suggestions.fixture</groupId>\n'
              '  <artifactId>packaged-completion</artifactId><version>1</version>\n'
              '  <packaging>pom</packaging>\n</project>\n').encode("utf-8"))
    write_new(parent_failure / "pom.xml", (common +
              '  <parent><groupId>org.apache.maven.suggestions.fixture</groupId>\n'
              '    <artifactId>unavailable-parent</artifactId><version>0-never-published</version>\n'
              '    <relativePath/></parent><artifactId>missing-parent</artifactId>\n'
              '  <packaging>pom</packaging>\n</project>\n').encode("utf-8"))
    return {"fixture": fixture, "ordinary": ordinary, "missing-parent": parent_failure,
            "user-settings": user_configuration / "settings.xml",
            "user-extensions": user_configuration / "extensions.xml",
            "forbidden-cache": fixture / "must-not-initialize-cache"}


def check_completion_inputs(fx: dict[str, Path], before: dict[str, str]) -> list[str]:
    # Maven may create its ordinary build outputs even for validate (including
    # .mvn/target/project-local-repo). Those are not completion-pass source edits.
    paths = list(fx["fixture"].rglob("*"))
    if any(path.is_symlink() for path in paths):
        raise AssertionError("Completion fixture contains an unexpected symbolic link")
    after = {str(path.relative_to(fx["fixture"])): file_sha256(path)
             for path in paths if path.is_file()}
    if any(after.get(name) != digest for name, digest in before.items()) or fx["forbidden-cache"].exists():
        raise AssertionError("Completion pass changed fixture inputs or initialized an inert provider cache")
    generated = sorted(set(after).difference(before))
    build_outputs = tuple(project + prefix for project in ("ordinary", "missing-parent")
                          for prefix in ("/.mvn/target/", "/target/"))
    if any(not path.startswith(build_outputs) for path in generated):
        raise AssertionError("Completion fixture created a file outside Maven build-output directories")
    return generated


def completion_acceptance(maven: str, repository: Path, work: Path,
                          distribution: dict) -> dict:
    logs = work / "logs"
    logs.mkdir()
    fx = completion_fixture(work)
    before = {str(p.relative_to(fx["fixture"])): file_sha256(p)
              for p in fx["fixture"].rglob("*") if p.is_file()}
    # User-installed extensions/settings are outside this distribution fixture.
    # Preserve installation configuration, but keep these offline cases independent
    # of the invoking account's Mimir extension and repository configuration.
    base = [maven, "-B", "-ntp", "-o", "-Dstyle.color=never", f"-Dmaven.repo.local={repository}",
            "-s", str(fx["user-settings"]), f"-Dmaven.user.extensions={fx['user-extensions']}"]
    version_log = logs / "00-maven-version.log"
    if run_logged(base + ["--version"], fx["ordinary"], version_log, timeout=30) != 0:
        raise AssertionError("Packaged Maven launcher did not start")
    if "Apache Maven" not in version_log.read_text(encoding="utf-8", errors="replace"):
        raise AssertionError("Packaged Maven version identity is missing")
    malformed = ["-Dm3.smart.adviserJar=not-an-absolute-jar", "-Dm3.smart.chromeLlmJarSha256=invalid",
                 f"-Dm3.smart.modelCacheDir={fx['forbidden-cache']}", "-Dm3.smart.llmAllowDownloads=true"]
    both = ["-Dm3.smart.suggest=true", "-Doptionllm=true"]
    failure = "m3-advice-not-a-lifecycle-phase"
    marker = "[M3] Build suggestions"
    unavailable = "suggestions unavailable; Maven's result is unchanged"
    llm = "[UNTRUSTED LLM]"
    cases = [
        ("01-default-success", "ordinary", ["validate"], 0, [], [marker, llm]),
        ("02-optionllm-success", "ordinary", ["-Doptionllm=true"] + malformed + ["validate"], 0, [], [marker, llm]),
        ("03-default-failure", "ordinary", [failure], 1, ["Unknown lifecycle phase"], [marker, llm]),
        ("04-optionllm-failure", "ordinary", ["-Doptionllm=true"] + malformed + [failure], 1, ["Unknown lifecycle phase"], [marker, llm]),
        ("05-deterministic-failure", "ordinary", ["-Dm3.smart.suggest=true", failure], 1,
         [marker, "requested lifecycle phase"], [llm, unavailable]),
        ("06-success-inert-provider", "ordinary", both + malformed + ["validate"], 0,
         ["No completed build diagnostics require suggestions"], [llm, unavailable]),
        ("07-unavailable-provider", "ordinary", both + malformed + [failure], 1,
         ["requested lifecycle phase", unavailable], [llm]),
        ("08-fail-never-baseline", "ordinary", ["--fail-never", failure], 0,
         ["Build failures were ignored"], [marker, llm]),
        ("09-fail-never-advice", "ordinary", ["--fail-never"] + both + malformed + [failure], 0,
         ["Build failures were ignored", unavailable], [llm]),
        ("10-nested-model-baseline", "missing-parent", ["validate"], 1, [], [marker, llm]),
        ("11-nested-model-advice", "missing-parent", ["-Dm3.smart.suggest=true", "validate"], 1,
         ["reported POM, parent and reactor", "dependency coordinates"], [llm]),
    ]
    results = []
    for label, project, options, expected, required, forbidden in cases:
        log = logs / (label + ".log")
        code = run_logged(base + ["-f", str(fx[project] / "pom.xml")] + options,
                          fx[project], log, timeout=90)
        output = log.read_text(encoding="utf-8", errors="replace")
        if code != expected or any(value not in output for value in required) or any(value in output for value in forbidden):
            raise AssertionError(f"Packaged completion contract failed: {label}; exit={code}; see {log}")
        generated = check_completion_inputs(fx, before)
        results.append({"case": label, "exitCode": code, "expectedExitCode": expected,
                        "logSha256": file_sha256(log), "mavenBuildOutputs": generated})
    if completion_distribution(Path(maven)) != distribution:
        raise AssertionError("Packaged runtime inputs changed during acceptance")
    return {"schema": "m3.smart.packaged-completion.acceptance.v1", "status": "PASS",
            "distribution": distribution, "versionLogSha256": file_sha256(version_log),
            "cases": results, "realMavenInvocations": len(results), "fixtureInputs": before,
            "nativeInferenceQualified": False, "offline": True}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn", help="Existing Maven executable name/path")
    parser.add_argument("--local-repo", help="Pre-existing approved absolute Maven local repository")
    parser.add_argument("--prepare-only", action="store_true", help="Fixture check only; no Maven qualification")
    parser.add_argument("--core-completion", action="store_true", help="Qualify the packaged Maven completion pass")
    parser.add_argument("--work", type=Path, help="New absolute output directory for --core-completion")
    args = parser.parse_args(argv)
    plugin = Path(__file__).resolve().parent.parent
    if args.core_completion:
        if args.prepare_only or not args.local_repo:
            raise ValueError("Core completion requires real packaged Maven and --local-repo; prepare-only is not a qualification")
        maven = shutil.which(args.maven)
        if maven is None:
            raise ValueError("Packaged Maven executable is required")
        distribution = completion_distribution(Path(maven).absolute())
        repository = approve_repository(args.local_repo, plugin)
        work = args.work or plugin / "target/m3-completion-acceptance"
        if not work.is_absolute() or work.exists() or work.is_symlink() or work.parent.resolve() != work.parent:
            raise ValueError("Core acceptance output must be a new absolute path without symlink parents")
        work.mkdir(mode=0o700)
        receipt = completion_acceptance(str(Path(maven).absolute()), repository, work, distribution)
        write_new(work / "acceptance.json", (json.dumps(receipt, sort_keys=True, indent=2) + "\n").encode("utf-8"))
        print("PASS: packaged Maven completion pass; 11 actual invocations; receipt in", work)
        return 0
    if args.work is not None:
        raise ValueError("--work is only supported with --core-completion")
    target = plugin / "target"
    if target.is_symlink() or (target.exists() and not target.is_dir()):
        raise ValueError("Maven target must be an ordinary directory")
    if not target.exists():
        target.mkdir()
    work = target / "m3-runtime-acceptance"
    if work.exists() or work.is_symlink():
        raise ValueError("Existing runtime acceptance directory; refusing overwrite or hidden replay")
    # Before any writes, require executable and repository for the real runtime lane.
    maven = shutil.which(args.maven)
    if not args.prepare_only and (maven is None or not args.local_repo):
        raise ValueError("Real Maven runtime and approved --local-repo are mandatory")
    repository = None if args.prepare_only else approve_repository(args.local_repo, plugin)
    work.mkdir(mode=0o700)
    fx = make_fixture(work)
    if args.prepare_only:
        print("PREPARED ONLY: fixture created; no Maven invocation or runtime PASS")
        return 0
    assert maven is not None and repository is not None
    receipt = acceptance(maven, plugin, repository, work, fx)
    write_new(work / "acceptance.json", (json.dumps(receipt, sort_keys=True, indent=2) + "\n").encode())
    print("PASS: real Maven plugin descriptor and eight goal invocations; receipts in", work)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, AssertionError, zipfile.BadZipFile, ET.ParseError) as exc:
        print(f"M3 MAVEN RUNTIME HOLD: {exc.__class__.__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
