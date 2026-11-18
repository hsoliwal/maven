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

import static org.apache.maven.agent.events.ReproduceTestMain.contains;
import static org.apache.maven.agent.events.ReproduceTestMain.failed;
import static org.apache.maven.agent.events.ReproduceTestMain.finish;
import static org.apache.maven.agent.events.ReproduceTestMain.mojo;
import static org.apache.maven.agent.events.ReproduceTestMain.project;
import static org.apache.maven.agent.events.ReproduceTestMain.properties;
import static org.apache.maven.agent.events.ReproduceTestMain.read;
import static org.apache.maven.agent.events.ReproduceTestMain.reproduce;
import static org.apache.maven.agent.events.ReproduceTestMain.request;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;

/**
 * What a reproduce command repeats from the build's command line, through the recorder as the spy drives it: the
 * profiles ({@code -P}, optional ids on Maven 4, where a profile the selection does not define stops the build), the
 * POM ({@code -f}) when the build ran elsewhere, and of the user properties only those the build reads (the failed
 * mojo, the reactor POM, profile activation, skip flags), never a secret under any name, a token- or webhook-shaped
 * value, an absolute path or a value a shell would change; summary, failure.md and failure.json count the rest.
 */
public final class ReproducePropertiesTestMain {
    /** The root POM: reads my.flag and the property-named values, activates a profile by act.flag. */
    static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>fix</groupId><artifactId>root"
            + "</artifactId><version>1</version><properties><level>${my.flag}</level><x>${nexus.pw}${gh}"
            + "${slack.webhook}${api.bearer}${db.pin}${aws.id}${deploy.signature}</x></properties><profiles><profile>"
            + "<id>act</id><activation><property><name>!act.flag</name></property></activation></profile></profiles>"
            + "</project>\n";

    /** Secret values the verifier passed under names the round-2 name filter missed, and more. */
    static final List<String> LEAKS = List.of("hunter2", "SECRETX", "ghp_0123", "squ_should", "eyJhbGci", "8812",
            "AKIAIOSFODNN7EXAMPLE", "sigVALUE123");

    private static final String SECRET = "S3cretPw";

    private ReproducePropertiesTestMain() {}

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("reproduce-properties");
        Files.writeString(root.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        lifecycle(root);
        maven4(root);
        configured(root);
        allowlist(root);
        secrets(root);
        Check.done("ReproducePropertiesTestMain");
    }

    /**
     * Profiles named on the command line ({@code -P strict}, {@code -P !slow}) come along, and of the user properties
     * those the failed mojo reads; a settings profile, the agent's own properties, the reproduce command's own test
     * selection, unread properties, absolute paths and shell-active values do not, and the summary, failure.md and
     * failure.json count what was left out. The build ran in another directory, so {@code -f} names the POM from there.
     */
    private static void lifecycle(Path root) throws IOException {
        Path elsewhere = Files.createTempDirectory("elsewhere");
        Properties user = properties("maven.compiler.failOnWarning", "true", "rel.dir", "conf/x.xml", "test", "Old*",
                "out.dir", "C:/abs/out", "unix.dir", "/abs/out", "weird", "a$b", "style.color", "never");
        MavenExecutionRequest request = profiled(request(root, elsewhere, List.of("clean", "verify"), user));
        finish(request, List.of(failed(project("p"), mojo("maven-compiler-plugin", "compile", "compile",
                "default-compile", "${maven.compiler.failOnWarning}", "${project.build.directory}"),
                new MojoFailureException("Compilation failure"))), List.of(), root);
        String pom = elsewhere.relativize(root.resolve("pom.xml")).toString().replace('\\', '/');
        Check.equal("mvn -f " + pom + " \"-Pstrict,-slow\" \"-Dmaven.compiler.failOnWarning=true\" -pl . -am compile",
                reproduce(root), "profiles, the property the mojo reads and -f");
        omitted(root, 4);
    }

    /** On Maven 4 a profile no module of the selection defines stops the build, so every id is optional there. */
    private static void maven4(Path root) throws IOException {
        MavenExecutionRequest request = profiled(request(root, root, List.of("verify"), new Properties()));
        request.getSystemProperties().setProperty("maven.version", "4.0.0-rc-7");
        finish(request, List.of(failed(project("p"), mojo("maven-compiler-plugin", "compile", "compile",
                "default-compile"), new MojoFailureException("Compilation failure"))), List.of(), root);
        Check.equal("mvn \"-P?strict,-?slow\" -pl . -am compile", reproduce(root), "optional profiles on Maven 4");
    }

    /** A settings profile {@code env} active, {@code strict} activated and {@code slow} deactivated by -P. */
    private static MavenExecutionRequest profiled(MavenExecutionRequest request) {
        Profile settings = new Profile();
        settings.setId("env");
        request.addProfile(settings);
        request.addActiveProfile("env");
        request.addActiveProfile("strict");
        request.addInactiveProfile("slow");
        return request;
    }

    /**
     * Only properties the build reads are repeated: a profile's activation property and the skip flags; secrets under
     * any name the reactor POM reads (a short name, a token-shaped or webhook value) never are, and are counted.
     */
    private static void allowlist(Path root) throws IOException {
        Properties user = properties("nexus.pw", "hunter2", "slack.webhook", "https://hooks.slack.com/services/T0/B0/"
                + "SECRETX", "gh", "ghp_0123456789abcdefABCDEF0123456789abcd", "sonar.token", "squ_shouldbehidden",
                "api.bearer", "eyJhbGciOiJIUzI1NiJ9.e30.x", "deploy.signature", "sigVALUE123", "db.pin", "8812",
                "aws.id", "AKIAIOSFODNN7EXAMPLE", "act.flag", "on", "skipTests", "true", "enforcer.skip", "true",
                "foo.skip", "maybe");
        finish(request(root, root, List.of("verfy"), user), List.of(),
                List.of(new IllegalStateException("Unknown lifecycle phase \"verfy\"")), root);
        Check.equal("mvn \"-Dact.flag=on\" \"-Denforcer.skip=true\" \"-DskipTests=true\" verfy", reproduce(root),
                "the activation property and the skip flags");
        omitted(root, 9);
        noLeak(root, LEAKS);
    }

    /**
     * Maven 4 copies the entries of {@code maven-user.properties} (installation {@code conf/}, {@code ~/.m2},
     * {@code .mvn/}) into the user properties, and {@code .mvn/maven.config} and {@code MAVEN_ARGS} add theirs; a rerun
     * reads them again, so the command neither repeats nor counts them.
     */
    private static void configured(Path root) throws IOException {
        Path home = Files.createTempDirectory("maven-home");
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/maven-user.properties"), "aether.conflictResolver.impl = path\n"
                + "maven.cache.config = \\\n    A { scope:session } \\\n    * { scope:request }\n");
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/maven.properties"), "project.flag=1\n");
        Files.writeString(root.resolve(".mvn/maven.config"), "-B\n-Dconfig.flag=1 -Dconfig.bare\n");
        Properties user = properties("aether.conflictResolver.impl", "path", "maven.cache.config", "A { scope:session }"
                + " * { scope:request }", "project.flag", "1", "my.flag", "on", "config.flag", "1", "config.bare", "true",
                "args.flag", "1", "maven.repo.local", "/private/repo", "session.topDirectory", root.toString());
        MavenExecutionRequest request = request(root, root, List.of("verify"), user);
        request.getSystemProperties().setProperty("maven.home", home.toString());
        request.getSystemProperties().setProperty("env.MAVEN_ARGS", "-o -Dargs.flag=1 -Dmaven.repo.local=/private/repo");
        finish(request, List.of(), List.of(new IllegalStateException("boom")), root);
        Check.equal("mvn \"-Dmy.flag=on\" verify", reproduce(root), "only the command line's own property, POM-read");
        omitted(root, 0);
        Files.delete(root.resolve(".mvn/maven.properties"));
        Files.delete(root.resolve(".mvn/maven.config"));
    }

    /** A goal run from the command line with credentials around: the password reaches no agent file. */
    private static void secrets(Path root) throws IOException {
        Properties user = properties("remoteRepositories", "central::default::https://alice:" + SECRET
                + "@repo.example.com/maven2", "sonar.login", "squ_" + SECRET, "db.pwd", SECRET, "jdbc.url",
                "jdbc:h2:mem:x?user=sa&password=" + SECRET, "api.key", SECRET, "gpg.passphrase", SECRET,
                "auth.header", SECRET, "artifact", "no.such:artifact:1.0");
        MojoExecution get = mojo("maven-dependency-plugin", "get", null, "default-cli", "${artifact}",
                "${remoteRepositories}", "${sonar.login}", "${jdbc.url}");
        finish(request(root, root, List.of("dependency:get"), user), List.of(failed(project("root"), get,
                new MojoFailureException("Couldn't download artifact from https://alice:" + SECRET + "@repo.example.com"))),
                List.of(), root);
        Check.equal("mvn \"-Dartifact=no.such:artifact:1.0\" -pl . -am org.apache.maven.plugins:maven-dependency-plugin:"
                + "3.13.0:get", reproduce(root), "the goal, with only the harmless property");
        omitted(root, 7);
        noLeak(root, List.of(SECRET));
    }

    /** No agent file and no event holds any of {@code secrets}. */
    private static void noLeak(Path root, List<String> secrets) throws IOException {
        try (Stream<Path> files = Files.list(root.resolve("target/agent"))) {
            List<String> leaking = files.filter(file -> secrets.stream().anyMatch(secret -> contains(file, secret)))
                    .map(Path::toString).collect(Collectors.toList());
            Check.equal(List.of(), leaking, "no agent file holds a secret of " + secrets);
        }
    }

    /** failure.json counts {@code count} left-out user properties, the summary and failure.md say so when any. */
    private static void omitted(Path root, int count) throws IOException {
        Check.that(read(root, "failure.json").contains("\"omittedProperties\":" + count), "omittedProperties "
                + count + ": " + read(root, "failure.json"));
        String note = "(" + count + " user propert" + (count == 1 ? "y" : "ies") + " omitted)";
        Check.that(count == 0 ? !read(root, "summary.txt").contains("omitted)") : read(root, "summary.txt")
                .contains("[agent]   " + note), "summary " + note);
        Check.that(count == 0 ? !read(root, "failure.md").contains("omitted)") : read(root, "failure.md")
                .contains("\n" + note + "\n"), "failure.md " + note);
    }

}
