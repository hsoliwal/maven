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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Profile;

/**
 * What every reproduce command of a build repeats from its command line, before its user properties
 * ({@link ReproduceProperties}) and {@code -pl}: {@code -f} with the POM when the build did not run in the root with
 * its {@code pom.xml}; {@code -P} with the active profiles that are not defined in the settings (the settings activate
 * their own; Maven 3.9 keeps no other record of which active profile came from {@code -P}, and its session settings
 * mirror the request) and the deactivated ones. On Maven 4 every id is written optional ({@code ?id}, {@code -?id}):
 * Maven 4 stops with a missing-profile error when no project of the {@code -pl}/{@code -am} selection defines a
 * required id, and a profile of another module is the common case; Maven 3 reads {@code ?} as part of the id, so it
 * gets the plain ids (a missing profile is only a warning there). Also the keys Maven's configuration files set, which
 * a rerun reads again and the command does not repeat.
 */
final class ReproduceOptions {
    private static final Pattern PROFILE = Pattern.compile("-?[A-Za-z0-9_.-]{1,200}+");
    private static final Pattern MAJOR = Pattern.compile("(\\d{1,4}+)\\..*");
    /** The files whose entries Maven 4 copies into the user properties, in each configuration directory. */
    private static final List<String> USER_PROPERTY_FILES = List.of("maven-user.properties", "maven.properties");
    /** A {@code -Dname} or {@code -Dname=value} token of {@code .mvn/maven.config}. */
    private static final Pattern CONFIG_PROPERTY = Pattern.compile("-D([A-Za-z0-9_.-]{1,200}+)(?:=.*+)?");

    private ReproduceOptions() {}

    /** The options of the build of {@code request}: {@code -f} and {@code -P}. */
    static List<String> of(MavenExecutionRequest request, Path root) {
        List<String> options = new ArrayList<>(pom(request, root));
        options.addAll(profiles(request.getActiveProfiles(), request.getInactiveProfiles(), request.getProfiles()
                .stream().map(Profile::getId).collect(Collectors.toList()), maven4(version(request))));
        return options;
    }

    /** The running Maven's version, from the request's system properties; "" when unknown. */
    static String version(MavenExecutionRequest request) {
        return Optional.ofNullable(request.getSystemProperties()).map(system -> system.getProperty("maven.version"))
                .orElse("");
    }

    /** Whether {@code version} is Maven 4 or later. */
    static boolean maven4(String version) {
        Matcher major = MAJOR.matcher(version);
        return major.matches() && Integer.parseInt(major.group(1)) >= 4;
    }

    /**
     * The keys that Maven's configuration sets, which a rerun reads again: {@code maven-user.properties} and
     * {@code maven.properties} of the installation, the user and the project, and the {@code -D} options of the
     * project's {@code .mvn/maven.config} and of the {@code MAVEN_ARGS} environment variable.
     */
    static Set<String> configured(MavenExecutionRequest request, Path root) {
        Properties system = Optional.ofNullable(request.getSystemProperties()).orElseGet(Properties::new);
        List<Path> projectDirs = Stream.of(Optional.of(root.resolve(".mvn")), property(system,
                "maven.multiModuleProjectDirectory").map(dir -> dir.resolve(".mvn"))).flatMap(Optional::stream)
                .distinct().collect(Collectors.toList());
        Stream<Path> dirs = Stream.concat(Stream.of(property(system, "maven.home").map(home -> home.resolve("conf")),
                property(system, "user.home").map(home -> home.resolve(".m2"))).flatMap(Optional::stream),
                projectDirs.stream());
        Set<String> keys = dirs.flatMap(dir -> USER_PROPERTY_FILES.stream().map(dir::resolve))
                .flatMap(ReproduceOptions::keys).collect(Collectors.toCollection(HashSet::new));
        projectDirs.forEach(dir -> keys.addAll(configKeys(dir.resolve("maven.config"))));
        keys.addAll(optionKeys(system.getProperty("env.MAVEN_ARGS", "")));
        return keys;
    }

    /** The {@code -D} keys of a {@code maven.config} file; none when it is absent or unreadable. */
    private static Set<String> configKeys(Path file) {
        try {
            return optionKeys(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException absent) {
            return Set.of();
        }
    }

    /** The {@code -D} keys of white-space separated options. */
    private static Set<String> optionKeys(String options) {
        return Arrays.stream(options.split("\\s++"))
                .map(CONFIG_PROPERTY::matcher).filter(Matcher::matches).map(m -> m.group(1))
                .collect(Collectors.toSet());
    }

    private static Optional<Path> property(Properties system, String key) {
        return Optional.ofNullable(system.getProperty(key)).flatMap(ReproduceOptions::path);
    }

    /** The keys of a properties file; none when it is absent or unreadable. */
    private static Stream<String> keys(Path file) {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
            return properties.stringPropertyNames().stream();
        } catch (IOException | IllegalArgumentException absent) {
            return Stream.empty();
        }
    }

    /** The directory the build ran in ({@code user.dir} of the request), when known. */
    static Optional<Path> workingDirectory(MavenExecutionRequest request) {
        return Optional.ofNullable(request.getSystemProperties()).map(system -> system.getProperty("user.dir"))
                .flatMap(ReproduceOptions::path);
    }

    /** {@code -f <pom>}, relative to the working directory, when the build ran elsewhere or with another file name. */
    static List<String> pom(MavenExecutionRequest request, Path root) {
        Path pom = Optional.ofNullable(request.getPom()).map(File::toPath).map(p -> p.toAbsolutePath().normalize())
                .orElse(root.resolve("pom.xml"));
        Optional<Path> dir = workingDirectory(request).filter(d -> !(d.equals(root) && pom.equals(root.resolve("pom.xml"))));
        Optional<String> relative = dir.filter(d -> Objects.equals(d.getRoot(), pom.getRoot()))
                .map(d -> d.relativize(pom).toString().replace('\\', '/')).filter(MavenCommands::quotable);
        return relative.map(text -> List.of("-f", text)).orElse(List.of());
    }

    /**
     * {@code -Pa,-b}: activated profiles not in {@code settings}, then deactivated ones with '-'; with
     * {@code optional} (Maven 4) {@code -P?a,-?b}.
     */
    static List<String> profiles(List<String> active, List<String> inactive, Collection<String> settings,
            boolean optional) {
        String mark = optional ? "?" : "";
        List<String> ids = Stream.concat(active.stream().filter(id -> !settings.contains(id))
                .map(id -> mark + bare(id)), inactive.stream().map(id -> "-" + mark + bare(id)))
                .filter(id -> PROFILE.matcher(id.replace("?", "")).matches()).distinct().collect(Collectors.toList());
        return ids.isEmpty() ? List.of() : List.of("-P" + String.join(",", ids));
    }

    /** A profile id without the optional marker a Maven 4 command line may give it. */
    private static String bare(String id) {
        return id.startsWith("?") ? id.substring(1) : id;
    }

    private static Optional<Path> path(String text) {
        try {
            return Optional.of(Path.of(text).toAbsolutePath().normalize());
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
    }
}
