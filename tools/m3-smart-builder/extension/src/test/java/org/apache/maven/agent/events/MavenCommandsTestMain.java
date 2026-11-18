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
import java.util.Set;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.DuplicateParameterException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;

/**
 * Command spellings: every {@code -D} argument double-quoted (Windows PowerShell 5.1 splits an unquoted
 * {@code -Dkey.with.dots=value} at the first dot) and so is every {@code -P} argument, paths with spaces quoted, the
 * test and integration-test argument lists the MCP server shares, the launcher per shell, a goal run from the command
 * line reproduced by that goal with the build's options, and which user properties a reproduce command repeats.
 */
public final class MavenCommandsTestMain {
    private MavenCommandsTestMain() {}

    public static void main(String[] args) throws DuplicateParameterException {
        spellings();
        quoting();
        argumentLists();
        selection();
        goalReproduce();
        profiles();
        values();
        skipFlags();
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
        Check.equal("mvn -pl \"<path>\" -am compile", MavenCommands.module("mvn", "<path>", "compile"), "placeholder");
    }

    /** Arguments with a redirection, glob, brace, comma, leading '~', '#' or '@' are quoted; typographic quotes refused. */
    private static void quoting() {
        Check.equal("mvn -f \"out>dir/pom.xml\" -pl \"mod*ule\" -am compile", MavenCommands.line("mvn", List.of("-f",
                "out>dir/pom.xml", "-pl", "mod*ule", "-am", "compile")), "redirect and glob characters quoted");
        List.of("a?b", "[a]", "{a,b}", "~x", "#x", "@x", "a,b", "a<b", "a b", "a&b", "a|b", "a;b", "a(b)", "a^b", "")
                .forEach(arg -> Check.equal("mvn \"" + arg + "\"", MavenCommands.line("mvn", List.of(arg)), "quoted " + arg));
        Check.equal("mvn a/b.c:d@e+f=g#h-i -am", MavenCommands.line("mvn", List.of("a/b.c:d@e+f=g#h-i", "-am")), "bare");
        Check.that(!MavenCommands.quotable("a\u201D;Write-Output x;mvn \u201Cx") && !MavenCommands.quotable("a\u2018b")
                && !MavenCommands.quotable("\u2013Dx") && !MavenCommands.quotable("a\u2014b"),
                "PowerShell's typographic quotes and dashes are not quotable");
    }

    private static void argumentLists() {
        Check.equal(List.of("-Dtest=X#y", "-Dsurefire.failIfNoSpecifiedTests=false", "test"),
                MavenCommands.testArgs("X#y"), "test arguments");
        Check.equal(List.of("-Dit.test=X", "-Dfailsafe.failIfNoSpecifiedTests=false", "-Dtest=NoUnitTestSelected",
                "-Dsurefire.failIfNoSpecifiedTests=false", "verify"), MavenCommands.integrationTestArgs("X"), "it");
        Check.equal("mvn -o \"-Da=b c\" \"-Pa,-b\" verify", MavenCommands.line("mvn", List.of("-o", "-Da=b c", "-Pa,-b",
                "verify")), "line");
        Check.equal(List.of("./mvnw", ".\\mvnw.cmd", ".\\mvnw.cmd"), launchers(MavenCommands.Launchers.of("./mvnw")),
                "the wrapper per shell");
        Check.equal(List.of("mvn", "mvn", "mvn"), launchers(MavenCommands.Launchers.of("mvn")), "mvn everywhere");
        Check.that(MavenCommands.quotable("fix.BTest#adds+x") && !MavenCommands.quotable("a$b")
                && !MavenCommands.quotable("a\\b") && !MavenCommands.quotable("%PATH%"), "quotable");
    }

    private static List<String> launchers(MavenCommands.Launchers launchers) {
        return List.of(launchers.sh(), launchers.powershell(), launchers.cmd());
    }

    /** Of the user properties, those the mojo or the build reads, not secret, not the agent's own; the rest counted. */
    private static void selection() throws DuplicateParameterException {
        Properties user = new Properties();
        user.setProperty("agent.check", "true");
        user.setProperty("agent.token", "secret");
        user.setProperty("maven.agent", "true");
        user.setProperty("unrelated", "x");
        user.setProperty("pom.read", "y");
        MojoExecution llmsTxt = llmsTxt("default-cli");
        Check.equal(Set.of("agent.check", "agent.token"), PropertyReads.mojo(llmsTxt), "what the mojo reads");
        ReproduceProperties properties = ReproduceProperties.of(user, Set.of(), Set.of("pom.read"));
        ReproduceProperties.Selection selection = properties.select(PropertyReads.mojo(llmsTxt));
        Check.equal(List.of("-Dagent.check=true", "-Dpom.read=y"), selection.args(), "read, not secret, no agent key");
        Check.equal(2, selection.omitted(), "the token and the unread property are counted, the agent key is not");
    }

    /** A goal run from the command line reruns itself with the properties it reads. */
    private static void goalReproduce() throws DuplicateParameterException {
        MojoExecution llmsTxt = llmsTxt("default-cli");
        Properties user = new Properties();
        user.setProperty("agent.check", "true");
        user.setProperty("pom.read", "y");
        user.setProperty("unrelated", "x");
        ReproduceProperties properties = ReproduceProperties.of(user, Set.of(), Set.of("pom.read"));
        String goal = MojoNames.cliGoal(llmsTxt);
        Check.equal("org.apache.maven.plugins:maven-agent-plugin:0.1:llms-txt", goal, "cli goal");
        ReproduceCommand.Build build = new ReproduceCommand.Build("mvn", List.of(), List.of("agent:llms-txt"), properties);
        ReproduceCommand.Reproduce reproduce = ReproduceCommand.reproduce(build, new ReproduceCommand.Failed("other",
                ".", List.of(), List.of(), "", goal, PropertyReads.mojo(llmsTxt)));
        Check.equal("mvn \"-Dagent.check=true\" \"-Dpom.read=y\" -pl . -am org.apache.maven.plugins:"
                + "maven-agent-plugin:0.1:llms-txt", reproduce.command(), "reproduce reruns the goal with what it reads");
        Check.equal(1, reproduce.omitted(), "and counts the rest");
        Check.equal("", MojoNames.cliGoal(bound(llmsTxt("default"), "verify")), "lifecycle execution");
    }

    /** Profiles of the command line: plain ids on Maven 3, optional ids on Maven 4; a lifecycle reproduce. */
    private static void profiles() {
        Check.equal("mvn -pl . -am verify", ReproduceCommand.of(new ReproduceCommand.Build("mvn", List.of(),
                List.of("clean", "install")), new ReproduceCommand.Failed("other", ".", List.of(), List.of(), "verify",
                "")), "lifecycle reproduce");
        Check.equal(List.of("-Pstrict,-slow"), ReproduceOptions.profiles(List.of("strict", "env"), List.of("slow"),
                List.of("env"), false), "active profiles not defined in the settings, and the deactivated ones");
        Check.equal(List.of("-P?strict,-?slow"), ReproduceOptions.profiles(List.of("strict", "env"), List.of("slow"),
                List.of("env"), true), "optional ids on Maven 4");
        Check.that(ReproduceOptions.maven4("4.0.0-rc-7") && ReproduceOptions.maven4("4.2.0-SNAPSHOT")
                && ReproduceOptions.maven4("10.0") && !ReproduceOptions.maven4("3.9.14") && !ReproduceOptions.maven4(""),
                "Maven 4 from the running version");
        SurefireReports.TestCase nested = new SurefireReports.TestCase("fix.OuterTest$Inner", "m[1]", "failed", 1,
                "", "", "");
        Check.equal("fix.OuterTest*Inner#m", ReproduceCommand.selector(List.of(nested)), "nested class, no '$'");
    }

    /** Which read properties are still left out: secret names, token- or webhook-shaped values, absolute paths. */
    private static void values() {
        Check.that(!ReproduceProperties.safe("x", "C:/abs") && !ReproduceProperties.safe("x", "/abs")
                && !ReproduceProperties.safe("x", "~/abs") && !ReproduceProperties.safe("x", "a=/abs")
                && ReproduceProperties.safe("x", "https://host/repo") && ReproduceProperties.safe("x", "conf/x.xml"),
                "absolute paths left out, URLs and relative paths kept");
        List.of("nexus.pw", "db.pin", "api.bearer", "my.jwt", "deploy.signature", "hook.sig", "app.salt", "mfa.otp",
                "slack.webhook", "sentry.dsn", "aws.secretKey", "github.token", "db.password", "auth", "x.hmac")
                .forEach(name -> Check.that(!ReproduceProperties.safe(name, "v"), "secret name " + name));
        List.of("ghp_0123456789abcdefABCDEF0123456789abcd", "github_pat_11ABC", "glpat-abc123", "xoxb-1-2", "sk-abc123",
                "AKIAIOSFODNN7EXAMPLE", "ASIAIOSFODNN7EXAMPLE", "eyJhbGciOiJIUzI1NiJ9.e30.x",
                "https://hooks.slack.com/services/T0/B0/X", "https://discord.com/api/webhooks/1/x",
                "https://acme.webhook.office.com/x", "https://h/x?sig=abc", "https://h/x?X-Amz-Signature=abc",
                "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY", "a\u201Db", "\u2013x")
                .forEach(value -> Check.that(!ReproduceProperties.safe("x", value), "secret value " + value));
        List.of("true", "3.9.14", "123e4567-e89b-12d3-a456-426614174000", "fix.BTest#adds", "UTF-8", "a b", "1.0")
                .forEach(value -> Check.that(ReproduceProperties.safe("x", value), "plain value " + value));
    }

    /** The documented skip flags with a boolean value are repeated even when nothing reads them. */
    private static void skipFlags() {
        Check.that(ReproduceProperties.skipFlag("skipTests", "true") && ReproduceProperties.skipFlag("enforcer.skip", "")
                && ReproduceProperties.skipFlag("maven.test.failure.ignore", "TRUE")
                && !ReproduceProperties.skipFlag("foo.skip", "maybe") && !ReproduceProperties.skipFlag("skipper", "true"),
                "skip flags with boolean values");
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
