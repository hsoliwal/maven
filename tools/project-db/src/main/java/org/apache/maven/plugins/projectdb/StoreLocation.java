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
package org.apache.maven.plugins.projectdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Where a project's database lives: {@code target/project-db} under the project root by default (removed by
 * {@code clean}), {@code .mvn/project-db} when persistence across clean builds is asked for, or an explicit
 * directory. Every store directory carries a {@code .gitignore} of {@code *}, so it is never committed.
 */
public final class StoreLocation {
    static final String DEFAULT = "target/project-db";
    static final String PERSISTENT = ".mvn/project-db";

    private StoreLocation() {}

    public static Path resolve(Path root, String directory, boolean persistent) {
        if (directory != null && !directory.isBlank()) return root.resolve(directory).toAbsolutePath().normalize();
        return root.resolve(persistent ? PERSISTENT : DEFAULT).toAbsolutePath().normalize();
    }

    /** Writes {@code .gitignore} with {@code *} into the store directory unless one is already there. */
    static void guard(Path directory) throws IOException {
        Path ignore = directory.resolve(".gitignore");
        if (Files.isSymbolicLink(ignore)) throw new IOException("project database .gitignore is a symlink");
        if (!Files.exists(ignore, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(ignore, "# Project database: local build data, never committed\n*\n",
                    StandardCharsets.UTF_8);
        }
    }
}
