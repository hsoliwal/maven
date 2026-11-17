#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Execute build-wrapper contracts against recording Maven and Java executables.

Only Python's standard library is needed. The recording native-executable
fixture requires a POSIX host because it uses an executable script with a
shebang; every case is explicitly skipped on other hosts. POSIX-shell cases
need sh, and PowerShell cases need pwsh on that POSIX host. A missing shell
is an explicit skip, never a passing case. These command-boundary checks do
not prove Maven compilation, lifecycle configuration, or native Windows
execution; Windows remains a separate qualification gate.

Example:
  python3 scripts/tests/test_m3_build.py --root . --report target/build-wrapper-tests.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


RECORDING_MAVEN = r'''import json, os, pathlib, sys
record = {
    "argv": sys.argv[1:],
    "cwd": os.getcwd(),
    "executable": str(pathlib.Path(sys.argv[0]).resolve()),
}
with open(os.environ["M3_BUILD_TEST_CAPTURE"], "a", encoding="utf-8") as stream:
    stream.write(json.dumps(record, ensure_ascii=False) + "\n")
print("recording Maven stdout")
print("recording Maven stderr", file=sys.stderr)
sys.exit(int(os.environ.get("M3_BUILD_TEST_EXIT", "0")))
'''

POWERSHELL_DRIVER = r'''
$ErrorActionPreference = 'Stop'
$global:LASTEXITCODE = 0
if ($env:M3_BUILD_TEST_NATIVE_ERRORS -eq 'true') {
    $PSNativeCommandUseErrorActionPreference = $true
}
$data = $env:M3_BUILD_TEST_PARAMETERS | ConvertFrom-Json
$parameters = @{}
foreach ($property in $data.PSObject.Properties) {
    $parameters[$property.Name] = $property.Value
}
$before = (Get-Location).Path
$positionalArguments = @($env:M3_BUILD_TEST_POSITIONAL | ConvertFrom-Json)
& $env:M3_BUILD_TEST_SCRIPT @parameters @positionalArguments
$result = $LASTEXITCODE
@{
    before = $before
    after = (Get-Location).Path
    nativeErrorPreference = $PSNativeCommandUseErrorActionPreference
} | ConvertTo-Json -Compress | Set-Content -LiteralPath $env:M3_BUILD_TEST_CALLER
exit $result
'''


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@unittest.skipUnless(os.name == "posix", "The recording native-executable fixture requires a POSIX host; native Windows qualification is separate")
class BuildWrapperContracts(unittest.TestCase):
    root: Path
    sh: str | None
    powershell: str | None
    observations: list[dict] = []

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="m3-build-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.project = self.base / "project with spaces"
        self.caller = self.base / "caller with spaces"
        self.bin = self.base / "bin"
        self.custom = self.base / "custom tools [literal]"
        for directory in (self.project, self.caller, self.bin, self.custom):
            directory.mkdir()
        for filename in ("m3-build.sh", "m3-build.ps1"):
            shutil.copyfile(self.root / filename, self.project / filename)
        self.capture = self.base / "maven-calls.jsonl"
        self.caller_record = self.base / "caller.json"
        self.default_maven = self.make_maven(self.bin / "mvn")
        self.named_maven = self.make_maven(self.bin / "selected-maven")
        self.custom_maven = self.make_maven(self.custom / "maven ; runner")
        self.env = os.environ.copy()
        for variable in ("M3_MAVEN", "M3_JAVA"):
            self.env.pop(variable, None)
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env.get("PATH", "")
        self.env["M3_BUILD_TEST_CAPTURE"] = str(self.capture)
        self.env["M3_BUILD_TEST_CALLER"] = str(self.caller_record)
        self.env["M3_BUILD_TEST_EXIT"] = "0"
        self.env["M3_BUILD_TEST_NATIVE_ERRORS"] = "false"

    def make_maven(self, target: Path) -> Path:
        # The native command is deliberately distinct from the wrapper under test.
        # Use a fixed interpreter path so PATH selection cannot change the oracle.
        target.write_text("#!" + sys.executable + "\n" + RECORDING_MAVEN,
                          encoding="utf-8", newline="\n")
        target.chmod(0o755)
        return target

    def run_wrapper(self, shell: str, arguments=None, parameters=None,
                    environment=None, exit_code=0, native_errors=False, positional=None):
        env = self.env.copy()
        env.update(environment or {})
        env["M3_BUILD_TEST_EXIT"] = str(exit_code)
        env["M3_BUILD_TEST_NATIVE_ERRORS"] = str(native_errors).lower()
        if shell == "posix":
            if not self.sh:
                self.skipTest("POSIX sh is unavailable")
            command = [self.sh, str(self.project / "m3-build.sh"), *(arguments or [])]
        else:
            if not self.powershell:
                self.skipTest("PowerShell runtime is unavailable")
            env["M3_BUILD_TEST_PARAMETERS"] = json.dumps(parameters or {})
            env["M3_BUILD_TEST_POSITIONAL"] = json.dumps(positional or [])
            env["M3_BUILD_TEST_SCRIPT"] = str(self.project / "m3-build.ps1")
            command = [self.powershell, "-NoLogo", "-NoProfile", "-NonInteractive",
                       "-Command", POWERSHELL_DRIVER]
        completed = subprocess.run(command, cwd=self.caller, env=env,
                                   text=True, capture_output=True, timeout=20)
        calls = ([json.loads(line) for line in self.capture.read_text(encoding="utf-8").splitlines()]
                 if self.capture.exists() else [])
        caller = (json.loads(self.caller_record.read_text(encoding="utf-8-sig"))
                  if self.caller_record.exists() else None)
        self.observations.append({
            "case": self.id().rsplit(".", 1)[-1],
            "shell": shell,
            "exit": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "calls": calls,
            "caller": caller,
        })
        return completed, calls, caller

    def assert_call(self, result, expected_args, expected_executable=None, exit_code=0):
        completed, calls, caller = result
        self.assertEqual(exit_code, completed.returncode, completed.stderr)
        self.assertEqual(1, len(calls), completed.stdout + completed.stderr)
        self.assertEqual(expected_args, calls[0]["argv"])
        self.assertEqual(str(self.project.resolve()), calls[0]["cwd"])
        self.assertEqual(str((expected_executable or self.default_maven).resolve()),
                         calls[0]["executable"])
        self.assertIn("recording Maven stdout", completed.stdout)
        self.assertIn("recording Maven stderr", completed.stderr)
        if caller is not None:
            self.assertEqual(str(self.caller), caller["before"])
            self.assertEqual(caller["before"], caller["after"])

    def assert_refusal(self, result, exit_code=None):
        completed, calls, _ = result
        self.assertNotEqual(0, completed.returncode)
        if exit_code is not None:
            self.assertEqual(exit_code, completed.returncode)
        self.assertEqual([], calls, "Refused input must not invoke Maven")


def add_case(name, function):
    setattr(BuildWrapperContracts, "test_" + name, function)


MODES = {
    "quick": ["package", "-DskipTests"],
    "test": ["test"],
    "verify": ["verify"],
    "full": ["clean", "verify"],
    "it": ["verify", "-Prun-its"],
}

for shell_name in ("posix", "powershell"):
    def default_case(self, shell=shell_name):
        self.assert_call(self.run_wrapper(shell), ["-B", "-ntp", "verify"])
    add_case(shell_name + "_default_keeps_native_builder_and_tests", default_case)

    def literal_root_case(self, shell=shell_name):
        target = self.base / "project with spaces [literal];root"
        self.project.rename(target)
        self.project = target
        self.assert_call(self.run_wrapper(shell), ["-B", "-ntp", "verify"])
    add_case(shell_name + "_literal_project_root_with_metacharacters", literal_root_case)

    for mode_name, goals_value in MODES.items():
        def mode_case(self, shell=shell_name, mode=mode_name, goals=goals_value):
            result = self.run_wrapper(shell, arguments=[mode], parameters={"Mode": mode})
            self.assert_call(result, ["-B", "-ntp", *goals])
        add_case(shell_name + "_mode_" + mode_name, mode_case)

    def exit_case(self, shell=shell_name):
        self.assert_call(self.run_wrapper(shell, exit_code=37), ["-B", "-ntp", "verify"],
                         exit_code=37)
    add_case(shell_name + "_propagates_native_exit_37", exit_case)

    def selected_path_case(self, shell=shell_name):
        result = self.run_wrapper(shell, environment={"M3_MAVEN": str(self.custom_maven)})
        self.assert_call(result, ["-B", "-ntp", "verify"], self.custom_maven)
    add_case(shell_name + "_literal_executable_path_with_spaces_and_metacharacters", selected_path_case)

    def relative_path_case(self, shell=shell_name):
        tools = self.project / "nearby tools [literal]"
        tools.mkdir()
        executable = self.make_maven(tools / "maven ; runner")
        relative = "." + os.sep + os.path.relpath(executable, self.project)
        result = self.run_wrapper(shell, environment={"M3_MAVEN": relative})
        self.assert_call(result, ["-B", "-ntp", "verify"], executable)
    add_case(shell_name + "_relative_executable_resolves_from_project_root", relative_path_case)

    def selected_name_case(self, shell=shell_name):
        result = self.run_wrapper(shell, environment={"M3_MAVEN": "selected-maven"})
        self.assert_call(result, ["-B", "-ntp", "verify"], self.named_maven)
    add_case(shell_name + "_executable_name_uses_path", selected_name_case)

    def empty_env_case(self, shell=shell_name):
        self.assert_call(self.run_wrapper(shell, environment={"M3_MAVEN": ""}),
                         ["-B", "-ntp", "verify"])
    add_case(shell_name + "_empty_environment_uses_default_maven", empty_env_case)

    def missing_executable_case(self, shell=shell_name):
        result = self.run_wrapper(shell, environment={"M3_MAVEN": str(self.base / "missing")})
        self.assert_refusal(result, 127 if shell == "posix" else None)
    add_case(shell_name + "_missing_selected_executable_refuses", missing_executable_case)

    def command_text_case(self, shell=shell_name):
        command_text = str(self.custom_maven) + " -Dembedded=true"
        self.assert_refusal(self.run_wrapper(shell, environment={"M3_MAVEN": command_text}))
    add_case(shell_name + "_executable_is_not_shell_command_text", command_text_case)

    def invalid_mode_case(self, shell=shell_name):
        result = self.run_wrapper(shell, arguments=["unknown-mode"],
                                  parameters={"Mode": "unknown-mode"},
                                  environment={"M3_MAVEN": str(self.base / "missing")})
        self.assert_refusal(result, 2 if shell == "posix" else None)
    add_case(shell_name + "_invalid_mode_refuses_before_maven", invalid_mode_case)

    def literal_args_case(self, shell=shell_name):
        sentinel = self.base / "should-not-exist"
        args = ["-Dspaces=two words", "-Dsemi=a;b", '-Dquote=a"b',
                "-Dbackslash=C:\\some dir\\", "-Dunicode=தமிழ்", "",
                "-Dliteral=$(touch '" + str(sentinel) + "')", "--", "-X"]
        result = self.run_wrapper(shell, arguments=["verify", *args],
                                  parameters={"Mode": "verify", "ExtraArgs": args})
        self.assert_call(result, ["-B", "-ntp", "verify", *args])
        self.assertFalse(sentinel.exists())
    add_case(shell_name + "_preserves_exact_argument_boundaries", literal_args_case)

    def resume_case(self, shell=shell_name):
        result = self.run_wrapper(shell, arguments=["resume", ":maven-core", "-DskipTests=false"],
                                  parameters={"Mode": "resume", "ResumeFrom": ":maven-core",
                                              "ExtraArgs": ["-DskipTests=false"]})
        self.assert_call(result, ["-B", "-ntp", "verify", "-rf", ":maven-core", "-DskipTests=false"])
    add_case(shell_name + "_resume_uses_native_reactor_and_forwards_extras", resume_case)

    for kind, selector in (("missing", None), ("empty", ""), ("whitespace", " \t ")):
        def bad_resume_case(self, shell=shell_name, value=selector):
            args = ["resume"] + ([] if value is None else [value])
            params = {"Mode": "resume"}
            if value is not None:
                params["ResumeFrom"] = value
            result = self.run_wrapper(shell, arguments=args, parameters=params)
            self.assert_refusal(result, 2 if shell == "posix" else None)
        add_case(shell_name + "_resume_refuses_" + kind + "_selector", bad_resume_case)

    def option_resume_case(self, shell=shell_name):
        result = self.run_wrapper(shell, arguments=["resume", "-pl"],
                                  parameters={"Mode": "resume", "ResumeFrom": "-pl"})
        self.assert_refusal(result, 2 if shell == "posix" else None)
    add_case(shell_name + "_resume_refuses_option_looking_selector", option_resume_case)


for help_arg in ("help", "-h", "--help"):
    def help_case(self, arg=help_arg):
        result = self.run_wrapper("posix", arguments=[arg],
                                  environment={"M3_MAVEN": str(self.base / "missing")})
        completed, calls, _ = result
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual([], calls)
        self.assertIn("usage", (completed.stdout + completed.stderr).lower())
    add_case("posix_help_" + help_arg.replace("-", "dash_"), help_case)

for help_kind, parameters in (("mode", {"Mode": "help"}), ("switch", {"Help": True})):
    def ps_help_case(self, params=parameters):
        result = self.run_wrapper("powershell", parameters=params,
                                  environment={"M3_MAVEN": str(self.base / "missing")})
        completed, calls, _ = result
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual([], calls)
        self.assertIn("usage", (completed.stdout + completed.stderr).lower())
    add_case("powershell_help_" + help_kind, ps_help_case)


def posix_native_options(self):
    args = ["-pl", "api/module,impl", "-am", "-T", "2", "-Pcustom", "-DskipTests=false"]
    self.assert_call(self.run_wrapper("posix", arguments=["verify", *args]),
                     ["-B", "-ntp", "verify", *args])
add_case("posix_forwards_native_selection_and_threads", posix_native_options)


def posix_options_only(self):
    args = ["-pl", "impl/maven-core", "-am", "-Dstyle.color=never"]
    self.assert_call(self.run_wrapper("posix", arguments=args), ["-B", "-ntp", "verify", *args])
add_case("posix_options_only_preserves_default_verify", posix_options_only)


def posix_relative_path_entry(self):
    tools = self.project / "relative bin [literal]"
    tools.mkdir()
    executable = self.make_maven(tools / "mvn")
    relative = "." + os.sep + os.path.relpath(tools, self.project)
    result = self.run_wrapper("posix", environment={"PATH": relative + os.pathsep + self.env["PATH"]})
    self.assert_call(result, ["-B", "-ntp", "verify"], executable)
add_case("posix_relative_path_entry_resolves_from_project_root", posix_relative_path_entry)


def ps_positional_mode(self):
    self.assert_call(self.run_wrapper("powershell", positional=["test"]), ["-B", "-ntp", "test"])
add_case("powershell_mode_retains_positional_binding", ps_positional_mode)


def ps_module(self):
    self.assert_call(self.run_wrapper("powershell", parameters={"Module": "api/module,impl"}),
                     ["-B", "-ntp", "-pl", "api/module,impl", "-am", "verify"])
add_case("powershell_module_adds_native_prerequisites", ps_module)


def ps_empty_module(self):
    self.assert_call(self.run_wrapper("powershell", parameters={"Module": ""}),
                     ["-B", "-ntp", "verify"])
add_case("powershell_empty_module_preserves_no_selection", ps_empty_module)


def ps_whitespace_module(self):
    self.assert_refusal(self.run_wrapper("powershell", parameters={"Module": " \t "}))
add_case("powershell_whitespace_module_refuses", ps_whitespace_module)


for count in (1, 2):
    def ps_threads(self, threads=count):
        self.assert_call(self.run_wrapper("powershell", parameters={"Threads": threads}),
                         ["-B", "-ntp", "-T", str(threads), "verify"])
    add_case("powershell_explicit_threads_" + str(count), ps_threads)

for count in (0, -1):
    def ps_invalid_threads(self, threads=count):
        self.assert_refusal(self.run_wrapper("powershell", parameters={"Threads": threads}))
    add_case("powershell_refuses_threads_" + str(count).replace("-", "negative_"), ps_invalid_threads)


def ps_resume_wrong_mode(self):
    self.assert_refusal(self.run_wrapper("powershell", parameters={"ResumeFrom": ":maven-core"}))
add_case("powershell_resume_selector_requires_resume_mode", ps_resume_wrong_mode)


def ps_resume_module_conflict(self):
    result = self.run_wrapper("powershell", parameters={"Mode": "resume", "ResumeFrom": ":maven-core",
                                                       "Module": "impl/maven-core"})
    self.assert_refusal(result)
add_case("powershell_resume_and_module_selection_refuse_combination", ps_resume_module_conflict)


def ps_command_override(self):
    result = self.run_wrapper("powershell", parameters={"Maven": str(self.custom_maven)},
                              environment={"M3_MAVEN": str(self.base / "missing")})
    self.assert_call(result, ["-B", "-ntp", "verify"], self.custom_maven)
add_case("powershell_command_parameter_overrides_environment", ps_command_override)


for selection_source in ("parameter", "environment"):
    def ps_missing_wildcard_selector(self, source=selection_source):
        self.make_maven(self.bin / "selected-maven-one")
        selector = str(self.bin / "selected-maven-*")
        self.assertFalse(Path(selector).exists())
        parameters = {"Maven": selector} if source == "parameter" else {}
        environment = {"M3_MAVEN": selector} if source == "environment" else {}
        self.assert_refusal(self.run_wrapper("powershell", parameters=parameters,
                                             environment=environment))
    add_case("powershell_missing_wildcard_selector_refuses_" + selection_source,
             ps_missing_wildcard_selector)

    def ps_missing_bare_wildcard_selector(self, source=selection_source):
        self.make_maven(self.bin / "selected-maven-one")
        selector = "selected-maven-*"
        self.assertFalse((self.bin / selector).exists())
        parameters = {"Maven": selector} if source == "parameter" else {}
        environment = {"M3_MAVEN": selector} if source == "environment" else {}
        self.assert_refusal(self.run_wrapper("powershell", parameters=parameters,
                                             environment=environment))
    add_case("powershell_missing_bare_wildcard_selector_refuses_" + selection_source,
             ps_missing_bare_wildcard_selector)


for selection_source in ("parameter", "environment"):
    def ps_literal_unclosed_bracket_name(self, source=selection_source):
        alternate = self.base / "alternate bin"
        alternate.mkdir()
        first = self.make_maven(self.bin / "[")
        second = self.make_maven(alternate / "[")
        # Exercise both PATH orders with the two public executable selectors.
        directories = (self.bin, alternate) if source == "parameter" else (alternate, self.bin)
        expected = first if source == "parameter" else second
        parameters = {"Maven": "["} if source == "parameter" else {}
        environment = {"PATH": os.pathsep.join(str(path) for path in directories)
                       + os.pathsep + self.env["PATH"]}
        if source == "environment":
            environment["M3_MAVEN"] = "["
        self.assert_call(self.run_wrapper("powershell", parameters=parameters,
                                          environment=environment),
                         ["-B", "-ntp", "verify"], expected)
    add_case("powershell_literal_unclosed_bracket_name_preserves_path_order_" + selection_source,
             ps_literal_unclosed_bracket_name)


for literal_name, case_name in (("maven[1]", "balanced_brackets"),
                                ("maven*?", "star_and_question_mark"),
                                ("maven`name", "backtick")):
    def ps_literal_special_name(self, name=literal_name):
        selected = self.make_maven(self.bin / name)
        self.assert_call(self.run_wrapper("powershell", parameters={"Maven": name}),
                         ["-B", "-ntp", "verify"], selected)
    add_case("powershell_literal_bare_name_with_" + case_name, ps_literal_special_name)


def ps_inherited_native_errors(self):
    result = self.run_wrapper("powershell", exit_code=37, native_errors=True)
    self.assert_call(result, ["-B", "-ntp", "verify"], exit_code=37)
    self.assertIsNotNone(result[2], "Caller must continue after the invoked script")
    self.assertIs(True, result[2]["nativeErrorPreference"], "Caller preference must remain intact")
add_case("powershell_preserves_exit_with_caller_native_exceptions", ps_inherited_native_errors)


def readme_build_guide_link(self):
    text = (self.root / "README.md").read_text(encoding="utf-8")
    self.assertIn("[M3 build commands](docs/M3-BUILD.md)", text)
add_case("readme_retains_build_guide_link", readme_build_guide_link)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path,
                        default=Path(os.environ.get("M3_BUILD_TEST_ROOT", Path(__file__).resolve().parents[2])))
    parser.add_argument("--powershell", default=shutil.which("pwsh"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--case", action="append", help="Exact test method name; repeatable")
    options = parser.parse_args()
    BuildWrapperContracts.root = options.root.resolve()
    BuildWrapperContracts.sh = shutil.which("sh")
    BuildWrapperContracts.powershell = options.powershell
    harness_sha256 = digest(Path(__file__))
    for filename in ("m3-build.sh", "m3-build.ps1", "README.md"):
        if not (BuildWrapperContracts.root / filename).is_file():
            parser.error("missing wrapper: " + str(BuildWrapperContracts.root / filename))
    sources_before = {name: digest(BuildWrapperContracts.root / name)
                      for name in ("m3-build.sh", "m3-build.ps1", "README.md")}
    if options.case:
        suite = unittest.TestSuite(BuildWrapperContracts(name) for name in options.case)
    else:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(BuildWrapperContracts)
    started = time.monotonic()
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sources_after = {name: digest(BuildWrapperContracts.root / name) for name in sources_before}
    source_stable = sources_before == sources_after
    receipt = {
        "schema": "M3_BUILD_WRAPPER_COMMAND_CONTRACT_V1",
        "status": "PASS" if result.wasSuccessful() and not result.skipped and source_stable else "NOT_PASS",
        "claim_boundary": "Native POSIX and PowerShell command-boundary fixtures; no Maven reactor/compiler/Windows claim",
        "root": str(BuildWrapperContracts.root),
        "platform": platform.platform(),
        "python": sys.version,
        "shells": {"posix": BuildWrapperContracts.sh, "powershell": BuildWrapperContracts.powershell},
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "tests": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "source_sha256": sources_before,
        "harness_sha256": harness_sha256,
        "source_unchanged": source_stable,
        "failure_details": [{"case": str(test), "traceback": trace} for test, trace in result.failures + result.errors],
        "skip_details": [{"case": str(test), "reason": reason} for test, reason in result.skipped],
        "observations": BuildWrapperContracts.observations,
    }
    if options.report:
        options.report.parent.mkdir(parents=True, exist_ok=True)
        options.report.write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: receipt[key] for key in ("status", "tests", "failures", "errors", "skipped", "source_unchanged")}))
    return 0 if result.wasSuccessful() and source_stable else 1


if __name__ == "__main__":
    sys.exit(main())
