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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/** Agent properties: off by default, user over system, bounded, paths under the root. */
public final class AgentSettingsTestMain {
    private AgentSettingsTestMain() {}

    public static void main(String[] args) {
        Path root = Path.of("build-root").toAbsolutePath().normalize();
        AgentSettings off = AgentSettings.of(new Properties(), new Properties(), root);
        Check.that(!off.events() && !off.agent() && off.stdout() == AgentSettings.Stdout.NONE, "off without properties");
        AgentSettings agent = AgentSettings.of(props("maven.agent", "true"), new Properties(), root);
        Check.equal(List.of(true, true, AgentSettings.Stdout.SUMMARY, root.resolve("target/agent"),
                        root.resolve("target/agent/build-events.jsonl"), 20, 3, 12, 2, 300, 4000, false, Optional.empty()),
                List.of(agent.events(), agent.agent(), agent.stdout(), agent.agentDir(), agent.eventsFile(),
                        agent.printLimit(), agent.maxRepeat(), agent.stackLines(), agent.context(), agent.messageChars(),
                        agent.summaryBytes(), agent.allTests(), agent.eventsLog()), "agent defaults");
        AgentSettings eventsOnly = AgentSettings.of(props("maven.events.file", "out/e.jsonl"), new Properties(), root);
        Check.equal(List.of(true, false, AgentSettings.Stdout.NONE, root.resolve("out/e.jsonl")), List.of(eventsOnly.events(),
                eventsOnly.agent(), eventsOnly.stdout(), eventsOnly.eventsFile()), "an events file alone: events, no agent");
        Properties user = props("maven.agent", "true", "maven.agent.context", "50", "maven.agent.printLimit", "-4",
                "maven.agent.stackLines", "abc", "maven.events.stdout", "EVENTS", "maven.agent.tests", "all",
                "maven.events.log", "build.log");
        AgentSettings bounded = AgentSettings.of(user, props("maven.agent.context", "1", "maven.agent.maxRepeat", "7"), root);
        Check.equal(List.of(10, 0, 12, 7, AgentSettings.Stdout.EVENTS, true, Optional.of(root.resolve("build.log"))),
                List.of(bounded.context(), bounded.printLimit(), bounded.stackLines(), bounded.maxRepeat(), bounded.stdout(),
                        bounded.allTests(), bounded.eventsLog()), "bounded, user over system, unparsable gives the default");
        Check.equal(".", agent.relative(root), "the root is .");
        Check.equal("a/src/A.java", agent.relative(root.resolve("a").resolve("src").resolve("A.java")), "'/' separators");
        Check.equal(AgentSettings.Stdout.NONE, AgentSettings.of(props("maven.agent", "true", "maven.events.stdout", "loud"),
                new Properties(), root).stdout(), "an unknown stdout mode prints nothing");
        Check.done("AgentSettingsTestMain");
    }

    private static Properties props(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }
}
