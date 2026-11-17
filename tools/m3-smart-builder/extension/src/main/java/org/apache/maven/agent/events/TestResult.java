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
package org.apache.maven.agent.events;

import java.util.List;

/**
 * One test case as the agent files show it: bounded message, trimmed stack and the test source line it failed at.
 *
 * @param project project id
 * @param test the case as reported
 * @param message bounded, root-relative message
 * @param stack trimmed stack trace lines
 * @param file root-relative test source of the failing frame, "" when unknown
 * @param line line of the failing frame, 0 when unknown
 */
record TestResult(String project, SurefireReports.TestCase test, String message, List<String> stack, String file,
        int line) {

    /** The fields of a {@code test} event. */
    JsonText.Obj json() {
        JsonText.Obj location = file.isEmpty() ? null
                : new JsonText.Obj().put("file", file).put("line", line > 0 ? line : null);
        return new JsonText.Obj().put("project", project).put("class", test.className()).put("method", test.method())
                .put("outcome", test.outcome()).put("durationMs", test.durationMs())
                .put("exception", JsonText.orNull(test.exception())).put("message", JsonText.orNull(message))
                .put("stack", stack).put("location", location);
    }

    /** The fields of a test in the failure digest: no duration, so reruns compare byte for byte. */
    JsonText.Obj digest() {
        return new JsonText.Obj().put("class", test.className()).put("method", test.method())
                .put("exception", JsonText.orNull(test.exception())).put("message", JsonText.orNull(message))
                .put("stack", stack);
    }

    /** {@code Class#method}. */
    String name() {
        return test.className() + "#" + test.method();
    }
}
