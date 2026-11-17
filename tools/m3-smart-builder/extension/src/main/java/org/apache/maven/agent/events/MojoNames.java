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

import java.util.Map;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;

/**
 * What a mojo is, from its identity only: its {@code prefix:goal} name as Maven prints it, the kind of failure it
 * stands for (compile, testCompile, test, integration-test, other) and the source of its diagnostics.
 */
final class MojoNames {
    private static final Map<String, String> SOURCES = Map.of("maven-compiler-plugin", "compiler",
            "maven-checkstyle-plugin", "checkstyle", "maven-pmd-plugin", "pmd", "spotbugs-maven-plugin", "spotbugs");

    private MojoNames() {}

    /** {@code prefix:goal}, such as {@code compiler:compile}; the artifactId when the prefix is unknown. */
    static String name(MojoExecution mojo) {
        String prefix = Optional.ofNullable(mojo.getMojoDescriptor()).map(MojoDescriptor::getPluginDescriptor)
                .map(PluginDescriptor::getGoalPrefix).filter(p -> !p.isEmpty()).orElse(mojo.getArtifactId());
        return prefix + ":" + mojo.getGoal();
    }

    /** The failure kind of the mojo. */
    static String kind(MojoExecution mojo) {
        String artifact = mojo.getArtifactId();
        String goal = mojo.getGoal();
        if ("maven-compiler-plugin".equals(artifact) && ("compile".equals(goal) || "testCompile".equals(goal))) {
            return goal;
        }
        if ("maven-surefire-plugin".equals(artifact) && "test".equals(goal)) {
            return "test";
        }
        boolean failsafe = "maven-failsafe-plugin".equals(artifact);
        return failsafe && ("integration-test".equals(goal) || "verify".equals(goal)) ? "integration-test" : "other";
    }

    /** The diagnostic source of the mojo's failure message: compiler, checkstyle, pmd, cpd, spotbugs or log. */
    static String source(MojoExecution mojo) {
        String source = SOURCES.getOrDefault(mojo.getArtifactId(), "log");
        return "pmd".equals(source) && mojo.getGoal().startsWith("cpd") ? "cpd" : source;
    }

    /** The {@code mojo} object of the mojo events: plugin {@code g:a:v}, goal, execution, phase. */
    static JsonText.Obj json(MojoExecution mojo) {
        return new JsonText.Obj().put("plugin", mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + mojo.getVersion())
                .put("goal", mojo.getGoal()).put("execution", mojo.getExecutionId())
                .put("phase", JsonText.orNull(mojo.getLifecyclePhase()));
    }
}
