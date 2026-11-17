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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Maven command lines agents are told to run: the one owner of their spelling, used by the failure digest's
 * reproduce commands and by the project description and {@code llms.txt} of maven-agent-plugin.
 */
public final class MavenCommands {
    /** Options of an agent build: batch, no transfer progress, quiet, no colours, agent files on. */
    public static final String AGENT_OPTIONS = "-B -ntp -q -Dstyle.color=never -Dmaven.agent=true";

    private MavenCommands() {}

    /** {@code ./mvnw} when the root has the Maven Wrapper, else {@code mvn}. */
    public static String launcher(Path root) {
        return Files.isRegularFile(root.resolve("mvnw")) ? "./mvnw" : "mvn";
    }

    /** The whole build. */
    public static String build(String launcher) {
        return launcher + " verify";
    }

    /** The whole build in agent mode: the summary on standard output, the files in {@code target/agent}. */
    public static String agent(String launcher) {
        return launcher + " " + AGENT_OPTIONS + " verify";
    }

    /** One module with the modules it needs, up to {@code phase}. */
    public static String module(String launcher, String path, String phase) {
        return launcher + " -pl " + path + " -am " + phase;
    }

    /** Selected tests ({@code Class#method}, comma-separated) of one module with Surefire. */
    public static String test(String launcher, String path, String selector) {
        return launcher + " -pl " + path + " -am -Dtest=" + selector + " -Dsurefire.failIfNoSpecifiedTests=false test";
    }

    /** Selected integration tests of one module with Failsafe. */
    public static String integrationTest(String launcher, String path, String selector) {
        return launcher + " -pl " + path + " -am -Dit.test=" + selector
                + " -Dfailsafe.failIfNoSpecifiedTests=false verify";
    }
}
