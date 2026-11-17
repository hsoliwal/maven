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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Profile;

/**
 * What every reproduce command of a build repeats from its command line, before {@code -pl}: {@code -f} with the POM
 * when the build did not run in the root with its {@code pom.xml}; {@code -P} with the active profiles that are not
 * defined in the settings (the settings activate their own; Maven 3.9 keeps no other record of which active profile
 * came from {@code -P}, and its session settings mirror the request) and the deactivated ones; {@code -D} with the
 * user properties. A user property is left out when a Maven configuration file sets it
 * ({@code maven-user.properties} or {@code maven.properties} of the installation's {@code conf/}, of {@code ~/.m2} or
 * of the project's {@code .mvn/}: Maven 4 copies their entries into the user properties, and a rerun reads them
 * again); when it is an agent setting ({@code maven.agent*}, {@code maven.events.*},
 * {@code style.color}, {@code maven.ext.class.path}, {@code chromellm.advice}) or a selection the reproduce command
 * sets itself ({@code test}, {@code it.test} and their {@code failIfNoSpecifiedTests}); when its name looks secret;
 * when its value holds a credential URL or a {@code password=}-like pair; when its value holds an absolute path
 * (machine-specific, and it names the user); and when some shell would change its value inside double quotes.
 */
final class ReproduceOptions {
    private static final Pattern OWN = Pattern.compile("maven\\.agent(\\..*)?|maven\\.events\\..*|style\\.color"
            + "|maven\\.ext\\.class\\.path|chromellm\\.advice|test|it\\.test|(surefire|failsafe)\\.failIfNoSpecifiedTests");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,200}+");
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(pass|pwd|secret|token|credential|cred|auth|key|login|session|cookie|private).*");
    private static final Pattern SECRET_VALUE =
            Pattern.compile("(?i)(password|passwd|pwd|token|secret|apikey|api_key|key)=");
    private static final Pattern ABSOLUTE = Pattern.compile("(?:^|[=,;])(?:[A-Za-z]:|~)?[/\\\\]");
    private static final Pattern PROFILE = Pattern.compile("-?[A-Za-z0-9_.-]{1,200}+");
    /** The files whose entries Maven 4 copies into the user properties, in each configuration directory. */
    private static final List<String> USER_PROPERTY_FILES = List.of("maven-user.properties", "maven.properties");

    private ReproduceOptions() {}

    /** The options of the build of {@code request}. */
    static List<String> of(MavenExecutionRequest request, Path root) {
        List<String> options = new ArrayList<>(pom(request, root));
        options.addAll(profiles(request.getActiveProfiles(), request.getInactiveProfiles(), request.getProfiles()
                .stream().map(Profile::getId).collect(Collectors.toList())));
        options.addAll(properties(request.getUserProperties(), configured(request, root)));
        return options;
    }

    /** The keys that Maven's configuration files set: the installation's, the user's and the project's. */
    static Set<String> configured(MavenExecutionRequest request, Path root) {
        Properties system = Optional.ofNullable(request.getSystemProperties()).orElseGet(Properties::new);
        Stream<Optional<Path>> dirs = Stream.of(property(system, "maven.home").map(home -> home.resolve("conf")),
                property(system, "user.home").map(home -> home.resolve(".m2")), Optional.of(root.resolve(".mvn")),
                property(system, "maven.multiModuleProjectDirectory").map(dir -> dir.resolve(".mvn")));
        return dirs.flatMap(Optional::stream).flatMap(dir -> USER_PROPERTY_FILES.stream().map(dir::resolve))
                .flatMap(ReproduceOptions::keys).collect(Collectors.toSet());
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

    /** {@code -Pa,-b}: activated profiles not in {@code settings}, then deactivated ones with '-'. */
    static List<String> profiles(List<String> active, List<String> inactive, Collection<String> settings) {
        List<String> ids = Stream.concat(active.stream().filter(id -> !settings.contains(id)),
                inactive.stream().map(id -> "-" + id)).filter(id -> PROFILE.matcher(id).matches()).distinct()
                .collect(Collectors.toList());
        return ids.isEmpty() ? List.of() : List.of("-P" + String.join(",", ids));
    }

    /** The repeated user properties as sorted {@code -Dname=value} arguments. */
    static List<String> properties(Properties user) {
        return properties(user, Set.of());
    }

    /** The repeated user properties, without those in {@code configured}, as sorted {@code -Dname=value} arguments. */
    static List<String> properties(Properties user, Set<String> configured) {
        return user.stringPropertyNames().stream().sorted().filter(name -> !configured.contains(name))
                .filter(name -> repeated(name, user.getProperty(name)))
                .map(name -> "-D" + name + "=" + user.getProperty(name)).collect(Collectors.toList());
    }

    /** Whether the property {@code name=value} is repeated (see the class comment). */
    static boolean repeated(String name, String value) {
        return NAME.matcher(name).matches() && !OWN.matcher(name).matches() && !SECRET_NAME.matcher(name).matches()
                && MavenCommands.quotable(value) && !SECRET_VALUE.matcher(value).find()
                && Redact.secrets(value).equals(value) && !ABSOLUTE.matcher(value).find();
    }

    private static Optional<Path> path(String text) {
        try {
            return Optional.of(Path.of(text).toAbsolutePath().normalize());
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
    }
}
