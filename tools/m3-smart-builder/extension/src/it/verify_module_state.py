#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Real Maven process/Java reactor acceptance, using an explicit dependency cache.

No mocked Mojos or synthesized success receipts. Logs and fixtures are retained
in --work. The caller supplies actual built extension and state JARs. Maven runs
offline except when --prime-repository explicitly enables dependency resolution
for the disabled baseline; missing dependencies leave the affected gate failed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile
from m3_sha_evidence import scan_evidence, qualify_cold_and_warm
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', required=True)
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--extension-jar', required=True, type=Path)
    parser.add_argument('--state-jar', required=True, type=Path)
    parser.add_argument('--sqlite-jar', type=Path,
                        help='actual Xerial SQLite JDBC driver; enables six real SQLite Maven scenarios')
    parser.add_argument('--h2-jar', type=Path,
                        help='actual H2 JDBC driver; enables six real H2 Maven scenarios')
    parser.add_argument('--mindexdb-classpath',
                        help='actual MIndexDB provider and dependency JARs, separated by '
                             + repr(os.pathsep) + '; enables ten native Maven scenarios on Java 21+')
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--prime-repository', action='store_true',
                        help='allow only the disabled baseline build to resolve pinned fixture dependencies online')
    args = parser.parse_args()
    drivers = {}
    for backend, path in (('sqlite', args.sqlite_jar), ('h2', args.h2_jar)):
        if path is not None:
            if not path.is_file() or not zipfile.is_zipfile(path):
                raise SystemExit(backend + ' driver must be an actual existing JAR')
            drivers[backend] = path.resolve()
    mindexdb_jars = []
    mindexdb_identities = []
    if args.mindexdb_classpath is not None:
        for entry in args.mindexdb_classpath.split(os.pathsep):
            path = Path(entry)
            if (not entry or path.suffix.lower() != '.jar'
                    or not path.is_file() or not zipfile.is_zipfile(path)):
                parser.error('--mindexdb-classpath must contain nonempty paths to existing JARs')
            path = path.resolve()
            if path in mindexdb_jars:
                parser.error('--mindexdb-classpath contains the same JAR more than once')
            try:
                data = path.read_bytes()
            except OSError as failure:
                parser.error('cannot read MIndexDB classpath JAR: ' + str(failure))
            mindexdb_jars.append(path)
            mindexdb_identities.append({'path': str(path), 'bytes': len(data),
                                       'sha256': hashlib.sha256(data).hexdigest()})
    work = args.work.resolve()
    if work.exists() and any(work.iterdir()):
        raise SystemExit('work directory must be new or empty')
    work.mkdir(parents=True, exist_ok=True)
    native_evidence = ({'status': 'PENDING', 'classpath': mindexdb_identities}
                       if mindexdb_jars else
                       {'status': 'NOT_RUN', 'reason': 'No authentic MIndexDB classpath supplied'})
    (work / 'mindexdb-provider.json').write_text(json.dumps(native_evidence, indent=2) + '\n')
    repo = work / 'repository'
    shutil.copytree(args.repository.resolve(), repo)
    reactor = work / 'reactor'
    reactor.mkdir()
    (work / 'settings.xml').write_text('<settings/>\n', encoding='utf-8')
    log_dir = work / 'logs'
    log_dir.mkdir()
    runs_file = work / 'test-runs.txt'
    failure_file = work / 'fail-tests'
    expected_file = work / 'expected-value.txt'
    expected_file.write_text('11\n')
    custom_properties = work / 'custom-pom.properties'
    custom_properties.write_text('fixture.marker=first\n')
    results = []

    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    plugins = '''<build><pluginManagement><plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-clean-plugin</artifactId><version>3.2.0</version></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><proc>none</proc></configuration></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version><configuration><systemPropertyVariables><fixture.log>LOG</fixture.log><fixture.fail>FAIL</fixture.fail><fixture.expected>EXPECTED</fixture.expected></systemPropertyVariables></configuration></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version><configuration><archive><pomPropertiesFile>CUSTOM_PROPERTIES</pomPropertiesFile></archive></configuration></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-install-plugin</artifactId><version>3.1.2</version></plugin>
    </plugins></pluginManagement></build>'''.replace('LOG', escape(str(runs_file))).replace('FAIL', escape(str(failure_file))).replace('EXPECTED', escape(str(expected_file))).replace('CUSTOM_PROPERTIES', escape(str(custom_properties)))
    prefix = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    write(reactor / 'pom.xml', prefix + '<groupId>m3.fixture</groupId><artifactId>reactor</artifactId><version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>library</module><module>consumer</module></modules><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>' + plugins + '</project>\n')
    parent = '<parent><groupId>m3.fixture</groupId><artifactId>reactor</artifactId><version>1.0-SNAPSHOT</version></parent>'
    write(reactor / 'library/pom.xml', prefix + parent + '<artifactId>library</artifactId><properties><fixture.consumer.marker>live-consumer-input</fixture.consumer.marker></properties></project>\n')
    write(reactor / 'consumer/pom.xml', prefix + parent + '''<artifactId>consumer</artifactId><dependencies>
      <dependency><groupId>m3.fixture</groupId><artifactId>library</artifactId><version>1.0-SNAPSHOT</version></dependency>
      <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope></dependency>
    </dependencies></project>\n''')
    library = reactor / 'library/src/main/java/fixture/Library.java'
    write(library, 'package fixture; public final class Library { public static int value() { return 11; } }\n')
    write(reactor / 'library/src/main/resources/value.txt', 'first\n')
    write(reactor / 'consumer/src/main/java/fixture/Consumer.java', 'package fixture; public final class Consumer { public int value() { return Library.value(); } }\n')
    write(reactor / 'consumer/src/test/java/fixture/ConsumerTest.java', '''package fixture;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
public class ConsumerTest {
 @Test public void alwaysRun() throws Exception {
  Files.writeString(Path.of(System.getProperty("fixture.log")), "test\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  if (Files.exists(Path.of(System.getProperty("fixture.fail")))) throw new AssertionError("retained failing-test witness");
  int expected = Integer.parseInt(Files.readString(Path.of(System.getProperty("fixture.expected"))).trim());
  if (new Consumer().value() != expected) throw new AssertionError("stale reactor artifact: " + new Consumer().value() + " expected " + expected);
 }
}
''')
    env = dict(os.environ)
    ext = os.pathsep.join(str(p.resolve()) for p in
                          (args.extension_jar, args.state_jar, *drivers.values(), *mindexdb_jars))
    env.pop('MAVEN_OPTS', None)
    env.pop('MAVEN_ARGS', None)
    runtime_version = subprocess.check_output([args.maven, '-version'], env=env, text=True).splitlines()
    base = [args.maven, '-o', '-B', '-ntp', '-s', str(work / 'settings.xml'),
            '-Dmaven.repo.local=' + str(repo), '-Dmaven.ext.class.path=' + ext]

    def require(condition, message):
        if not condition:
            raise AssertionError(message)

    def unchanged_mindexdb_classpath():
        for identity in mindexdb_identities:
            data = Path(identity['path']).read_bytes()
            require(len(data) == identity['bytes']
                    and hashlib.sha256(data).hexdigest() == identity['sha256'],
                    'MIndexDB classpath JAR changed during acceptance: ' + identity['path'])

    def run(name, flags=(), success=True, enabled=True, directory=None, measure_sha=False):
        unchanged_mindexdb_classpath()
        command = base + (['-Dm3.lifecycle.incremental=true'] if enabled else []) + list(flags) + (['-X'] if measure_sha else []) + ['verify']
        if args.prime_repository and name == 'disabled-baseline':
            command.remove('-o')
            # Resolve later offline plugin realms without early clean/install effects.
            command[-1:-1] = [
                'org.apache.maven.plugins:maven-clean-plugin:3.2.0:help',
                'org.apache.maven.plugins:maven-install-plugin:3.1.2:help',
            ]
        started = time.monotonic()
        process = subprocess.run(command, cwd=directory or reactor, env=env, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, timeout=240)
        text = process.stdout
        (log_dir / (name + '.log')).write_text(text, encoding='utf-8')
        row = {'case': name, 'exit': process.returncode, 'expected_success': success,
               'seconds': round(time.monotonic() - started, 3),
               'hits': [line for line in text.splitlines() if 'M3DB UP-TO-DATE' in line]}
        if measure_sha:
            row['sha256_evidence'] = scan_evidence(text)
        results.append(row)
        (work / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(name, 'exit=' + str(process.returncode), 'hits=' + str(len(row['hits'])), flush=True)
        require((process.returncode == 0) == success, name + ' unexpected Maven result; see ' + str(log_dir / (name + '.log')))
        return text

    def count_tests():
        return runs_file.read_text().count('test\n') if runs_file.exists() else 0

    run('disabled-baseline', enabled=False)
    require(not (reactor / 'library/.m3').exists(), 'disabled fresh build created state')
    run('cold', measure_sha=True)
    tests = count_tests()
    warm = run('warm-fresh-jvm', measure_sha=True)
    scan_proof = qualify_cold_and_warm(results[-2]['sha256_evidence'], results[-1]['sha256_evidence'])
    (work / 'sha256-scan-evidence.json').write_text(json.dumps(scan_proof, indent=2) + '\n')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' in warm, 'library compile not reused')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (consumer)' in warm, 'consumer compile not reused')
    require('M3DB UP-TO-DATE maven-jar-plugin:jar (library)' in warm, 'library jar not reused')
    require(count_tests() == tests + 1, 'tests were skipped on a cache hit')
    require((reactor / 'library/.m3/module.m3db').is_file(), 'missing embedded M3DB')

    jar = reactor / 'library/target/library-1.0-SNAPSHOT.jar'
    properties_entry = 'META-INF/maven/m3.fixture/library/pom.properties'
    with zipfile.ZipFile(jar) as archive:
        require('fixture.marker=first' in archive.read(properties_entry).decode('iso-8859-1'),
                'original archiver did not package the configured properties')
    old = custom_properties.stat()
    custom_properties.write_text('fixture.marker=other\n')
    os.utime(custom_properties, ns=(old.st_atime_ns, old.st_mtime_ns))
    archive_changed = run('external-pom-properties')
    with zipfile.ZipFile(jar) as archive:
        actual_properties = archive.read(properties_entry).decode('iso-8859-1')
    (work / 'external-pom-properties.json').write_text(json.dumps({
        'input': str(custom_properties), 'source_mtime_preserved': True,
        'expected_property': 'fixture.marker=other', 'actual_properties': actual_properties,
        'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}, indent=2) + '\n')
    require('M3DB UP-TO-DATE maven-jar-plugin:jar (library)' not in archive_changed,
            'external archive properties incorrectly reused a previous JAR')
    require('fixture.marker=other' in actual_properties, 'configured external properties were not refreshed')

    old = library.stat()
    library.write_text(library.read_text().replace('11', '12'))
    expected_file.write_text('12\n')
    os.utime(library, ns=(old.st_atime_ns, old.st_mtime_ns))
    changed = run('same-time-source-and-downstream')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in changed, 'same-time source reused')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (consumer)' not in changed, 'changed dependency reused')
    run('settled')

    resource = reactor / 'library/src/main/resources/value.txt'
    old = resource.stat()
    resource.write_text('other\n')
    os.utime(resource, ns=(old.st_atime_ns, old.st_mtime_ns))
    run('same-time-resource')
    require((reactor / 'library/target/classes/value.txt').read_text() == 'other\n', 'resource tool timestamp shortcut returned stale bytes')

    classes = reactor / 'library/target/classes/fixture/Library.class'
    data = classes.read_bytes()
    classes.write_bytes(b'broken class output')
    tamper = run('output-tamper')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in tamper, 'modified class reused')
    # Maven's compiler decides how to repair its own incremental output; verify actual valid class bytes.
    require(classes.read_bytes().startswith(b'\xca\xfe\xba\xbe'), 'original compiler did not restore a valid class')

    old = jar.stat()
    jar.write_bytes(b'broken jar output')
    os.utime(jar, ns=(old.st_atime_ns, old.st_mtime_ns))
    run('jar-output-tamper')
    require(jar.read_bytes().startswith(b'PK'), 'jar tool timestamp shortcut preserved corrupt bytes')

    (reactor / 'library/.m3/module.m3db').write_bytes(b'not a database')
    corrupt = run('corrupt-database')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in corrupt, 'corrupt database authorized reuse')
    run('post-corruption-warm')

    tests = count_tests()
    failure_file.write_text('fail\n')
    failed = run('tests-still-fail', success=False)
    require('retained failing-test witness' in failed, 'failure came from something other than original test')
    require(count_tests() == tests + 1, 'failing test never executed')
    failure_file.unlink()
    run('after-test-failure')

    run('explicit-nonincremental', enabled=False)
    invalidated = run('after-nonincremental')
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in invalidated, 'nonincremental run left old success')
    run('parallel-reactor', flags=('-T', '2'))
    parallel_warm = run('parallel-reactor-warm', flags=('-T', '2'))
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' in parallel_warm, 'parallel build did not reuse module state')
    install = run('install-still-executes', flags=('install',))
    require((repo / 'm3/fixture/library/1.0-SNAPSHOT/library-1.0-SNAPSHOT.jar').is_file(), 'install did not publish original artifact')
    require('M3DB UP-TO-DATE maven-jar-plugin:jar (library)' in install,
            'install fixture did not observe a reused JAR')
    installed_pom = repo / 'm3/fixture/library/1.0-SNAPSHOT/library-1.0-SNAPSHOT.pom'
    require(installed_pom.is_file(), 'install did not publish the current POM')
    namespace = {'p': 'http://maven.apache.org/POM/4.0.0'}
    installed_model = ET.parse(installed_pom).getroot()
    require(installed_model.findtext('p:artifactId', namespaces=namespace) == 'library'
            and installed_model.findtext('p:properties/p:fixture.consumer.marker', namespaces=namespace) == 'live-consumer-input',
            'install lost the live consumer model after a cached JAR')
    build_pom = installed_pom.with_name('library-1.0-SNAPSHOT-build.pom')
    if any('Apache Maven 4.' in line for line in runtime_version):
        require(build_pom.is_file(), 'Maven4 did not preserve its original build/consumer POM installation')
    (work / 'installed-pom.json').write_text(json.dumps({
        'warm_jar_hit': True, 'consumer_pom_sha256': hashlib.sha256(installed_pom.read_bytes()).hexdigest(),
        'live_marker': 'live-consumer-input',
        'build_pom_sha256': hashlib.sha256(build_pom.read_bytes()).hexdigest() if build_pom.is_file() else None}, indent=2) + '\n')
    run('file-backend', flags=('-Dm3.lifecycle.backend=file',))
    file_warm = run('file-backend-warm', flags=('-Dm3.lifecycle.backend=file',))
    require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' in file_warm, 'file backend did not persist')
    require((reactor / 'library/.m3/module-state-v1.bin').is_file(), 'file backend image missing')
    run('clean-runs', flags=('clean',))
    empty = reactor / 'empty'
    write(empty / 'pom.xml', prefix + parent + '<artifactId>empty</artifactId><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><configuration><skipIfEmpty>true</skipIfEmpty></configuration></plugin></plugins></build></project>\n')
    (empty / 'target/classes').mkdir(parents=True)
    shutil.copyfile(jar, empty / 'target/empty-1.0-SNAPSHOT.jar')
    run('empty-jar-with-stale-file', directory=empty)
    empty_hit = run('empty-jar-repeat', directory=empty)
    require('M3DB UP-TO-DATE maven-jar-plugin:jar' not in empty_hit, 'skipIfEmpty invented an attached artifact')

    # javac discovers processors in the project's own outputs as well as in
    # dependencies. Keep main compilation explicitly disabled for processing,
    # then let the original test compiler discover the real compiled provider.
    processor = work / 'processor'
    processor_log = work / 'processor-runs.txt'
    processor_input = work / 'processor-input.txt'
    processor_input.write_text('first\n')
    processor_plugins = plugins.replace('<configuration><proc>none</proc></configuration>',
        '<executions><execution><id>default-compile</id><configuration><proc>none</proc></configuration></execution></executions>')
    write(processor / 'pom.xml', prefix + '<groupId>m3.fixture</groupId><artifactId>processor</artifactId><version>1.0-SNAPSHOT</version><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>' + processor_plugins + '</project>\n')
    write(processor / 'src/main/java/fixture/OwnProcessor.java', '''package fixture;
import java.nio.file.*;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class OwnProcessor extends AbstractProcessor {
 @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
  if (!round.processingOver()) try {
   String value = Files.readString(Path.of(System.getProperty("fixture.processor.input")));
   Files.writeString(Path.of(System.getProperty("fixture.processor.log")), value, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
  return false;
 }
}
''')
    write(processor / 'src/main/resources/META-INF/services/javax.annotation.processing.Processor', 'fixture.OwnProcessor\n')
    processor_test = processor / 'src/test/java/fixture/ProcessorConsumer.java'
    write(processor_test, 'package fixture; public final class ProcessorConsumer {}\n')
    processor_flags = ('-Dfixture.processor.log=' + str(processor_log),
                       '-Dfixture.processor.input=' + str(processor_input))
    run('project-processor-cold', directory=processor, flags=processor_flags)
    require(processor_log.is_file() and 'first' in processor_log.read_text(), 'original javac did not discover the actual project processor')
    processor_warm = run('project-processor-warm', directory=processor, flags=processor_flags)
    require('M3DB UP-TO-DATE maven-compiler-plugin:testCompile (processor)' not in processor_warm,
            'project output processor escaped cache admission')
    before = processor_log.read_text()
    processor_input.write_text('other\n')
    stamp = time.time_ns() + 2_000_000_000
    os.utime(processor_test, ns=(stamp, stamp))
    touched = run('project-processor-source-touch', directory=processor, flags=processor_flags)
    require('M3DB UP-TO-DATE maven-compiler-plugin:testCompile (processor)' not in touched,
            'processor-dependent compilation was reused after original compiler timestamp invalidation')
    require(processor_log.read_text().startswith(before) and 'other' in processor_log.read_text()[len(before):],
            'original compiler did not rerun the processor with its changed side input')

    # Optional engines use their authentic supplied implementations. Switching from
    # another backend must start cold; the next separate JVM must reuse the
    # committed image and still run the original tests. Restored timestamps and
    # failing tests exercise the same lifecycle contract on each actual engine.
    jdbc_evidence = {}
    native_artifacts = {}
    optional_backends = list(drivers) + (['m3db'] if mindexdb_jars else [])
    for backend in optional_backends:
        flags = ('-Dm3.lifecycle.backend=' + backend,)
        cold = run(backend + '-cold', flags=flags)
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in cold,
                backend + ' reused receipts across a backend switch')
        if backend == 'm3db':
            require('M3DB UP-TO-DATE maven-compiler-plugin:compile (consumer)' not in cold,
                    'm3db reused downstream receipts across a backend switch')
        tests = count_tests()
        warm = run(backend + '-warm-fresh-jvm', flags=flags)
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' in warm,
                backend + ' did not reuse actual compiler state')
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (consumer)' in warm,
                backend + ' did not reuse actual consumer state')
        require('M3DB UP-TO-DATE maven-jar-plugin:jar (library)' in warm,
                backend + ' did not reuse and attach the actual JAR')
        require(count_tests() == tests + 1, backend + ' skipped original tests')
        if backend == 'm3db':
            # This is the original com.synexia MIndexDB artifact format, distinct
            # from the plugin's embedded library engine and its file backend.
            artifact_name = hashlib.sha256(b'maven-module-build-state-v1').hexdigest() + '.miart'
            for module in ('library', 'consumer'):
                image = reactor / module / '.m3/maven-build-db' / artifact_name
                require(image.is_file() and image.stat().st_size > 64,
                        'native MIndexDB artifact missing for ' + module)
                with image.open('rb') as stream:
                    require(stream.read(8) == b'MINDEXAR',
                            'native MIndexDB artifact format missing for ' + module)
                native_artifacts[module] = str(image.relative_to(work))
        else:
            image = reactor / ('library/.m3/module-state.' +
                               ('sqlite' if backend == 'sqlite' else 'mv.db'))
            require(image.is_file() and image.stat().st_size > 0,
                    backend + ' database image was not created')
        previous = int(expected_file.read_text().strip())
        following = previous + 1
        old = library.stat()
        library.write_text(library.read_text().replace('return ' + str(previous), 'return ' + str(following)))
        expected_file.write_text(str(following) + '\n')
        os.utime(library, ns=(old.st_atime_ns, old.st_mtime_ns))
        if backend == 'm3db':
            require(library.stat().st_size == old.st_size
                    and library.stat().st_mtime_ns == old.st_mtime_ns,
                    'native source-invalidation fixture did not preserve size and mtime')
        changed = run(backend + '-same-time-source-and-downstream', flags=flags)
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' not in changed,
                backend + ' reused a changed source with restored timestamp')
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (consumer)' not in changed,
                backend + ' reused a changed dependency')
        settled = run(backend + '-settled', flags=flags)
        require('M3DB UP-TO-DATE maven-compiler-plugin:compile (library)' in settled,
                backend + ' did not settle after content invalidation')
        tests = count_tests()
        failure_file.write_text('fail\n')
        failed = run(backend + '-tests-still-fail', flags=flags, success=False)
        require('retained failing-test witness' in failed,
                backend + ' failure did not come from the original test')
        require(count_tests() == tests + 1, backend + ' failing test did not execute')
        failure_file.unlink()
        run(backend + '-after-test-failure', flags=flags)
        if backend in drivers:
            driver = drivers[backend]
            jdbc_evidence[backend] = {'status': 'PASSED', 'cases': 6,
                                      'driver_name': driver.name,
                                      'driver_sha256': hashlib.sha256(driver.read_bytes()).hexdigest(),
                                      'database': str(image.relative_to(work))}
    if mindexdb_jars:
        # Return to a previously populated backend, then to the real native
        # store. Neither switch may revive a success from an older generation.
        for backend, label in (('file', 'm3db-switch-file'), ('m3db', 'm3db-return-native')):
            flags = ('-Dm3.lifecycle.backend=' + backend,)
            cold = run(label + '-cold', flags=flags)
            for module in ('library', 'consumer'):
                require('M3DB UP-TO-DATE maven-compiler-plugin:compile (' + module + ')' not in cold,
                        label + ' revived an earlier backend generation for ' + module)
            tests = count_tests()
            warm = run(label + '-warm-fresh-jvm', flags=flags)
            for module in ('library', 'consumer'):
                require('M3DB UP-TO-DATE maven-compiler-plugin:compile (' + module + ')' in warm,
                        label + ' did not persist fresh compiler state for ' + module)
            require('M3DB UP-TO-DATE maven-jar-plugin:jar (library)' in warm,
                    label + ' did not reuse and attach the actual JAR')
            require(count_tests() == tests + 1, label + ' skipped original tests')
        unchanged_mindexdb_classpath()
        native_evidence = {'status': 'PASSED', 'cases': 10,
                           'provider': 'com.synexia.mindex.db.MIndexDB',
                           'classpath': mindexdb_identities,
                           'artifacts': {module: {'path': relative,
                               'sha256': hashlib.sha256((work / relative).read_bytes()).hexdigest()}
                               for module, relative in native_artifacts.items()}}
        (work / 'mindexdb-provider.json').write_text(json.dumps(native_evidence, indent=2) + '\n')
    for backend in ('sqlite', 'h2'):
        if backend not in jdbc_evidence:
            jdbc_evidence[backend] = {'status': 'NOT_RUN', 'reason': 'No authentic JDBC driver supplied'}
    unchanged_mindexdb_classpath()
    print('PASS: real Maven lifecycle reuse, content and archive invalidation, own-project processor admission, corruption, tests, clean, install, parallel reactor, portable file backend and ' + str(len(drivers)) + ' actual JDBC engines', flush=True)
    (work / 'acceptance.json').write_text(json.dumps({'status': 'PASSED', 'cases': len(results),
        'repository_priming': args.prime_repository,
        'extension_sha256': hashlib.sha256(args.extension_jar.read_bytes()).hexdigest(),
        'state_sha256': hashlib.sha256(args.state_jar.read_bytes()).hexdigest(),
        'jdbc_backends': jdbc_evidence,
        'native_mindexdb': native_evidence,
        'sha256_scan_evidence': scan_proof,
        'runtime': runtime_version,
        'results': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
