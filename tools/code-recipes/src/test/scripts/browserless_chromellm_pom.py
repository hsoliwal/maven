#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See
# the License for the specific language governing permissions and
# limitations under the License.
"""Check a generated ChromeLLM Maven advice profile without third-party dependencies."""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(element: ET.Element, name: str) -> ET.Element | None:
    return next((item for item in element if local_name(item.tag) == name), None)


def contract(text: str) -> list[str]:
    errors = []
    try:
        document = ET.fromstring(text)
    except ET.ParseError as exc:
        return [f"invalid XML: {exc}"]

    profiles = [
        node
        for node in document.iter()
        if local_name(node.tag) == "profile"
        and (child(node, "id") is not None)
        and child(node, "id").text == "chromellm-advice"
    ]
    if len(profiles) != 1:
        return [f"expected one chromellm-advice profile, found {len(profiles)}"]

    profile = profiles[0]
    properties = child(profile, "properties")
    if properties is None:
        return ["missing advice properties"]
    values = {local_name(node.tag): (node.text or "") for node in properties}
    if values.get("chromellm.backend") != "cli":
        errors.append("default provider must be cli")
    if "chromellm.backend.args" not in values:
        errors.append("missing explicit local backend args property")

    forbidden = ("chromellm.chrome.", "--chrome-profile", "--chrome-headless")
    for node in profile.iter():
        attributes = " ".join(str(item) for item in node.attrib.values())
        represented = f"{local_name(node.tag)} {attributes} {node.text or ''}"
        if any(token in represented for token in forbidden):
            errors.append(f"removed Chrome adapter control remains: {local_name(node.tag)}")

    executions = {
        (child(node, "id").text or ""): node
        for node in profile.iter()
        if local_name(node.tag) == "execution" and child(node, "id") is not None
    }
    expected = {"chromellm-pre-build": "validate", "chromellm-post-build": "verify"}
    if set(executions) != set(expected):
        errors.append(f"advice execution IDs changed: {sorted(executions)}")
    for identifier, phase in expected.items():
        execution = executions.get(identifier)
        if execution is None:
            continue
        actual_phase = child(execution, "phase")
        if actual_phase is None or actual_phase.text != phase:
            errors.append(f"{identifier} lost its {phase} lifecycle phase")
        args = [
            node.attrib.get("value", node.attrib.get("line", ""))
            for node in execution.iter()
            if local_name(node.tag) == "arg"
        ]
        for required in ("--backend", "${chromellm.backend}", "--prompt-file", "${chromellm.backend.args}"):
            if required not in args:
                errors.append(f"{identifier} lost argument {required}")

    if "BEGIN CHROMELLM-BUILD-ADVICE v3" not in text or "END CHROMELLM-BUILD-ADVICE v3" not in text:
        errors.append("advice profile marker is not v3")
    if "CHROMELLM-BUILD-ADVICE v2" in text:
        errors.append("retired v2 marker remains")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("poms", nargs="+", help="POM paths, or - to read XML from stdin")
    arguments = parser.parse_args()
    failed = False
    for name in arguments.poms:
        text = sys.stdin.read() if name == "-" else Path(name).read_text(encoding="utf-8")
        errors = contract(text)
        if errors:
            failed = True
            for error in errors:
                print(f"FAIL {name}: {error}", file=sys.stderr)
        else:
            print(f"PASS {name}: local ChromeLLM advice profile")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
