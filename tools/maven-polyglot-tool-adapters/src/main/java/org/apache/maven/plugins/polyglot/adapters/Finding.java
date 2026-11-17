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
package org.apache.maven.plugins.polyglot.adapters;

import java.util.Comparator;
import java.util.Objects;

/**
 * One diagnostic of an analyzer or linter, as collected inside the tool's process: the file relative to the analysed
 * root ('/' separators) or an absolute {@code file:} URI outside it, 1-based line and column (0 = unknown), level,
 * rule id and message.
 */
record Finding(String file, long line, long column, Level level, String rule, String message) {
    /** Report order: file, line, column, rule, message; makes the reports byte-reproducible. */
    static final Comparator<Finding> ORDER = Comparator.comparing(Finding::file).thenComparingLong(Finding::line)
            .thenComparingLong(Finding::column).thenComparing(Finding::rule).thenComparing(Finding::message);

    /** SARIF result levels and the matching build-events severities. */
    enum Level {
        ERROR("error", "ERROR"),
        WARNING("warning", "WARNING"),
        NOTE("note", "INFO");

        private final String sarif;
        private final String severity;

        Level(String sarif, String severity) {
            this.sarif = sarif;
            this.severity = severity;
        }

        String sarif() {
            return sarif;
        }

        String severity() {
            return severity;
        }
    }

    Finding {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(level, "level");
        rule = rule == null || rule.isBlank() ? "unnamed" : rule;
        message = message == null || message.isBlank() ? rule : message;
        line = Math.max(0, line);
        column = Math.max(0, column);
    }
}
