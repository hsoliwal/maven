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
package org.apache.maven.recipes.javac;

/**
 * One row of {@code LST_CENSUS.tsv}.
 *
 * @param path the source path relative to the plugin's root, with {@code /} separators
 * @param parseEvents how many PARSE events javac sent for the file (one parse means 1)
 * @param printEqual whether the tree prints the exact source
 * @param attributedMethodCalls calls with a method type
 * @param unattributedMethodCalls calls without one
 * @param status what happened to the file, with a detail for failures
 * @param builtAt the javac event the tree was built at: ANALYZE, COMPILATION or {@code -}
 */
public record CensusRow(
        String path,
        int parseEvents,
        boolean printEqual,
        int attributedMethodCalls,
        int unattributedMethodCalls,
        String status,
        String builtAt) {
    /** The census header line. */
    public static final String HEADER =
            "path\tparse_events\tprint_equal\tattributed_method_calls\tunattributed_method_calls\tstatus\tbuilt_at";

    /** @return the row as one tab-separated line */
    public String tsv() {
        return String.join(
                "\t",
                path,
                Integer.toString(parseEvents),
                Boolean.toString(printEqual),
                Integer.toString(attributedMethodCalls),
                Integer.toString(unattributedMethodCalls),
                status,
                builtAt);
    }
}
