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

# SPDX-License-Identifier: Apache-2.0
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

    def test_completion_fixture_preserves_exact_inputs_and_missing_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            a = module.completion_fixture(first)
            b = module.completion_fixture(second)
            for project in ("ordinary", "missing-parent"):
                self.assertEqual((a[project] / "pom.xml").read_bytes(),
                                 (b[project] / "pom.xml").read_bytes())
                self.assertTrue((a[project] / ".mvn").is_dir())
            self.assertIn(b"<relativePath/>", (a["missing-parent"] / "pom.xml").read_bytes())
            self.assertFalse(a["forbidden-cache"].exists())
            with self.assertRaises(FileExistsError):
                module.completion_fixture(first)

    def test_completion_distribution_refuses_missing_and_duplicate_owners(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for folder in ("bin", "conf", "boot", "lib"):
                (root / folder).mkdir()
            launcher = root / "bin/mvn"
            launcher.write_bytes(b"unit fixture only; never executed\n")
            (root / "bin/m2.conf").write_bytes(b"unit fixture\n")
            with zipfile.ZipFile(root / "boot/launcher.jar", "w") as jar:
                jar.writestr("manifest.txt", "unit fixture")
            helper = "org/apache/maven/cling/invoker/mvn/M3BuildSuggestions.class"
            invoker = "org/apache/maven/cling/invoker/mvn/MavenInvoker.class"
            library = root / "lib/maven-cli.jar"
            with zipfile.ZipFile(library, "w") as jar:
                jar.writestr(invoker, b"original invoker fixture")
            with self.assertRaises(AssertionError):
                module.completion_distribution(launcher)
            with zipfile.ZipFile(library, "w") as jar:
                jar.writestr(helper, b"completion fixture")
            with self.assertRaises(AssertionError):
                module.completion_distribution(launcher)
            with zipfile.ZipFile(library, "w") as jar:
                jar.writestr(helper, b"completion fixture")
                jar.writestr(invoker, b"original invoker fixture")
            identity = module.completion_distribution(launcher)
            self.assertEqual(identity["owner"]["classes"][helper], module.sha256(b"completion fixture"))
            self.assertEqual(identity["inputs"]["lib/maven-cli.jar"], module.file_sha256(library))
            (root / "lib/ext").mkdir()
            resource = root / "lib/ext/runtime-resource.txt"
            resource.write_bytes(b"first runtime resource")
            (root / "bin/JvmConfigParser.class").write_bytes(b"parser fixture")
            extended = module.completion_distribution(launcher)
            self.assertIn("bin/JvmConfigParser.class", extended["inputs"])
            self.assertEqual(extended["inputs"]["lib/ext/runtime-resource.txt"], module.file_sha256(resource))
            resource.write_bytes(b"changed runtime resource")
            self.assertNotEqual(extended, module.completion_distribution(launcher))
            linked = root / "conf/linked.properties"
            linked.symlink_to(resource)
            with self.assertRaises(ValueError):
                module.completion_distribution(launcher)
            linked.unlink()
            boot_configuration = root / "conf/maven-boot-repository.properties"
            boot_configuration.write_bytes(b"maven.repo.local.tail = ${maven.home}/repository\n")
            with self.assertRaises(ValueError):
                module.completion_distribution(launcher)
            bundled = root / "repository/io/test/inline-plugin/1/inline-plugin-1.jar"
            bundled.parent.mkdir(parents=True)
            bundled.write_bytes(b"first bundled artifact fixture")
            with_bundled = module.completion_distribution(launcher)
            self.assertEqual(with_bundled["inputs"][str(bundled.relative_to(root))],
                             module.file_sha256(bundled))
            bundled.write_bytes(b"changed bundled artifact fixture")
            self.assertNotEqual(with_bundled, module.completion_distribution(launcher))
            linked_bundle = bundled.parent / "linked.jar"
            linked_bundle.symlink_to(bundled)
            with self.assertRaises(ValueError):
                module.completion_distribution(launcher)
            linked_bundle.unlink()
            (root / "lib/duplicate-owner.jar").write_bytes(library.read_bytes())
            with self.assertRaises(AssertionError):
                module.completion_distribution(launcher)

    def test_completion_accepts_maven_outputs_but_refuses_input_and_provider_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            fx = module.completion_fixture(Path(temporary))
            before = {str(p.relative_to(fx["fixture"])): module.file_sha256(p)
                      for p in fx["fixture"].rglob("*") if p.is_file()}
            output = fx["ordinary"] / ".mvn/target/project-local-repo/consumer.pom"
            output.parent.mkdir(parents=True)
            output.write_bytes(b"ordinary Maven output")
            self.assertEqual(module.check_completion_inputs(fx, before),
                             ["ordinary/.mvn/target/project-local-repo/consumer.pom"])
            pom = fx["ordinary"] / "pom.xml"
            original = pom.read_bytes()
            pom.write_bytes(b"changed source")
            with self.assertRaises(AssertionError):
                module.check_completion_inputs(fx, before)
            pom.write_bytes(original)
            side_effect = fx["ordinary"] / "unexpected.txt"
            side_effect.write_bytes(b"unexpected output outside target")
            with self.assertRaises(AssertionError):
                module.check_completion_inputs(fx, before)
            side_effect.unlink()
            fx["forbidden-cache"].mkdir()
            with self.assertRaises(AssertionError):
                module.check_completion_inputs(fx, before)

    def test_core_mode_cannot_turn_preparation_into_runtime_qualification(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / "must-not-be-created"
            with self.assertRaises(ValueError):
                module.main(["--core-completion", "--prepare-only", "--work", str(work)])
            self.assertFalse(work.exists())
            with self.assertRaises(ValueError):
                module.main(["--work", str(work)])
            self.assertFalse(work.exists())


if __name__ == "__main__":
    unittest.main()
