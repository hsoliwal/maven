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
package org.apache.maven.plugins.nativebuild.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What {@link StepShim} starts for one native step: the tool, its argument list, environment additions, an
 * optional directory to empty first (the stage, emptied exactly when an install really runs) and the content stamps
 * to delete before the tool starts (the step's own and those of every later step, so a step that fails or is killed
 * never keeps the stamp of an earlier success, and the steps after a re-run step run again too). Written to
 * {@code <build>/steps/<task>.step} and declared as an input of the polyglot task, so a changed argument list,
 * environment or tool changes the task's content stamp. One fact per line, tab-separated, no escaping: values with
 * control characters are refused, as in the polyglot manifest.
 *
 * @param tool the executable
 * @param arguments the argument list
 * @param environment variables added to the inherited environment
 * @param clean an absolute directory emptied before the tool starts, or empty
 * @param forget absolute stamp files deleted before the tool starts
 */
public record StepSpec(String tool, List<String> arguments, Map<String, String> environment, String clean,
        List<String> forget) {
    /** The first line. */
    public static final String MAGIC = "NATIVE-STEP-V1";

    /** Copies and checks. */
    public StepSpec {
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(new TreeMap<>(environment));
        forget = List.copyOf(forget);
        Stream.of(Stream.of(tool, clean), arguments.stream(), forget.stream(), environment.entrySet().stream()
                .flatMap(e -> Stream.of(e.getKey(), e.getValue()))).flatMap(s -> s).forEach(StepSpec::check);
        if (tool.isEmpty() || environment.keySet().stream().anyMatch(k -> k.isEmpty() || k.contains("="))) {
            throw new IllegalArgumentException("a native step needs a tool and NAME=VALUE environment entries");
        }
    }

    /** A step that deletes no stamps. */
    public StepSpec(String tool, List<String> arguments, Map<String, String> environment, String clean) {
        this(tool, arguments, environment, clean, List.of());
    }

    /** The command: tool, then arguments. */
    public List<String> command() {
        return Steps.list(tool, arguments);
    }

    /** The file text, LF line ends, environment sorted by name. */
    public String render() {
        Stream<String> lines = Stream.of(Stream.of(MAGIC, "tool\t" + tool), arguments.stream().map(a -> "arg\t" + a),
                new TreeMap<>(environment).entrySet().stream().map(e -> "env\t" + e.getKey() + "=" + e.getValue()),
                Optional.of(clean).filter(d -> !d.isEmpty()).stream().map(d -> "clean\t" + d),
                forget.stream().map(f -> "forget\t" + f)).flatMap(s -> s);
        return lines.collect(Collectors.joining("\n", "", "\n"));
    }

    /** Reads a rendered step. */
    public static StepSpec parse(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !MAGIC.equals(lines.get(0))) {
            throw new IllegalArgumentException("not a native step file");
        }
        List<String[]> facts = lines.stream().skip(1).map(l -> l.split("\t", 2)).filter(f -> f.length == 2).toList();
        List<String> args = new ArrayList<>(values(facts, "arg"));
        Map<String, String> env = values(facts, "env").stream().map(v -> v.split("=", 2))
                .collect(Collectors.toMap(v -> v[0], v -> v.length > 1 ? v[1] : "", (a, b) -> b, TreeMap::new));
        return new StepSpec(values(facts, "tool").stream().findFirst().orElse(""), args, env,
                values(facts, "clean").stream().findFirst().orElse(""), values(facts, "forget"));
    }

    private static List<String> values(List<String[]> facts, String kind) {
        return facts.stream().filter(f -> kind.equals(f[0])).map(f -> f[1]).toList();
    }

    private static void check(String value) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("native step values must not hold tabs, line breaks or other control "
                    + "characters: '" + value.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "'");
        }
    }
}
