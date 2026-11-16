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
package org.apache.maven.api.services;

import java.util.List;
import java.util.Objects;

import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Evaluates explicit, side-effect-free build policies using the owning Maven session.
 *
 * <p>This is an additive native API for reusable policy checks, not an automatic
 * replacement for the Maven Enforcer plugin. It does not bind a lifecycle phase,
 * suppress existing Mojo execution, change a POM or resolve dependencies itself.
 * Callers decide whether to fail a build based on {@link Report#accepted()}.
 *
 * <p>Minimum Maven requirements use Maven's own version parser: an unbracketed
 * version means "at least this version" (as in Enforcer); a bracketed range
 * uses Maven range syntax. Java feature requirements apply to the JVM running
 * Maven, not to a separately selected compilation toolchain.
 */
@Experimental
public interface BuildPolicyService extends Service {
    enum Severity {
        ERROR,
        WARN
    }

    /** Only explicitly requested policies are evaluated. */
    sealed interface Rule permits MavenVersion, JavaFeature, NoSnapshotDependencies {
        Severity severity();
    }

    /** Minimum or range requirement for the Maven runtime version. */
    record MavenVersion(String requirement, Severity severity) implements Rule {
        public MavenVersion {
            Objects.requireNonNull(requirement, "requirement");
            severity = Objects.requireNonNull(severity, "severity");
            if (requirement.isBlank() || !requirement.equals(requirement.trim()) || requirement.length() > 256) {
                throw new IllegalArgumentException("invalid Maven version requirement");
            }
        }
    }

    /** Minimum Java feature version of the JVM executing Maven, e.g. 21. */
    record JavaFeature(int minimum, Severity severity) implements Rule {
        public JavaFeature {
            if (minimum < 1) {
                throw new IllegalArgumentException("minimum Java feature must be positive");
            }
            severity = Objects.requireNonNull(severity, "severity");
        }
    }

    /** Reject resolved, selected dependencies with snapshot versions. */
    record NoSnapshotDependencies(Severity severity) implements Rule {
        public NoSnapshotDependencies {
            severity = Objects.requireNonNull(severity, "severity");
        }
    }

    /**
     * @param session owner of the Maven runtime version
     * @param javaFeature feature version of the JVM executing Maven
     * @param dependencies an original, complete dependency resolution result,
     *        or null when no dependency result has been supplied. A snapshot
     *        policy never interprets missing or failed resolution as success
     * @param rules ordered explicit policies to evaluate
     */
    record Request(Session session, int javaFeature, DependencyResolverResult dependencies, List<Rule> rules) {
        public Request {
            session = Objects.requireNonNull(session, "session");
            if (javaFeature < 1) {
                throw new IllegalArgumentException("javaFeature must be positive");
            }
            rules = List.copyOf(rules);
        }

        /** Captures the current Maven JVM feature without probing any toolchain. */
        public static Request currentJvm(Session session, DependencyResolverResult dependencies, List<Rule> rules) {
            return new Request(session, Runtime.version().feature(), dependencies, rules);
        }
    }

    /** A reproducible violation or a deliberately incomplete check. */
    record Finding(String rule, Severity severity, String subject, String message) {
        public Finding {
            rule = Objects.requireNonNull(rule, "rule");
            severity = Objects.requireNonNull(severity, "severity");
            subject = Objects.requireNonNull(subject, "subject");
            message = Objects.requireNonNull(message, "message");
        }
    }

    /** No findings means all requested checks were evaluated and passed. */
    record Report(List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }

        /** WARN findings do not fail a build; ERROR findings do. */
        public boolean accepted() {
            return findings.stream().noneMatch(finding -> finding.severity() == Severity.ERROR);
        }
    }

    /** Evaluate requested rules without mutating the session or executing Mojos. */
    Report evaluate(Request request);
}
