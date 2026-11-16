# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Offline contract checks for Maven runtime qualification orchestration.

These checks never pretend to be Maven: a real mvn install and goal execution
are mandatory for an acceptance PASS.
"""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

SOURCE = Path(__file__).resolve().parents[1] / "verify_maven_runtime.py"
spec = importlib.util.spec_from_file_location("m3_verify_maven_runtime", SOURCE)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class MavenRuntimeAcceptanceContract(unittest.TestCase):
    def test_deterministic_approved_offline_fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            a = module.make_fixture(first)
            b = module.make_fixture(second)
            for key in ("pom", "manifest", "candidate", "artifact", "log"):
                self.assertEqual(a[key].read_bytes(), b[key].read_bytes())
            self.assertEqual(module.sha256(a["artifact"].read_bytes()), a["digest"])
            self.assertEqual(len(list(a["missing"].iterdir())), 0)
            self.assertNotEqual(a["corrupt"].joinpath(a["artifact"].name).read_bytes(),
                                a["artifact"].read_bytes())
            self.assertIn("\ttrue\n", a["manifest"].read_text())

    def test_no_silent_fixture_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module.make_fixture(root)
            with self.assertRaises(FileExistsError):
                module.make_fixture(root)

    def test_plugin_descriptor_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            jar = repo / "io/synexia/m3-smart-maven-plugin/0.1.0-SNAPSHOT/m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar"
            jar.parent.mkdir(parents=True)
            with self.assertRaises(AssertionError):
                module.check_installed_plugin(repo)
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/maven/plugin.xml", "<plugin><goalPrefix>m3</goalPrefix>"
                                 "<mojos><mojo><goal>plan</goal></mojo></mojos></plugin>")
            with self.assertRaises(AssertionError):
                module.check_installed_plugin(repo)
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/maven/plugin.xml", "<plugin><goalPrefix>m3</goalPrefix>"
                                 "<mojos>" + "".join(f"<mojo><goal>{name}</goal></mojo>"
                                 for name in ("plan", "provision", "diagnose")) + "</mojos></plugin>")
            self.assertEqual(module.check_installed_plugin(repo), module.sha256(jar.read_bytes()))

    def test_safe_repository_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.mkdir()
            repo = root / "repository"
            repo.mkdir()
            self.assertEqual(module.approve_repository(str(repo), root), repo)
            with self.assertRaises(ValueError):
                module.approve_repository("relative", root)
            with self.assertRaises(ValueError):
                module.approve_repository(str(target), root)
            with self.assertRaises(ValueError):
                module.approve_repository(str(root / "absent"), root)

    def test_receipts_strict_schema(self):
        with tempfile.TemporaryDirectory() as temporary:
            p = Path(temporary) / "plan.json"
            p.write_text(json.dumps({"schema": "m3.smart.plan.v1", "mode": "review-only"}))
            self.assertEqual(module.assert_receipt(p, "m3.smart.plan.v1")[1]["mode"], "review-only")
            with self.assertRaises(AssertionError):
                module.assert_receipt(p, "m3.smart.provision.v1")


if __name__ == "__main__":
    unittest.main()
