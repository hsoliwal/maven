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
package org.apache.maven.api.services;

/*
 * Modified 2026-10-21 by Hitesh Soliwal and Contributors to the Synexia Project:
 * Complete the Apache license notice and record documentation without changing the service API.
 */

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Maven 4 plugin-facing access to native M3JDK21 build tools. The JDK owns the
 * class-file reader, analysis, schema and toolchain identity; Maven shares
 * immutable receipts with participating plugins, never reconstructing an AST.
 * This service does not skip goals or replace dependency-resolution semantics.
 */
@Experimental
public interface M3BuildToolsService extends Service {
    String DEPENDENCY_SCHEMA = "M3JDK21_CLASS_DEPS_V1";
    String DEPENDENCY_PROVIDER = "m3-class-deps";

    /**
     * Request dependency facts from the selected original JDK provider.
     *
     * @param selectedJdkHome selected JDK installation directory
     * @param classFile path to the original class-file bytes
     * @param publicApiOnly whether to request only the public-API dependency scope
     */
    record Request(Path selectedJdkHome, Path classFile, boolean publicApiOnly) {
        public Request {
            selectedJdkHome = Objects.requireNonNull(selectedJdkHome, "selectedJdkHome");
            classFile = Objects.requireNonNull(classFile, "classFile");
        }
    }

    /**
     * Source-bound output of the original JDK class dependency engine.
     *
     * @param schema exact dependency-receipt schema identifier
     * @param scope class or public-API scope reported by the provider
     * @param classSha256 canonical SHA-256 of the original class-file bytes
     * @param text dependency receipt emitted by the original JDK provider
     */
    record Receipt(String schema, String scope, String classSha256, String text) {
        public Receipt {
            schema = Objects.requireNonNull(schema, "schema");
            scope = Objects.requireNonNull(scope, "scope");
            classSha256 = Objects.requireNonNull(classSha256, "classSha256");
            text = Objects.requireNonNull(text, "text");
            if (!DEPENDENCY_SCHEMA.equals(schema)
                    || !classSha256.matches("[0-9a-f]{64}")
                    || !(scope.equals("CLASS") || scope.equals("PUBLIC_API"))) {
                throw new IllegalArgumentException("foreign JDK build tool receipt");
            }
        }
    }

    /**
     * Returns an empty result when the selected compiler/toolchain is not the
     * current JVM's M3JDK21 or the native provider is not installed. A provider
     * failure propagates, never silently invoking a second classfile scanner.
     * Session-scoped cache hits are reauthenticated against the original bytes.
     */
    Optional<Receipt> dependencies(Session session, Request request) throws IOException;
}
