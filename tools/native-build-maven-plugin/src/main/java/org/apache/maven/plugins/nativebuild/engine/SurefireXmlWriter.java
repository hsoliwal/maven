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
package org.apache.maven.plugins.nativebuild.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes a {@link TestSuiteReport} in the surefire report format ({@code TEST-<suite>.xml}: testsuite with tests,
 * failures, errors, skipped and time; testcase with failure, error or skipped children and system-out), which the
 * surefire report plugin, CI servers and IDEs read.
 */
public final class SurefireXmlWriter {
    private SurefireXmlWriter() {}

    /** The report file name for a suite. */
    public static String fileName(String suite) {
        return "TEST-" + suite + ".xml";
    }

    /** Writes the report into {@code directory}; returns the file. */
    public static Path write(TestSuiteReport report, Path directory) throws IOException {
        Path file = directory.resolve(fileName(report.name()));
        TaskFileWriter.write(file, render(report));
        return file;
    }

    /** The report XML. */
    public static String render(TestSuiteReport r) {
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite xmlns:xsi=\"http://www.w3.org/2001/"
                + "XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/"
                + "maven-surefire-plugin/xsd/surefire-test-report.xsd\" name=\"" + XmlSupport.escape(r.name())
                + "\" time=\"" + time(r.seconds()) + "\" tests=\"" + r.cases().size() + "\" errors=\""
                + r.count(TestSuiteReport.Status.ERRORED) + "\" skipped=\"" + r.count(TestSuiteReport.Status.SKIPPED)
                + "\" failures=\"" + r.count(TestSuiteReport.Status.FAILED) + "\">\n";
        return head + r.cases().stream().map(SurefireXmlWriter::testCase).collect(Collectors.joining())
                + "</testsuite>\n";
    }

    private static String testCase(TestSuiteReport.Case c) {
        String open = "  <testcase name=\"" + XmlSupport.escape(c.name()) + "\" classname=\""
                + XmlSupport.escape(c.className()) + "\" time=\"" + time(c.seconds()) + "\"";
        String detail = detail(c) + (c.output().isEmpty() ? ""
                : "    <system-out>" + XmlSupport.escape(c.output()) + "</system-out>\n");
        return detail.isEmpty() ? open + "/>\n" : open + ">\n" + detail + "  </testcase>\n";
    }

    private static String detail(TestSuiteReport.Case c) {
        return switch (c.status()) {
            case FAILED -> problem("failure", c.message());
            case ERRORED -> problem("error", c.message());
            case SKIPPED -> "    <skipped message=\"" + XmlSupport.escape(c.message()) + "\"/>\n";
            case PASSED -> "";
        };
    }

    /**
     * A failure or error: the first line in the {@code message} attribute and, for a message of several lines, the
     * whole text as element content, because XML attribute normalization turns line breaks into spaces in every
     * reader (the surefire schema allows text in both elements).
     */
    private static String problem(String element, String message) {
        String first = message.lines().findFirst().orElse("");
        String open = "    <" + element + " message=\"" + XmlSupport.escape(first) + "\" type=\"native\"";
        return message.strip().equals(first.strip()) ? open + "/>\n"
                : open + ">" + XmlSupport.escape(message) + "</" + element + ">\n";
    }

    private static String time(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
