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

import java.util.List;
import java.util.Properties;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.DuplicateParameterException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;

/**
 * Command spellings: every {@code -D} argument double-quoted (Windows PowerShell 5.1 splits an unquoted
 * {@code -Dkey.with.dots=value} at the first dot), paths with spaces quoted, the test and integration-test argument
 * lists the MCP server shares, and a goal run from the command line reproduced by that goal with its properties.
 */
public final class MavenCommandsTestMain {
    private MavenCommandsTestMain() {}

    public static void main(String[] args) throws DuplicateParameterException {
        spellings();
        argumentLists();
        reproduce();
        Check.done("MavenCommandsTestMain");
    }

    private static void spellings() {
        Check.equal("-B -ntp -q \"-Dstyle.color=never\" \"-Dmaven.agent=true\"", MavenCommands.AGENT_OPTIONS, "agent");
        Check.equal("mvn -pl b -am \"-Dtest=fix.BTest#adds\" \"-Dsurefire.failIfNoSpecifiedTests=false\" test",
                MavenCommands.test("mvn", "b", "fix.BTest#adds"), "test");
        Check.equal("./mvnw -pl b -am \"-Dit.test=fix.BIT\" \"-Dfailsafe.failIfNoSpecifiedTests=false\""
                + " \"-Dtest=NoUnitTestSelected\" \"-Dsurefire.failIfNoSpecifiedTests=false\" verify",
                MavenCommands.integrationTest("./mvnw", "b", "fix.BIT"), "integration test");
        Check.equal("mvn -pl \"my mod\" -am compile", MavenCommands.module("mvn", "my mod", "compile"), "space");
        Check.equal("mvn -pl <path> -am compile", MavenCommands.module("mvn", "<path>", "compile"), "placeholder");
    }

    private static void argumentLists() {
        Check.equal(List.of("-Dtest=X#y", "-Dsurefire.failIfNoSpecifiedTests=false", "test"),
                MavenCommands.testArgs("X#y"), "test arguments");
        Check.equal(List.of("-Dit.test=X", "-Dfailsafe.failIfNoSpecifiedTests=false", "-Dtest=NoUnitTestSelected",
                "-Dsurefire.failIfNoSpecifiedTests=false", "verify"), MavenCommands.integrationTestArgs("X"), "it");
        Check.equal("mvn -o \"-Da=b c\" verify", MavenCommands.line("mvn", List.of("-o", "-Da=b c", "verify")), "line");
    }

    private static void reproduce() throws DuplicateParameterException {
        Properties user = new Properties();
        user.setProperty("agent.check", "true");
        user.setProperty("agent.token", "secret");
        user.setProperty("unrelated", "x");
        List<String> goal = MojoNames.cliGoal(llmsTxt("default-cli"), user);
        Check.equal(List.of("-Dagent.check=true", "org.apache.maven.plugins:maven-agent-plugin:0.1:llms-txt"), goal,
                "cli goal with its parameter properties, no secret-like name");
        Check.equal("mvn -pl . -am \"-Dagent.check=true\" org.apache.maven.plugins:maven-agent-plugin:0.1:llms-txt",
                ReproduceCommand.of("mvn", "other", ".", List.of(), List.of(), "", goal), "reproduce reruns the goal");
        Check.equal(List.of(), MojoNames.cliGoal(bound(llmsTxt("default"), "verify"), user), "lifecycle execution");
        Check.equal("mvn -pl . -am verify", ReproduceCommand.of("mvn", "other", ".", List.of(), List.of(), "verify",
                List.of()), "lifecycle reproduce");
        SurefireReports.TestCase nested = new SurefireReports.TestCase("fix.OuterTest$Inner", "m[1]", "failed", 1,
                "", "", "");
        Check.equal("fix.OuterTest*Inner#m", ReproduceCommand.selector(List.of(nested)), "nested class, no '$'");
    }

    private static MojoExecution llmsTxt(String executionId) throws DuplicateParameterException {
        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-agent-plugin");
        plugin.setVersion("0.1");
        plugin.setGoalPrefix("agent");
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setGoal("llms-txt");
        mojo.setPluginDescriptor(plugin);
        mojo.addParameter(parameter("check", "${agent.check}"));
        mojo.addParameter(parameter("token", "${agent.token}"));
        return new MojoExecution(mojo, executionId);
    }

    private static MojoExecution bound(MojoExecution mojo, String phase) {
        mojo.setLifecyclePhase(phase);
        return mojo;
    }

    private static Parameter parameter(String name, String expression) {
        Parameter parameter = new Parameter();
        parameter.setName(name);
        parameter.setExpression(expression);
        return parameter;
    }
}
