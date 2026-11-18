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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * The property names a build reads by name, the allowlist of the properties a reproduce command repeats
 * ({@link ReproduceProperties}): what a mojo reads ({@code ${name}} in its descriptor's parameter expressions and
 * default values and in its execution configuration, which also holds the POM's configuration), what the reactor's
 * POM files read ({@code ${name}} anywhere: properties, configuration, versions; the raw files, before Maven
 * interpolated a user property into them) and the properties that activate a profile of those POMs or of the
 * settings. Reading is bounded: {@value #MAX_CHARS} characters per POM or configuration.
 */
final class PropertyReads {
    /** Characters of one POM file or one mojo configuration that are searched. */
    static final int MAX_CHARS = 1 << 20;
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]{1,200}+)\\}");
    private static final Pattern ACTIVATION =
            Pattern.compile("<property>\\s*+<name>\\s*+!?\\s*+([A-Za-z0-9_.-]{1,200}+)\\s*+</name>");

    private PropertyReads() {}

    /** The names {@code mojo} reads: its parameters' expressions and default values, and its configuration. */
    static Set<String> mojo(MojoExecution mojo) {
        List<Parameter> parameters = Optional.ofNullable(mojo.getMojoDescriptor()).map(MojoDescriptor::getParameters)
                .orElse(List.of());
        Stream<String> texts = parameters.stream().flatMap(p -> Stream.of(p.getExpression(), p.getDefaultValue()));
        return Stream.concat(texts, Stream.of(configuration(mojo))).filter(Objects::nonNull)
                .flatMap(PropertyReads::references).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The execution configuration as XML text; "" when this Maven gives none. */
    private static String configuration(MojoExecution mojo) {
        try {
            return JsonText.clip(String.valueOf(mojo.getConfiguration()), MAX_CHARS);
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    /** The names the build of {@code request} reads: its POM files' and its settings profiles'. */
    static Set<String> build(MavenExecutionRequest request, Optional<MavenSession> session) {
        Stream<Path> projects = session.map(PropertyReads::projects).orElse(List.of()).stream()
                .map(MavenProject::getFile).filter(Objects::nonNull).map(File::toPath);
        Stream<Path> poms = Stream.concat(Optional.ofNullable(request.getPom()).map(File::toPath).stream(), projects);
        Set<String> names = poms(poms);
        names.addAll(profiles(Optional.ofNullable(request.getProfiles()).orElse(List.of())));
        return names;
    }

    private static List<MavenProject> projects(MavenSession session) {
        return Optional.ofNullable(session.getAllProjects()).or(() -> Optional.ofNullable(session.getProjects()))
                .orElse(List.of());
    }

    /** {@code ${name}} references and profile activation properties in the POM files. */
    static Set<String> poms(Stream<Path> poms) {
        return poms.map(pom -> pom.toAbsolutePath().normalize()).distinct().map(PropertyReads::text)
                .flatMap(text -> Stream.concat(references(text), ACTIVATION.matcher(text).results()
                        .map(m -> m.group(1))))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The activation property names of {@code profiles} (a leading '!' removed). */
    static Set<String> profiles(List<Profile> profiles) {
        return profiles.stream().map(Profile::getActivation).filter(Objects::nonNull).map(Activation::getProperty)
                .filter(Objects::nonNull).map(ActivationProperty::getName).filter(Objects::nonNull)
                .map(name -> name.startsWith("!") ? name.substring(1) : name).collect(Collectors.toSet());
    }

    private static Stream<String> references(String text) {
        return REFERENCE.matcher(text).results().map(match -> match.group(1));
    }

    /** The first {@link #MAX_CHARS} bytes of a POM as text; "" when it cannot be read. */
    private static String text(Path pom) {
        try (InputStream in = Files.newInputStream(pom)) {
            return new String(in.readNBytes(MAX_CHARS), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException unreadable) {
            return "";
        }
    }
}
