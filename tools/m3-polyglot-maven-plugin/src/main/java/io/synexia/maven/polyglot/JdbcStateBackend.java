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
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;

/** The explicit sqlite and h2 backends: an external JDBC engine, verified by reading back each publication. */
final class JdbcStateBackend implements ModuleStateBackend {
    private final M3ModuleJdbcStorage jdbc;

    JdbcStateBackend(Path root, String backend) throws IOException {
        jdbc = new M3ModuleJdbcStorage(root, backend);
    }

    @Override
    public byte[] read() throws IOException {
        return jdbc.read();
    }

    @Override
    public void write(byte[] image) throws IOException {
        jdbc.write(image);
        byte[] published = jdbc.read();
        if (published == null || !MessageDigest.isEqual(image, published)) {
            throw new IOException("module JDBC state publication mismatch");
        }
    }
}
