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

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The user properties a reproduce command repeats, by allowlist: a property is repeated only when the build reads it
 * ({@link PropertyReads}: the failed mojo, the reactor's POM files, profile activation) or it is a skip flag with a
 * boolean value ({@code skipTests}, {@code skipITs}, {@code maven.test.skip}, {@code maven.test.skip.exec},
 * {@code maven.test.failure.ignore}, {@code <name>.skip}), and only when {@link #safe}: no secret-looking name, no
 * token-, webhook- or key-shaped value, no credential, no absolute path, nothing a shell would change. The agent's own
 * settings, the reproduce command's own test selection, the properties Maven 4 sets itself ({@code session.*Directory})
 * and those Maven's configuration sets are dropped without a word; every other user property left out is counted, so
 * an agent knows the rerun lacks it.
 */
final class ReproduceProperties {
    /** No user property at all. */
    static final ReproduceProperties NONE = new ReproduceProperties(new TreeMap<>(), Set.of());
    private static final Pattern OWN = Pattern.compile("maven\\.agent(\\..*)?|maven\\.events\\..*|style\\.color"
            + "|maven\\.ext\\.class\\.path|chromellm\\.advice|test|it\\.test"
            + "|(surefire|failsafe)\\.failIfNoSpecifiedTests|session\\.(?:topDirectory|rootDirectory)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,200}+");
    private static final Pattern SKIP = Pattern.compile("skipTests|skipITs"
            + "|maven\\.test\\.(?:skip(?:\\.exec)?|failure\\.ignore)|(?:[A-Za-z0-9_-]{1,100}+\\.){1,10}+skip");
    private static final Pattern BOOLEAN = Pattern.compile("(?i)true|false|");
    private static final Pattern SECRET_WORD = Pattern.compile("(?i)pass|pwd|secret|token|credential|auth|key|login"
            + "|session|cookie|private|bearer|jwt|signature|hmac|webhook");
    private static final Pattern SECRET_PART = Pattern.compile("(?i)pw|pin|otp|sig|salt|dsn|cred|creds");
    private static final Pattern NAME_PARTS = Pattern.compile("[._-]++|(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern SECRET_PAIR =
            Pattern.compile("(?i)(password|passwd|pwd|token|secret|apikey|api_key|key)=");
    /** Token prefixes (GitHub, GitLab, Slack, OpenAI, Stripe, AWS, npm, PyPI, JWT), webhook hosts, signed URLs. */
    private static final Pattern TOKEN = Pattern.compile("\\b(?:gh[pousr]_|github_pat_|glpat-|xox[abprs]-"
            + "|sk-[A-Za-z0-9]|[sr]k_live_|npm_[A-Za-z0-9]|pypi-|eyJ[A-Za-z0-9_-]{8}|(?:AKIA|ASIA)[A-Z0-9]{12})"
            + "|(?i:hooks\\.slack\\.com|discord(?:app)?\\.com/api/webhooks|webhook\\.office\\.com"
            + "|[?&](?:sig|signature|x-amz-signature|x-amz-credential|x-amz-security-token)=)");
    /** A long random-looking run: at least 24 token characters with an upper-case letter, a lower-case one, a digit. */
    private static final Pattern RANDOM = Pattern.compile("(?=[^A-Z]*+[A-Z])(?=[^a-z]*+[a-z])(?=[^0-9]*+[0-9])"
            + "[A-Za-z0-9+/=_-]{24,}+");
    private static final Pattern NOT_TOKEN = Pattern.compile("[^A-Za-z0-9+/=_-]++");
    private static final Pattern ABSOLUTE = Pattern.compile("(?:^|[=,;])(?:[A-Za-z]:|~)?[/\\\\]");

    /** The {@code -Dname=value} arguments a reproduce command repeats and how many user properties it leaves out. */
    record Selection(List<String> args, int omitted) {}

    private final SortedMap<String, String> candidates;
    private final Set<String> read;

    private ReproduceProperties(SortedMap<String, String> candidates, Set<String> read) {
        this.candidates = candidates;
        this.read = Set.copyOf(read);
    }

    /**
     * The user properties of a build, without the agent's own, the reproduce command's own selection and those in
     * {@code configured}; {@code read} names those the build reads besides the failed mojo.
     */
    static ReproduceProperties of(Properties user, Set<String> configured, Set<String> read) {
        SortedMap<String, String> candidates = user.stringPropertyNames().stream()
                .filter(name -> !OWN.matcher(name).matches() && !configured.contains(name))
                .collect(Collectors.toMap(name -> name, user::getProperty, (a, b) -> a, TreeMap::new));
        return new ReproduceProperties(candidates, read);
    }

    /** The properties repeated for a failure whose mojo reads {@code mojoReads}, sorted by name. */
    Selection select(Set<String> mojoReads) {
        List<String> args = candidates.entrySet().stream().filter(e -> repeated(e.getKey(), e.getValue(), mojoReads))
                .map(e -> "-D" + e.getKey() + "=" + e.getValue()).collect(Collectors.toList());
        return new Selection(args, candidates.size() - args.size());
    }

    private boolean repeated(String name, String value, Set<String> mojoReads) {
        return (read.contains(name) || mojoReads.contains(name) || skipFlag(name, value)) && safe(name, value);
    }

    /** A skip flag with a boolean (or empty) value. */
    static boolean skipFlag(String name, String value) {
        return SKIP.matcher(name).matches() && BOOLEAN.matcher(value).matches();
    }

    /** Whether {@code name=value} may appear in a command line (see the class comment). */
    static boolean safe(String name, String value) {
        return NAME.matcher(name).matches() && !secretName(name) && MavenCommands.quotable(value)
                && !SECRET_PAIR.matcher(value).find() && !TOKEN.matcher(value).find() && !random(value)
                && Redact.secrets(value).equals(value) && !ABSOLUTE.matcher(value).find();
    }

    /** A name holding a secret word anywhere, or a part (between '.', '_', '-' or a camel hump) such as pw or pin. */
    private static boolean secretName(String name) {
        return SECRET_WORD.matcher(name).find()
                || NAME_PARTS.splitAsStream(name).anyMatch(part -> SECRET_PART.matcher(part).matches());
    }

    private static boolean random(String value) {
        return Arrays.stream(NOT_TOKEN.split(value)).anyMatch(run -> RANDOM.matcher(run).matches());
    }
}
