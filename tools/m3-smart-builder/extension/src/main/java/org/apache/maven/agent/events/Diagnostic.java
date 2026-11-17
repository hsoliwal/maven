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

/**
 * One located diagnostic of a project. {@code file} is root-relative with '/' separators; 0 for an unknown line
 * or column and "" for an unknown rule or mojo are written as JSON null.
 *
 * @param project project id {@code groupId:artifactId}, "" when no reactor project holds the file
 * @param source compiler, log, checkstyle, pmd, cpd or spotbugs
 * @param severity ERROR, WARNING or INFO
 * @param file root-relative file
 * @param line 1-based line, 0 when unknown
 * @param column 1-based column, 0 when unknown
 * @param rule rule id or javac lint key, "" when unknown
 * @param message message, already bounded and with root paths made relative
 * @param mojo {@code prefix:goal} that reported it, "" when unknown
 */
record Diagnostic(String project, String source, String severity, String file, int line, int column, String rule,
        String message, String mojo) {

    /** Identity of a diagnostic across sources: file, line, rule and message (a failure message and the log). */
    String key() {
        return file + '\u0001' + line + '\u0001' + rule + '\u0001' + message;
    }

    /** What repeats collapse on within a project and severity: the rule, or the message when there is none. */
    String repeatKey() {
        return rule.isEmpty() ? message : rule;
    }

    /** {@code file:line:column}, leaving out what is unknown. */
    String location() {
        return file + (line > 0 ? ":" + line : "") + (line > 0 && column > 0 ? ":" + column : "");
    }

    /** The fields of a {@code diagnostic} event. */
    JsonText.Obj json() {
        return new JsonText.Obj().put("project", JsonText.orNull(project)).put("source", source)
                .put("severity", severity).putAll(located());
    }

    /** The located part, shared by the event and the failure digest. */
    JsonText.Obj located() {
        return new JsonText.Obj().put("file", file).put("line", line > 0 ? line : null)
                .put("column", column > 0 ? column : null).put("rule", JsonText.orNull(rule))
                .put("message", message).put("mojo", JsonText.orNull(mojo));
    }
}
