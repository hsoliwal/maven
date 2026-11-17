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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** {@code LST_CENSUS.tsv}: one row per parsed source file, ordered by path, LF line ends, UTF-8. */
public final class LstCensus {
    /** The census header line. */
    public static final String HEADER = CensusRow.HEADER;

    private LstCensus() {}

    /**
     * @param rows the rows, in the order to write
     * @return the census text, header first, each line ending in LF
     */
    public static String tsv(List<CensusRow> rows) {
        return Stream.concat(Stream.of(HEADER), rows.stream().map(CensusRow::tsv))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * Writes the census, creating the parent directory.
     *
     * @param path the census file
     * @param rows the rows, in the order to write
     * @throws IOException if the file cannot be written
     */
    public static void write(Path path, List<CensusRow> rows) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(path, tsv(rows), StandardCharsets.UTF_8);
    }
}
