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
package org.apache.maven.plugins.nativebuild;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugins.nativebuild.engine.OwnerCheck;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The polyglot manifests of a module, read from the effective model the way the polyglot plugin's run and plan goals
 * read their {@code manifest} parameter: the {@code <manifest>} of the plugin and of each execution, else the
 * {@code m3.polyglot.manifest} property (user, system, then project properties), else
 * {@code ${project.basedir}/m3-build.tsv} (see {@link OwnerCheck#manifests}).
 */
final class PolyglotManifests {
    /** The polyglot plugin's coordinates. */
    static final String GROUP = "io.synexia";
    static final String ARTIFACT = "m3-polyglot-maven-plugin";
    /** The property behind the polyglot {@code manifest} parameter. */
    static final String PROPERTY = "m3.polyglot.manifest";

    private PolyglotManifests() {}

    /** Every manifest the polyglot plugin may run for the mojo's module. */
    static List<Path> of(AbstractNativeMojo m) {
        List<String> configured = m.project.getBuildPlugins().stream().filter(PolyglotManifests::polyglot)
                .flatMap(p -> Stream.concat(Stream.of(p.getConfiguration()),
                        p.getExecutions().stream().map(PluginExecution::getConfiguration)))
                .map(PolyglotManifests::manifest).flatMap(Optional::stream).toList();
        return OwnerCheck.manifests(m.project.getBasedir().toPath(), configured, property(m));
    }

    private static boolean polyglot(Plugin plugin) {
        return GROUP.equals(plugin.getGroupId()) && ARTIFACT.equals(plugin.getArtifactId());
    }

    private static Optional<String> manifest(Object configuration) {
        Xpp3Dom child = configuration instanceof Xpp3Dom dom ? dom.getChild("manifest") : null;
        return Optional.ofNullable(child).map(Xpp3Dom::getValue).filter(v -> !v.isBlank() && !v.contains("${"));
    }

    private static String property(AbstractNativeMojo m) {
        Stream<Properties> sources = m.session == null ? Stream.of(m.project.getProperties())
                : Stream.of(m.session.getUserProperties(), m.session.getSystemProperties(), m.project.getProperties());
        return sources.filter(p -> p != null && p.getProperty(PROPERTY) != null).map(p -> p.getProperty(PROPERTY))
                .findFirst().orElse(null);
    }
}
