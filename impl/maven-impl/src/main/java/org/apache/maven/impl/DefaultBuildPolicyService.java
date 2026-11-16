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
package org.apache.maven.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.Version;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.BuildPolicyService;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.VersionParser;

/** Native Maven policy evaluator; dependency facts remain owned by Maven Resolver. */
@Named
@Singleton
public final class DefaultBuildPolicyService implements BuildPolicyService {
    private final VersionParser versionParser;

    @Inject
    public DefaultBuildPolicyService(VersionParser versionParser) {
        this.versionParser = Objects.requireNonNull(versionParser, "versionParser");
    }

    @Override
    public Report evaluate(Request request) {
        Objects.requireNonNull(request, "request");
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : request.rules()) {
            if (rule instanceof MavenVersion version) {
                checkMavenVersion(request, version, findings);
            } else if (rule instanceof JavaFeature java) {
                if (request.javaFeature() < java.minimum()) {
                    findings.add(new Finding(
                            "requireJavaVersion",
                            java.severity(),
                            "runtime-jvm",
                            "Maven JVM Java feature " + request.javaFeature() + " is below " + java.minimum()));
                }
            } else if (rule instanceof NoSnapshotDependencies snapshots) {
                checkSnapshots(request.dependencies(), snapshots, findings);
            } else {
                throw new IllegalArgumentException("unsupported policy: " + rule);
            }
        }
        return new Report(findings);
    }

    private void checkMavenVersion(Request request, MavenVersion rule, List<Finding> findings) {
        Version actual = Objects.requireNonNull(request.session().getMavenVersion(), "session Maven version");
        String required = rule.requirement();
        boolean matched;
        if (required.charAt(0) == '[' || required.charAt(0) == '(') {
            matched = versionParser.parseVersionRange(required).contains(actual);
        } else {
            matched = actual.compareTo(versionParser.parseVersion(required)) >= 0;
        }
        if (!matched) {
            findings.add(new Finding(
                    "requireMavenVersion",
                    rule.severity(),
                    "maven-runtime",
                    "Maven version " + actual + " does not satisfy " + required));
        }
    }

    private static void checkSnapshots(
            DependencyResolverResult resolved, NoSnapshotDependencies rule, List<Finding> findings) {
        if (resolved == null
                || !resolved.getExceptions().isEmpty()
                || resolved.getRoot() == null
                || resolved.getRequest() == null
                || resolved.getRequest().getRequestType() != DependencyResolverRequest.RequestType.RESOLVE) {
            findings.add(new Finding(
                    "banSnapshotDependencies",
                    rule.severity(),
                    "dependency-resolution",
                    "Snapshot policy not evaluated: successful RESOLVE dependency result is unavailable"));
            return;
        }
        // Resolver supplies the selected dependency set; don't mistake an omitted
        // conflict version for a selected dependency, and don't re-resolve it.
        List<Dependency> selected = new ArrayList<>(resolved.getDependencies().keySet());
        selected.sort(Comparator.comparing(Dependency::key));
        for (Dependency dependency : selected) {
            if (dependency.snapshot()) {
                findings.add(new Finding(
                        "banSnapshotDependencies",
                        rule.severity(),
                        dependency.key(),
                        "Selected dependency uses a snapshot version"));
            }
        }
    }
}
