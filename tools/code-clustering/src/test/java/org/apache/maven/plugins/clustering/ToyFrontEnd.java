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
package org.apache.maven.plugins.clustering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;

/**
 * A front end registered through {@code META-INF/services} for {@code .toy} files. Each line
 * {@code fn <name> <param> = <expression>} is one METHOD atom: the name and the parameter are declared, the expression
 * words are names or number literals. It stands in for an adapter over a real language core.
 */
public final class ToyFrontEnd implements AtomFrontEnd {
    @Override public String name() {
        return "toy";
    }

    @Override public boolean accepts(Path file) {
        return file.getFileName().toString().endsWith(".toy");
    }

    @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
        List<Atom> atoms = new ArrayList<>();
        for (Path file : files) {
            String path = root.relativize(file).toString().replace('\\', '/');
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] w = line.trim().split("\\s+");
                if (w.length < 5 || !w[0].equals("fn")) continue;
                AtomBuilder b = AtomBuilder.atom(path, "METHOD", w[1] + "(1)", line).declare(w[1]).declare(w[2]);
                b.node("FN").name("NAME", w[1]).name("PARAM", w[2]);
                for (int i = 4; i < w.length; i++) {
                    if (w[i].matches("\\d+")) b.literal("NUM", w[i]);
                    else b.name("WORD", w[i]);
                }
                atoms.add(b.build());
            }
        }
        return atoms;
    }
}
