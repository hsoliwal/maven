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
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.Source;
import org.apache.maven.api.services.Sources;
import org.apache.maven.api.spi.ModelParser;
import org.apache.maven.api.spi.ModelParserException;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.io.ModelReader;

/**
 * Lets Maven 3 {@link ModelProcessor} components contributed by core extensions locate and read project
 * models under Maven 4.
 * <p>
 * Maven 4 finds subprojects and reads models only through the {@link ModelParser} SPI. Extensions written
 * for Maven 3 replace the model processor instead: Tycho pomless ({@code tycho-build} with
 * {@code tycho-pomless}) contributes polyglot's {@code TeslaModelProcessor}, which generates the models of
 * Eclipse bundles, features, target platforms and aggregators from {@code META-INF/MANIFEST.MF},
 * {@code feature.xml}, {@code *.target} or {@code pom.tycho}. Without this bridge every pomless subproject
 * is reported as "Child subproject ... does not exist".
 * <p>
 * Only an extension processor's own file is claimed. When a processor answers with the default
 * {@code pom.xml} of the directory, the native Maven 4 reader stays in charge, so plain projects keep
 * every Maven 4 model feature. Maven's own {@link DefaultModelProcessor} is never consulted here.
 *
 * @since 4.2.0
 */
@Named(LegacyModelProcessorParser.ID)
@Singleton
public class LegacyModelProcessorParser implements ModelParser {

    /** The parser id reported in Maven 4 model diagnostics. */
    static final String ID = "legacy-model-processor";

    private static final String DEFAULT_POM = "pom.xml";

    private final List<ModelProcessor> processors;

    @Inject
    public LegacyModelProcessorParser(List<ModelProcessor> processors) {
        this.processors = Objects.requireNonNull(processors, "processors");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<Source> locate(Path dir) {
        return claim(dir).map(claim -> Sources.fromPath(claim.pom()));
    }

    @Override
    public Model parse(Source source, Map<String, ?> options) throws ModelParserException {
        Path pom = Objects.requireNonNull(source.getPath(), "source path");
        Claim claim = claim(pom.getParent())
                .filter(found -> found.pom().getFileName().equals(pom.getFileName()))
                .orElseThrow(() -> new ModelParserException("No Maven 3 model processor claims " + pom));
        return read(claim, options);
    }

    @Override
    public Optional<Model> locateAndParse(Path dir, Map<String, ?> options) throws ModelParserException {
        Optional<Claim> claim = claim(dir);
        return claim.isPresent() ? Optional.of(read(claim.get(), options)) : Optional.empty();
    }

    /**
     * The first extension processor that answers for {@code dir} with a file of its own in that directory.
     * The claimed file is resolved against {@code dir} as given, so callers comparing paths see their own form.
     */
    private Optional<Claim> claim(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        return processors.stream()
                .filter(processor -> !(processor instanceof DefaultModelProcessor))
                .map(processor -> new Claim(processor, located(processor, dir)))
                .filter(claim -> claim.pom() != null)
                .findFirst();
    }

    private static Path located(ModelProcessor processor, Path dir) {
        File pom = processor.locatePom(dir.toFile());
        if (pom == null || !pom.isFile()) {
            return null;
        }
        Path directory = dir.toAbsolutePath().normalize();
        Path path = pom.toPath().toAbsolutePath().normalize();
        boolean ownFile = directory.equals(path.getParent())
                && !DEFAULT_POM.equals(path.getFileName().toString());
        return ownFile ? dir.resolve(path.getFileName().toString()) : null;
    }

    private static Model read(Claim claim, Map<String, ?> options) throws ModelParserException {
        Map<String, Object> legacyOptions = new HashMap<>();
        legacyOptions.put(ModelProcessor.SOURCE, new FileModelSource(claim.pom().toFile()));
        legacyOptions.put(ModelReader.IS_STRICT, isStrict(options));
        try {
            return claim.processor().read(claim.pom().toFile(), legacyOptions).getDelegate();
        } catch (ModelParseException e) {
            throw new ModelParserException(failure(claim, e), e.getLineNumber(), e.getColumnNumber(), e);
        } catch (IOException | RuntimeException e) {
            throw new ModelParserException(failure(claim, e), e);
        }
    }

    private static String failure(Claim claim, Exception e) {
        return "Maven 3 model processor " + claim.processor().getClass().getName() + " could not read " + claim.pom()
                + ": " + e.getMessage();
    }

    private static boolean isStrict(Map<String, ?> options) {
        Object value = options != null ? options.get(ModelParser.STRICT) : null;
        return value == null || Boolean.parseBoolean(value.toString());
    }

    private record Claim(ModelProcessor processor, Path pom) {}
}
