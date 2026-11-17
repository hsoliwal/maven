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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Event stream: line prefix, seq order under parallel writers, the cap, staging and publishing. */
public final class EventWriterTestMain {
    private static final Pattern PREFIX = Pattern.compile("^\\{\"v\":1,\"seq\":(\\d+),\"tMs\":\\d+,\"type\":\"([a-z-]+)\"");

    private EventWriterTestMain() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("event-writer-test");
        prefixAndEcho(dir);
        parallelOrder(dir);
        cap(dir);
        Check.done("EventWriterTestMain");
    }

    private static void prefixAndEcho(Path dir) throws Exception {
        List<String> echoed = new ArrayList<>();
        EventWriter writer = EventWriter.open(echoed::add);
        writer.emit("session-start", new JsonText.Obj().put("root", "."));
        writer.emitFinal("session-end", new JsonText.Obj().put("outcome", "success"));
        Path target = dir.resolve("nested/target/agent/build-events.jsonl");
        writer.publish(target);
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        Check.equal(2, lines.size(), "two lines");
        Check.that(lines.get(0).matches("^\\{\"v\":1,\"seq\":1,\"tMs\":\\d+,\"type\":\"session-start\",\"root\":\"\\.\"}$"),
                "v, seq, tMs, type come first, then the fields: " + lines.get(0));
        Check.equal(lines, echoed, "the echo receives exactly the written lines");
        Check.that(!writer.emit("late", new JsonText.Obj()), "a published stream takes no more events");
        Check.equal(2, Files.readAllLines(target).size(), "the published file is unchanged");
        Check.that(Files.readString(target).endsWith("\n") && !Files.readString(target).contains("\r"), "LF line ends");
    }

    private static void parallelOrder(Path dir) throws Exception {
        EventWriter writer = EventWriter.open(line -> {});
        List<Thread> threads = new ArrayList<>();
        IntStream.range(0, 8).forEach(t -> threads.add(new Thread(() -> IntStream.range(0, 250)
                .forEach(i -> writer.emit("diagnostic", new JsonText.Obj().put("thread", t).put("i", i))))));
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }
        Path target = dir.resolve("parallel.jsonl");
        writer.publish(target);
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        Check.equal(2000, lines.size(), "every parallel event written once");
        long[] expected = {1};
        lines.forEach(line -> {
            Matcher m = PREFIX.matcher(line);
            Check.that(m.find() && Long.parseLong(m.group(1)) == expected[0]++, "seq strictly increasing: " + line);
        });
    }

    private static void cap(Path dir) throws Exception {
        EventWriter writer = EventWriter.open(line -> {});
        IntStream.range(0, EventWriter.MAX_EVENTS + 5).forEach(i -> writer.emit("diagnostic", new JsonText.Obj()));
        writer.emitFinal("session-end", new JsonText.Obj());
        Check.equal(5L, writer.dropped(), "events past the cap are counted, not written");
        Path target = dir.resolve("capped.jsonl");
        writer.publish(target);
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        Check.equal(EventWriter.MAX_EVENTS + 1, lines.size(), "the cap plus the final event");
        Check.that(lines.get(lines.size() - 1).contains("\"type\":\"session-end\""), "the final event is always written");
    }
}
