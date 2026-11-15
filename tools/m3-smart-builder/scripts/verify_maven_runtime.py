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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default="mvn", help="Existing Maven executable name/path")
    parser.add_argument("--local-repo", help="Pre-existing approved absolute Maven local repository")
    parser.add_argument("--prepare-only", action="store_true", help="Fixture check only; no Maven qualification")
    args = parser.parse_args(argv)
    plugin = Path(__file__).resolve().parent.parent
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
