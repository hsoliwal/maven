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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;

/**
 * What a failed build tells an agent to read and run, through the recorder as the spy drives it: the whole bounded
 * failure message (dependency, plugin and rule failures keep their reason) in the digest and in {@code mojo-end};
 * reproduce commands that carry the command line's profiles and user properties (never a secret, an absolute path or
 * a value a shell would change), {@code -f} when the build ran in another directory, and the goals of a goal-level
 * failure; the launcher spelled per shell; a {@code session-start} first even when the build failed before its
 * session; no home directory or local repository in a message; test selectors every shell keeps unchanged.
 */
public final class ReproduceTestMain {
    static final String DEPENDENCY = "Could not resolve dependencies for project fix:dep:jar:1.0-SNAPSHOT\n"
            + "dependency: fix:missing-lib:jar:9.9 (compile)\n"
            + "\tCannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact"
            + " fix:missing-lib:jar:9.9 has not been downloaded from it before.";
    static final String ENFORCER = "Rule 0: org.apache.maven.enforcer.rules.property.RequireProperty failed with"
            + " message:\nmust.be.set is required";
    private static final String SECRET = "S3cretPw";

    /** A lifecycle event as the spy receives it; no session is needed for these events. */
    private record Event(Type type, MavenProject project, MojoExecution mojo, Exception exception)
            implements ExecutionEvent {
        @Override
        public Type getType() {
            return type;
        }

        @Override
        public MavenSession getSession() {
            return null;
        }

        @Override
        public MavenProject getProject() {
            return project;
        }

        @Override
        public MojoExecution getMojoExecution() {
            return mojo;
        }

        @Override
        public Exception getException() {
            return exception;
        }
    }

    private ReproduceTestMain() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("reproduce");
        fullMessage(root);
        lifecycle(root);
        configured(root);
        goals(root);
        secrets(root);
        launchers(root);
        places(root);
        selectors();
        Check.done("ReproduceTestMain");
    }

    /** A dependency and a rule failure keep every line of their message, in the digest, summary and mojo-end. */
    private static void fullMessage(Path root) throws IOException {
        finish(request(root, root, List.of("verify"), new Properties()), List.of(failed(project("plug"),
                mojo("maven-enforcer-plugin", "enforce", "validate", "need-prop"), new MojoFailureException(ENFORCER))),
                List.of(), root);
        Check.that(read(root, "failure.json").contains("\"message\":" + JsonText.quote(ENFORCER)),
                "the rule's reason in failure.json: " + read(root, "failure.json"));
        Check.that(read(root, "build-events.jsonl").contains("\"error\":" + JsonText.quote(ENFORCER)),
                "the rule's reason in mojo-end");
        Check.that(read(root, "summary.txt").contains("must.be.set is required"), "the reason in the summary");
        finish(request(root, root, List.of("verify"), new Properties()), List.of(),
                List.of(new IllegalStateException(DEPENDENCY)), root);
        Check.that(read(root, "failure.json").contains("\"message\":" + JsonText.quote(DEPENDENCY)),
                "the missing artifact and why: " + read(root, "failure.json"));
    }

    /**
     * Profiles named on the command line ({@code -P strict}, {@code -P !slow}) and the user properties come along; a
     * settings profile, the agent's own properties, the reproduce command's own test selection, absolute paths and
     * shell-active values do not. The build ran in another directory, so {@code -f} names the POM from there.
     */
    private static void lifecycle(Path root) throws IOException {
        Path elsewhere = Files.createTempDirectory("elsewhere");
        Properties user = properties("maven.compiler.failOnWarning", "true", "rel.dir", "conf/x.xml", "test", "Old*",
                "out.dir", "C:/abs/out", "unix.dir", "/abs/out", "weird", "a$b", "style.color", "never");
        MavenExecutionRequest request = request(root, elsewhere, List.of("clean", "verify"), user);
        Profile settings = new Profile();
        settings.setId("env");
        request.addProfile(settings);
        request.addActiveProfile("env");
        request.addActiveProfile("strict");
        request.addInactiveProfile("slow");
        finish(request, List.of(failed(project("p"), mojo("maven-compiler-plugin", "compile", "compile",
                "default-compile"), new MojoFailureException("Compilation failure"))), List.of(), root);
        String pom = elsewhere.relativize(root.resolve("pom.xml")).toString().replace('\\', '/');
        Check.equal("mvn -f " + pom + " \"-Pstrict,-slow\" \"-Dmaven.compiler.failOnWarning=true\""
                + " \"-Drel.dir=conf/x.xml\" -pl . -am compile", reproduce(root), "profiles, properties and -f");
    }

    /**
     * Maven 4 copies the entries of {@code maven-user.properties} (installation {@code conf/}, {@code ~/.m2},
     * {@code .mvn/}) into the user properties; a rerun reads those files again, so the command leaves them out.
     */
    private static void configured(Path root) throws IOException {
        Path home = Files.createTempDirectory("maven-home");
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/maven-user.properties"), "aether.conflictResolver.impl = path\n"
                + "maven.cache.config = \\\n    A { scope:session } \\\n    * { scope:request }\n");
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/maven.properties"), "project.flag=1\n");
        Properties user = properties("aether.conflictResolver.impl", "path", "maven.cache.config",
                "A { scope:session } * { scope:request }", "project.flag", "1", "my.flag", "on");
        MavenExecutionRequest request = request(root, root, List.of("verify"), user);
        request.getSystemProperties().setProperty("maven.home", home.toString());
        finish(request, List.of(), List.of(new IllegalStateException("boom")), root);
        Check.equal("mvn \"-Dmy.flag=on\" verify", reproduce(root), "only the command line's own property");
        Files.delete(root.resolve(".mvn/maven.properties"));
    }

    /** A goal-level failure (a mistyped phase) reruns the build's goals, in the failed project when there is one. */
    private static void goals(Path root) throws IOException {
        finish(request(root, root, List.of("verfy"), new Properties()), List.of(),
                List.of(new IllegalStateException("Unknown lifecycle phase \"verfy\"")), root);
        Check.equal("mvn verfy", reproduce(root), "no project: the goals");
        finish(request(root, root, List.of("verfy"), new Properties()), List.of(new Event(ExecutionEvent.Type
                .ProjectFailed, project("root"), null, new IllegalStateException("Unknown lifecycle phase"))), List.of(),
                root);
        Check.equal("mvn -pl . -am verfy", reproduce(root), "a project failed without a mojo: its goals");
    }

    /** A goal run from the command line with credentials around: the password reaches no agent file. */
    private static void secrets(Path root) throws IOException {
        Properties user = properties("remoteRepositories", "central::default::https://alice:" + SECRET
                + "@repo.example.com/maven2", "sonar.login", "squ_" + SECRET, "db.pwd", SECRET, "jdbc.url",
                "jdbc:h2:mem:x?user=sa&password=" + SECRET, "api.key", SECRET, "gpg.passphrase", SECRET,
                "auth.header", SECRET, "artifact", "no.such:artifact:1.0");
        MojoExecution get = mojo("maven-dependency-plugin", "get", null, "default-cli");
        finish(request(root, root, List.of("dependency:get"), user), List.of(failed(project("root"), get,
                new MojoFailureException("Couldn't download artifact from https://alice:" + SECRET + "@repo.example.com"))),
                List.of(), root);
        Check.equal("mvn \"-Dartifact=no.such:artifact:1.0\" -pl . -am org.apache.maven.plugins:maven-dependency-plugin:"
                + "3.13.0:get", reproduce(root), "the goal, with only the harmless property");
        try (Stream<Path> files = Files.list(root.resolve("target/agent"))) {
            List<String> leaking = files.filter(file -> contains(file, SECRET)).map(Path::toString)
                    .collect(Collectors.toList());
            Check.equal(List.of(), leaking, "no agent file holds the password");
        }
    }

    /** With the Maven Wrapper the digest spells the launcher for each shell; the reproduce command uses ./mvnw. */
    private static void launchers(Path root) throws IOException {
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        finish(request(root, root, List.of("verify"), new Properties()), List.of(),
                List.of(new IllegalStateException("boom")), root);
        Check.that(read(root, "failure.json").contains("\"launcher\":{\"sh\":\"./mvnw\",\"powershell\":"
                + "\".\\\\mvnw.cmd\",\"cmd\":\".\\\\mvnw.cmd\"}"), "launcher per shell: " + read(root, "failure.json"));
        Check.equal("./mvnw verify", reproduce(root), "./mvnw in the reproduce command");
        Check.that(read(root, "failure.md").contains("PowerShell and cmd.exe start it as `.\\mvnw.cmd`"),
                "failure.md names the Windows form");
        Files.delete(root.resolve("mvnw"));
        String first = read(root, "build-events.jsonl").lines().findFirst().orElse("");
        Check.that(first.contains("\"seq\":1,") && first.contains("\"type\":\"session-start\"")
                && first.contains("\"goals\":[\"verify\"]") && first.contains("\"modules\":[]"),
                "a build that failed before its session still starts with session-start: " + first);
    }

    /** The home directory becomes ~ and the local repository {@code <local-repo>}, in every spelling. */
    private static void places(Path root) throws IOException {
        Path home = Files.createTempDirectory("home");
        Path repo = Files.createTempDirectory("repo");
        Properties user = properties("maven.repo.local", repo.toString());
        MavenExecutionRequest request = request(root, root, List.of("verify"), user);
        request.getSystemProperties().setProperty("user.home", home.toString());
        String message = "Cannot access local (" + repo + ") with " + home.resolve(".m2").resolve("settings.xml")
                + " and " + home.toString().replace('\\', '/') + "/x for " + root.resolve("a").resolve("B.java");
        finish(request, List.of(), List.of(new IllegalStateException(message)), root);
        String sep = home.getFileSystem().getSeparator();
        String expected = JsonText.quote("Cannot access local (<local-repo>) with ~" + sep + ".m2" + sep
                + "settings.xml and ~/x for a" + sep + "B.java");
        String json = read(root, "failure.json");
        Check.that(json.contains(expected.substring(1, expected.length() - 1)) && !json.contains(home.getFileName()
                .toString()) && !json.contains(repo.getFileName().toString()), "home and repository: " + json);
    }

    /** Selectors hold only characters every shell keeps inside double quotes; module paths fall back to the id. */
    private static void selectors() {
        List<SurefireReports.TestCase> tests = List.of(test("fix.BSpec", "x\" & calc & \"y"), test("fix.CSpec",
                "a`id`b"), test("fix.Bad\"Name", "m"), test("fix.Outer$Inner", "m"), test("fix.DTest", "ok"),
                test("fix.ETest", "with space"), test("fix.ETest", "fine"));
        Check.equal("fix.BSpec,fix.CSpec,fix.Outer*Inner#m,fix.DTest#ok,fix.ETest", ReproduceCommand.selector(tests),
                "unsafe methods give their class, an unsafe class is left out");
        Check.equal("a/b", ReproduceCommand.projectArg("a/b", "fix:b"), "a plain path");
        Check.equal("fix:m", ReproduceCommand.projectArg("my$mod", "fix:m"), "a path a shell would change");
        Check.equal("http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException",
                SessionReport.helpLink("MojoFailureException"), "Maven 4's bare reference becomes the link");
        Check.equal("https://example.org/x", SessionReport.helpLink("https://example.org/x"), "a link stays");
    }

    private static SurefireReports.TestCase test(String className, String method) {
        return new SurefireReports.TestCase(className, method, "failed", 1, "", "", "");
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    /** Records an agent build of {@code request}: the events, then its end with {@code exceptions}. */
    private static void finish(MavenExecutionRequest request, List<ExecutionEvent> events, List<Throwable> exceptions,
            Path root) throws IOException {
        AgentSettings settings = AgentSettings.of(request.getUserProperties(), request.getSystemProperties(), root);
        BuildRecorder recorder = new BuildRecorder(settings, EventWriter.open(line -> {}), request);
        events.forEach(recorder::handle);
        recorder.finish(exceptions);
    }

    private static MavenExecutionRequest request(Path root, Path workingDir, List<String> goals, Properties user) {
        Properties system = new Properties();
        system.setProperty("user.dir", workingDir.toString());
        user.setProperty("maven.agent", "true");
        user.setProperty("maven.events.stdout", "none");
        return new DefaultMavenExecutionRequest().setPom(root.resolve("pom.xml").toFile()).setGoals(goals)
                .setUserProperties(user).setSystemProperties(system);
    }

    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        java.util.stream.IntStream.range(0, pairs.length / 2).forEach(i -> properties.setProperty(pairs[2 * i],
                pairs[2 * i + 1]));
        return properties;
    }

    private static MavenProject project(String artifactId) {
        MavenProject project = new MavenProject();
        project.setGroupId("fix");
        project.setArtifactId(artifactId);
        return project;
    }

    private static MojoExecution mojo(String artifactId, String goal, String phase, String executionId) {
        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifactId);
        plugin.setVersion("3.13.0");
        plugin.setGoalPrefix(artifactId.replace("maven-", "").replace("-plugin", ""));
        MojoDescriptor descriptor = new MojoDescriptor();
        descriptor.setGoal(goal);
        descriptor.setPluginDescriptor(plugin);
        MojoExecution mojo = new MojoExecution(descriptor, executionId);
        mojo.setLifecyclePhase(phase);
        return mojo;
    }

    private static ExecutionEvent failed(MavenProject project, MojoExecution mojo, Exception cause) {
        return new Event(ExecutionEvent.Type.MojoFailed, project, mojo, cause);
    }

    private static String read(Path root, String file) throws IOException {
        return Files.readString(root.resolve("target/agent").resolve(file), StandardCharsets.UTF_8);
    }

    /** The reproduce command of the first failure in failure.json, unescaped. */
    private static String reproduce(Path root) throws IOException {
        String json = read(root, "failure.json");
        int start = json.indexOf("\"reproduce\":\"") + "\"reproduce\":\"".length();
        return json.substring(start, json.indexOf("\"}", start)).replace("\\\"", "\"");
    }
}
