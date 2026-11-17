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
package org.apache.maven.internal.impl;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;

import org.apache.maven.api.services.Source;
import org.apache.maven.api.services.Sources;
import org.apache.maven.api.spi.ModelParser;
import org.apache.maven.api.spi.ModelParserException;
import org.apache.maven.impl.DefaultModelXmlFactory;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.io.ModelParseException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.testing.PlexusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Tycho-pomless-like Maven 3 model processor (a core extension in real builds) must make pomless
 * Eclipse bundles visible to the Maven 4 model processor, which only consults {@link ModelParser}s.
 */
@PlexusTest
class LegacyModelProcessorParserTest {

    private static final String POLYGLOT = ".polyglot.META-INF";

    @Inject
    PlexusContainer container;

    @TempDir
    Path work;

    private final LegacyModelProcessorParser parser =
            new LegacyModelProcessorParser(List.of(new DefaultModelProcessor(), new PomlessProcessor()));

    @Test
    void pomlessBundleIsLocatedThroughTheExtensionProcessor() throws IOException {
        Path bundle = bundle("org.example.bundle");

        Optional<Source> located = parser.locate(bundle);

        assertEquals(Optional.of(bundle.resolve(POLYGLOT)), located.map(Source::getPath));
    }

    @Test
    void plainProjectsAndOtherPathsStayWithTheNativeReader() throws IOException {
        Path plain = Files.createDirectories(work.resolve("plain"));
        Files.writeString(plain.resolve("pom.xml"), "<project/>");
        Path empty = Files.createDirectories(work.resolve("empty"));

        assertEquals(Optional.empty(), parser.locate(plain));
        assertEquals(Optional.empty(), parser.locate(empty));
        assertEquals(Optional.empty(), parser.locate(plain.resolve("pom.xml")));
    }

    @Test
    void maven4ModelProcessorFindsAndReadsThePomlessSubproject() throws IOException {
        Path bundle = bundle("org.example.bundle");
        var processor =
                new org.apache.maven.impl.model.DefaultModelProcessor(new DefaultModelXmlFactory(), List.of(parser));

        Path pom = processor.locateExistingPom(bundle);
        org.apache.maven.api.model.Model model = processor.read(Sources.buildSource(pom), Map.of());

        assertEquals(bundle.resolve(POLYGLOT), pom);
        assertEquals("org.example.bundle", model.getArtifactId());
        assertEquals("eclipse-plugin", model.getPackaging());
        assertEquals(pom, model.getPomFile());
    }

    @Test
    void parseFailuresKeepTheirPosition() throws IOException {
        Path bundle = bundle(PomlessProcessor.BROKEN);

        ModelParserException failure =
                assertThrows(ModelParserException.class, () -> parser.locateAndParse(bundle, Map.of()));

        assertEquals(PomlessProcessor.BROKEN_LINE, failure.getLineNumber());
        assertTrue(failure.getMessage().contains(PomlessProcessor.class.getName()), failure.getMessage());
    }

    @Test
    void theBridgeIsAContainerComponent() throws Exception {
        assertTrue(container.lookupList(ModelParser.class).stream()
                .anyMatch(LegacyModelProcessorParser.class::isInstance));
        assertTrue(container.lookupMap(ModelParser.class).containsKey(LegacyModelProcessorParser.ID));
    }

    private Path bundle(String symbolicName) throws IOException {
        Path bundle = Files.createDirectories(work.resolve(symbolicName).resolve("META-INF"));
        Files.writeString(
                bundle.resolve("MANIFEST.MF"), "Manifest-Version: 1.0\nBundle-SymbolicName: " + symbolicName + "\n");
        return bundle.getParent();
    }

    /** Behaves like polyglot's TeslaModelProcessor with Tycho's bundle mapping, reduced to the contract. */
    static final class PomlessProcessor implements ModelProcessor {
        static final String BROKEN = "org.example.broken";
        static final int BROKEN_LINE = 2;

        @Override
        public File locatePom(File dir) {
            File pom = new File(dir, "pom.xml");
            if (pom.isFile() || !new File(dir, "META-INF/MANIFEST.MF").isFile()) {
                return pom;
            }
            File polyglot = new File(dir, POLYGLOT);
            try {
                Files.write(polyglot.toPath(), new byte[0]);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return polyglot;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Model read(File input, Map<String, ?> options) throws IOException {
            File dir = new File(((ModelSource) options.get(ModelProcessor.SOURCE)).getLocation()).getParentFile();
            String symbolicName = symbolicName(dir);
            if (BROKEN.equals(symbolicName)) {
                throw new ModelParseException("broken manifest", BROKEN_LINE, 1);
            }
            ((Map) options).put(ModelProcessor.SOURCE, new FileModelSource(new File(dir, "META-INF")));
            Model model = new Model();
            model.setModelVersion("4.0.0");
            model.setGroupId("org.example.pomless");
            model.setArtifactId(symbolicName);
            model.setVersion("1.0.0-SNAPSHOT");
            model.setPackaging("eclipse-plugin");
            return model;
        }

        private static String symbolicName(File dir) throws IOException {
            try (InputStream in = Files.newInputStream(dir.toPath().resolve("META-INF/MANIFEST.MF"))) {
                return new Manifest(in).getMainAttributes().getValue("Bundle-SymbolicName");
            }
        }

        @Override
        public Model read(Reader input, Map<String, ?> options) {
            throw new UnsupportedOperationException("pomless models are read from files");
        }

        @Override
        public Model read(InputStream input, Map<String, ?> options) {
            throw new UnsupportedOperationException("pomless models are read from files");
        }
    }
}
