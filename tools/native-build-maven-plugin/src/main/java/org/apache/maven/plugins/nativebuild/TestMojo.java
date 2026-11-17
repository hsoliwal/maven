/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.nativebuild;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.SurefireXmlWriter;
import org.apache.maven.plugins.nativebuild.engine.TestReports;
import org.apache.maven.plugins.nativebuild.engine.TestSuiteReport;

/**
 * Runs the native tests in test (ctest, make check or test, meson test, a Ninja test target) and writes their
 * results as {@code TEST-native-<system>-<classifier>.xml} in the surefire format into the reports directory, copied
 * to target/surefire-reports by default. Honours skipTests, maven.test.skip and maven.test.failure.ignore.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public final class TestMojo extends AbstractNativeMojo {
    /** Skips the native tests (also skips Java tests in surefire). */
    @Parameter(property = "skipTests", defaultValue = "false")
    boolean skipTests;

    /** Skips the native tests. */
    @Parameter(property = "maven.test.skip", defaultValue = "false")
    boolean skipAllTests;

    /** Reports failed native tests without failing the build. */
    @Parameter(property = "maven.test.failure.ignore", defaultValue = "false")
    boolean testFailureIgnore;

    /** Also copies the report to the surefire reports directory. */
    @Parameter(property = "native.reports.surefireCopy", defaultValue = "true")
    boolean surefireCopy;

    /** The surefire reports directory. */
    @Parameter(defaultValue = "${project.build.directory}/surefire-reports")
    File surefireReportsDirectory;

    @Override
    protected void runNative() throws IOException, InterruptedException, MojoFailureException {
        if (skipTests || skipAllTests) {
            getLog().info("native tests skipped");
            return;
        }
        NativeSession session = open(false);
        if (session.found() && session.step(NativePhase.TEST).isEmpty()) {
            getLog().info("no native test step for " + session.context().system().id());
        } else if (session.found()) {
            verdict(test(session));
        }
    }

    private TestSuiteReport test(NativeSession session) throws IOException, InterruptedException {
        Path junit = TestReports.junitFile(session.context(), session.planner());
        if (!incremental) {
            Files.deleteIfExists(junit);
        }
        long start = System.nanoTime();
        String failure = runTests(session);
        double seconds = (System.nanoTime() - start) / 1e9;
        String suite = TestReports.suite(session.context(), session.classifier());
        return write(TestReports.collect(session.context(), session.planner(), suite, failure, seconds));
    }

    /** Runs the test step; returns its failure message, empty when it passed. */
    private static String runTests(NativeSession session) throws InterruptedException {
        try {
            session.run(NativePhase.TEST);
            return "";
        } catch (IOException failed) {
            return String.valueOf(failed.getMessage());
        }
    }

    private TestSuiteReport write(TestSuiteReport report) throws IOException {
        Path file = SurefireXmlWriter.write(report, reportsDirectory.toPath());
        getLog().info("REPORT " + file + " (" + report.summary() + ")");
        if (surefireCopy) {
            Path copy = surefireReportsDirectory.toPath().resolve(file.getFileName());
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            getLog().info("REPORT copied to " + copy);
        }
        return report;
    }

    private void verdict(TestSuiteReport report) throws MojoFailureException {
        long bad = report.count(TestSuiteReport.Status.FAILED) + report.count(TestSuiteReport.Status.ERRORED);
        if (bad == 0) {
            return;
        }
        String message = "native tests failed: " + report.summary() + "; see " + reportsDirectory;
        if (testFailureIgnore) {
            getLog().warn(message + " (maven.test.failure.ignore)");
        } else {
            throw new MojoFailureException(message);
        }
    }
}
