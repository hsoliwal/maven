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
package org.apache.maven.recipes;

import org.openrewrite.Validated;

/** The default and the valid range of the recipes' Java major version options. */
final class VersionOption {
    static final int DEFAULT = 21;
    static final int LOWEST = 8;
    static final int HIGHEST = 99;

    private VersionOption() {}

    static int orDefault(Integer value) {
        return value == null ? DEFAULT : value;
    }

    static Validated<Integer> validate(String option, Integer value) {
        String message = "must be a Java major version from " + LOWEST + " to " + HIGHEST;
        return Validated.test(option, message, value, v -> v == null || (v >= LOWEST && v <= HIGHEST));
    }
}
