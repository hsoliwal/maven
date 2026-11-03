#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""Exercise this fork's native M3AST option with six real Maven compilations.

Supply a Maven 4.1.0-SNAPSHOT distribution containing this fork's changes, an
M3JDK compiler image, a stock Java 21 JDK, and a Maven repository containing
maven-compiler-plugin 3.16.0 and its dependencies. All Maven calls are offline.
The verifier builds its own annotation processor and adds only its digest-named
fixture coordinate to the supplied repository. Use a fresh --out path without
whitespace for each run; javac's plugin argument syntax requires those paths.

Example (replace the paths with your build outputs):
  python3 m3/verify_ast_consumer.py --maven-home /build/apache-maven \
      --jdk-home /build/m3jdk --stock-jdk /opt/jdk-21 \
      --maven-repository /cache/maven --out /proof/maven-native

The receipt establishes runtime behavior and hashes the supplied artifacts.
Source-build and full-VM provenance must be supplied separately; a version
banner or a compiler integration image cannot establish either provenance.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from xml.sax.saxutils import escape


SOURCE = '''package fixture;
public final class Main {
    public static final int VALUE = 21;
    public static void main(String[] args) {
        System.out.println("M3_MAVEN_NATIVE_PASS:" + Generated.value());
    }
}
'''

PROCESSOR = '''package processor;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
@SupportedAnnotationTypes("*")
@SupportedOptions("fixture.marker")
public final class FixtureProcessor extends AbstractProcessor {
    private boolean generated;
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!generated && !round.processingOver()) {
            for (Element element : round.getRootElements()) {
                if (element.toString().equals("fixture.Main")) {
                    generated = true;
                    if (!"retained".equals(processingEnv.getOptions().get("fixture.marker")))
                        throw new AssertionError("Existing processor argument was lost");
                    try {
                        try (Writer writer = processingEnv.getFiler()
                                .createSourceFile("fixture.Generated", element).openWriter()) {
                            writer.write("package fixture; public final class Generated { public static int value() { return 21; } }");
                        }
                        try (Writer writer = processingEnv.getFiler()
                                .createResource(StandardLocation.CLASS_OUTPUT, "", "fixture-processor.txt", element).openWriter()) {
                            writer.write("PROCESSOR_PATH_AND_OPTION_RETAINED");
                        }
                    } catch (IOException failure) { throw new RuntimeException(failure); }
                    break;
                }
            }
        }
        return false;
    }
}
'''

COMPILER_PLUGIN = "org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def run(command, jdk, log, success=True):
    environment = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                 "MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_PROJECTBASEDIR", "M2_HOME"):
        environment.pop(name, None)
    environment["JAVA_HOME"] = str(jdk)
    environment["MAVEN_SKIP_RC"] = "1"
    result = subprocess.run([str(arg) for arg in command], cwd=log.parent,
                            env=environment, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=180)
    log.write_text(result.stdout, encoding="utf-8")
    if (result.returncode == 0) != success:
        raise RuntimeError("Unexpected compiler result; see " + str(log))
    return result.stdout


def make_processor(output, stock):
    source = output / "processor-src/processor/FixtureProcessor.java"
    source.parent.mkdir(parents=True)
    source.write_text(PROCESSOR, encoding="utf-8")
    classes = output / "processor-classes"
    classes.mkdir()
    run([stock / "bin/javac", "--release", "21", "-proc:none", "-g:none",
         "-d", classes, source], stock, output / "processor-compile.log")
    entries = {entry.relative_to(classes).as_posix(): entry.read_bytes()
               for entry in classes.rglob("*.class")}
    entries["META-INF/services/javax.annotation.processing.Processor"] = b"processor.FixtureProcessor\n"
    archive = output / "processor.jar"
    # ZIP entry order, timestamps, platform, mode and compression are fixed.
    # Repeated verification against the same compiler produces identical bytes.
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_STORED) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            jar.writestr(info, data)
    return archive


def write_matching(path, content):
    if path.is_symlink():
        raise ValueError("Fixture artifact must not be a symbolic link: " + str(path))
    try:
        with path.open("xb") as stream:
            stream.write(content)
    except FileExistsError:
        if path.read_bytes() != content:
            raise ValueError("Fixture artifact drift: " + str(path)) from None


def install_processor(repository, processor):
    # A different compiler may emit different bytes. Its full digest selects a
    # distinct coordinate, and an existing artifact at that coordinate is never
    # overwritten. This fixture has no published dependencies or metadata.
    version = "1-" + digest(processor)
    base = repository / "org/m3/fixture/native-consumer-processor" / version
    base.mkdir(parents=True, exist_ok=True)
    name = "native-consumer-processor-" + version
    write_matching(base / (name + ".jar"), processor.read_bytes())
    pom = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
           '<groupId>org.m3.fixture</groupId><artifactId>native-consumer-processor</artifactId>'
           '<version>' + version + '</version></project>\n')
    write_matching(base / (name + ".pom"), pom.encode("utf-8"))
    return version


def write_project(project, processor_version, argument, broken):
    project.mkdir()
    source = project / "src/main/java/fixture/Main.java"
    source.parent.mkdir(parents=True)
    source.write_text(SOURCE.replace("Generated.value()", "Unknown.value()") if broken else SOURCE,
                      encoding="utf-8")
    extra = "<arg>" + escape(argument) + "</arg>" if argument else ""
    (project / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        '<groupId>org.m3.fixture</groupId><artifactId>native-compiler</artifactId><version>1</version>'
        '<properties><maven.compiler.release>21</maven.compiler.release></properties>'
        '<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>'
        '<artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version>'
        '<configuration><annotationProcessorPaths><path><groupId>org.m3.fixture</groupId>'
        '<artifactId>native-consumer-processor</artifactId><version>' + processor_version + '</version></path>'
        '</annotationProcessorPaths><compilerArgs><arg>-Afixture.marker=retained</arg>'
        '<arg>-parameters</arg>' + extra + '</compilerArgs></configuration>'
        '</plugin></plugins></build></project>\n', encoding="utf-8")


def check_classes(project, compiler):
    classes = project / "target/classes"
    behavior = run([compiler / "bin/java", "-cp", classes, "fixture.Main"],
                   compiler, project / "run.log")
    if behavior.strip() != "M3_MAVEN_NATIVE_PASS:21":
        raise ValueError("Native Maven compiled behavior changed")
    if (classes / "fixture-processor.txt").read_text(encoding="utf-8") != "PROCESSOR_PATH_AND_OPTION_RETAINED":
        raise ValueError("Native Maven lost the processor path or existing processor argument")
    return {entry.relative_to(classes).as_posix(): digest(entry)
            for entry in sorted(classes.rglob("*.class"))}


def check_capture(trace, image, slots, broken):
    content = trace.read_text(encoding="utf-8")
    lines = content.splitlines()
    if not lines or not lines[0].startswith("# M3AST-1 actual javac events"):
        raise ValueError("Missing actual compiler trace")
    stats = dict(re.findall(r"(\w+)=(\S+)", lines[-1]))
    if int(stats.get("parsed", "0")) < 2 or stats.get("extraSourceReads") != "0":
        raise ValueError("Expected original and processor-generated units without rereads")
    if stats.get("completed") != "true" or stats.get("consumerFailed") != "false":
        raise ValueError("The compiler capture did not complete")
    if "fixture.Generated" not in content:
        raise ValueError("Compiler capture did not include annotation-processor output")
    row = {"trace_stats": stats, "trace_sha256": digest(trace)}
    if broken:
        if int(stats.get("errors", "0")) < 1 or image.exists() or slots.exists():
            raise ValueError("Compiler errors must prevent primitive image and slot publication")
        row.update(image_absent=True, slots_absent=True)
    else:
        if stats.get("errors") != "0":
            raise ValueError("The successful compile reported errors")
        data = image.read_bytes()
        if len(data) < 56 or data[:8] != b"M3PRIM01" or not slots.read_text(encoding="utf-8").startswith(
                "# M3PRIM01 SHA-256 " + data[24:56].hex()):
            raise ValueError("Invalid actual primitive image or slot namespace")
        row.update(image_sha256=digest(image), slots_sha256=digest(slots))
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("maven-home", "jdk-home", "stock-jdk", "out"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--maven-repository", "--repository", dest="repository", type=Path, required=True)
    args = parser.parse_args()
    maven, jdk, stock, repository = [path.resolve(strict=True) for path in
                                    (args.maven_home, args.jdk_home, args.stock_jdk, args.repository)]
    output = args.out.resolve()
    if re.search(r"\s", str(output)):
        raise ValueError("Use a proof output path without whitespace for javac plugin arguments")
    if any(output.is_relative_to(path) for path in (maven, jdk, stock, repository)):
        raise ValueError("Proof output cannot modify a supplied distribution or Maven cache")
    launcher = maven / "bin/mvn"
    cores = sorted((maven / "lib").glob("maven-core-*.jar"))
    if len(cores) != 1:
        raise ValueError("Expected exactly one native Maven core JAR")
    with zipfile.ZipFile(cores[0]) as jar:
        if "org/apache/maven/plugin/internal/M3AstCompilerConfiguration.class" not in jar.namelist():
            raise ValueError("The supplied distribution lacks this fork's native compiler integration")
    inputs = {"maven_launcher": launcher, "maven_core": cores[0],
              "m3jdk_release": jdk / "release", "m3jdk_modules": jdk / "lib/modules",
              "stock_release": stock / "release", "stock_modules": stock / "lib/modules"}
    hashes = {name: digest(path) for name, path in inputs.items()}
    output.mkdir(parents=True, exist_ok=False)
    settings = output / "settings.xml"
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><offline>true</offline></settings>\n',
                        encoding="utf-8")
    version = run([launcher, "--version"], jdk, output / "maven-version.log")
    if "Apache Maven 4.1.0-SNAPSHOT" not in version or str(jdk) not in version:
        raise ValueError("Expected this fork's Maven 4.1.0-SNAPSHOT and the supplied compiler image")
    processor = make_processor(output, stock)
    processor_version = install_processor(repository, processor)
    base = [launcher, "-o", "-B", "-ntp", "-s", settings, "-Dmaven.repo.local=" + str(repository)]
    expected, rows = None, []
    for name, compiler, enabled, capture, forked, broken in (
        ("default-stock", stock, False, False, False, False),
        ("enabled-stock-refusal", stock, True, False, False, False),
        ("enabled-m3", jdk, True, False, False, False),
        ("api-trace", jdk, True, True, False, False),
        ("command-line-trace", jdk, True, True, True, False),
        ("compiler-error", jdk, True, True, False, True),
    ):
        project = output / name
        trace, image, slots = [project / path for path in ("capture.tsv", "constants.m3prim", "slots.tsv")]
        argument = ("-Xplugin:M3AST lossless trace=" + str(trace) + " image=" + str(image)
                    + " slots=" + str(slots) + " fold-expressions") if capture else None
        write_project(project, processor_version, argument, broken)
        failed = broken or name == "enabled-stock-refusal"
        command = base + ["-f", project / "pom.xml", "-Dm3.ast=" + str(enabled).lower(),
                          "-Dmaven.compiler.fork=" + str(forked).lower(), COMPILER_PLUGIN]
        text = run(command, compiler, project / "compile.log", success=not failed)
        row = {"case": name, "native_opt_in": enabled, "forked": forked,
               "compile_log_sha256": digest(project / "compile.log")}
        if name == "enabled-stock-refusal":
            if "plug-in not found: M3AST" not in text:
                raise ValueError("Native Maven option did not reach the stock compiler")
            row["refusal"] = "plug-in not found: M3AST"
        elif not failed:
            actual = check_classes(project, compiler)
            if expected is None:
                expected = actual
                details = run([stock / "bin/javap", "-v", "-classpath", project / "target/classes", "fixture.Main"],
                              stock, project / "class-details.log")
                if "MethodParameters:" not in details:
                    raise ValueError("The existing -parameters compiler argument was lost")
            elif actual != expected:
                raise ValueError("Native M3AST option changed fixture bytecode")
            row.update(class_sha256=actual, processor_path_and_option_retained=True,
                       method_parameters_retained=True)
        if capture:
            row.update(check_capture(trace, image, slots, broken))
        rows.append(row)
    if hashes != {name: digest(path) for name, path in inputs.items()}:
        raise ValueError("A supplied runtime artifact changed during verification")
    receipt = {"status": "MAVEN4_NATIVE_OPTION_RUNTIME_PASS", "cases": rows,
               "maven_home": str(maven), "jdk_home": str(jdk), "stock_jdk": str(stock),
               "maven_version_output": version, "compiler_plugin": COMPILER_PLUGIN,
               "input_sha256": hashes, "verifier_sha256": digest(Path(__file__)),
               "processor_sha256": digest(processor),
               "processor_coordinate": "org.m3.fixture:native-consumer-processor:" + processor_version,
               "source_build_provenance": "SUPPLY_BUILD_RECEIPT_SEPARATELY",
               "full_vm_build_provenance": "SUPPLY_BUILD_RECEIPT_SEPARATELY"}
    (output / "RECEIPT.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("M3_NATIVE_MAVEN4_RUNTIME_PASS", output)


if __name__ == "__main__":
    main()
