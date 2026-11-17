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
package org.apache.maven.mcp;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/** Named checks for the test mains: each check is logged, and {@link #finish()} fails when any check failed. */
final class Checks {

    private static final System.Logger LOG = System.getLogger(Checks.class.getName());

    private final String suite;
    private final List<String> failures = new ArrayList<>();
    private int passed;

    Checks(String suite) {
        this.suite = suite;
    }

    void that(String name, boolean ok) {
        that(name, ok, "");
    }

    /** Records one check; {@code detail} is logged with a failure. */
    void that(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            LOG.log(Level.INFO, "PASS " + suite + ": " + name);
        } else {
            failures.add(name);
            LOG.log(Level.ERROR, "FAIL " + suite + ": " + name + (detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    /** Logs the totals and throws when a check failed, which fails the exec goal and the build. */
    void finish() {
        LOG.log(Level.INFO, suite + ": " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            throw new AssertionError(suite + " failed: " + failures);
        }
    }
}
