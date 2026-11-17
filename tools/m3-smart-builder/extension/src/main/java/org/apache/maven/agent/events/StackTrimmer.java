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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Stack traces cut to what explains a test failure: the exception line, application frames (those of the test's
 * own package root), {@code Caused by:} headers and the root cause with up to ten application frames; runs of
 * framework frames collapse into one {@code ... n framework frames omitted} line, and a hard line cap keeps the
 * root cause header. Ported from MavenSkills/mcp-server {@code StackTraceProcessor} (Apache-2.0, commit 32ddfdb7)
 * for Java 17, returning lines without their leading indentation.
 */
final class StackTrimmer {
    static final int ROOT_CAUSE_APP_FRAMES = 10;
    static final int MAX_HEADER_LENGTH = 200;

    /** One exception of a trace: its header line and the frame lines below it. */
    record Segment(String header, List<String> frames) {}

    private StackTrimmer() {}

    /** The trimmed lines of {@code trace}; a blank {@code appPackage} keeps every frame; {@code maxLines} 0 = no cap. */
    static List<String> trim(String trace, String appPackage, int maxLines) {
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        List<Segment> segments = segments(trace.strip());
        List<String> lines = new ArrayList<>();
        lines.add(header(segments.get(0).header()));
        frames(lines, segments.get(0).frames(), appPackage, Integer.MAX_VALUE);
        segments.subList(1, segments.size()).forEach(segment -> {
            lines.add(header(segment.header()));
            boolean root = segment == segments.get(segments.size() - 1);
            frames(lines, segment.frames(), appPackage, root ? ROOT_CAUSE_APP_FRAMES : Integer.MAX_VALUE);
        });
        List<String> capped = maxLines > 0 && lines.size() > maxLines ? cap(lines, segments, maxLines) : lines;
        return capped.stream().map(String::strip).collect(Collectors.toList());
    }

    /** Splits on unindented {@code Caused by:} lines; the first line is always the top-level header. */
    static List<Segment> segments(String trace) {
        List<String> lines = trace.lines().collect(Collectors.toList());
        List<Integer> starts = IntStream.range(0, lines.size())
                .filter(i -> i == 0 || lines.get(i).startsWith("Caused by:")).boxed()
                .collect(Collectors.toCollection(ArrayList::new));
        starts.add(lines.size());
        return IntStream.range(0, starts.size() - 1).mapToObj(k -> new Segment(lines.get(starts.get(k)),
                List.copyOf(lines.subList(starts.get(k) + 1, starts.get(k + 1))))).collect(Collectors.toList());
    }

    /** Adds frames, collapsing framework runs; at most {@code appLimit} application frames are kept. */
    private static void frames(List<String> out, List<String> frames, String appPackage, int appLimit) {
        if (appPackage == null || appPackage.isBlank()) {
            out.addAll(frames);
            return;
        }
        int[] counts = {0, 0}; // framework run, application frames kept
        frames.forEach(frame -> frame(out, frame, appPackage, appLimit, counts));
        flush(out, counts);
    }

    private static void frame(List<String> out, String frame, String appPackage, int appLimit, int[] counts) {
        boolean structural = structural(frame);
        if (structural || application(frame, appPackage)) {
            flush(out, counts);
            if (structural || counts[1]++ < appLimit) {
                out.add(structural ? header(frame) : frame);
            }
        } else {
            counts[0]++;
        }
    }

    private static void flush(List<String> out, int[] counts) {
        if (counts[0] > 0) {
            out.add("\t... " + counts[0] + " framework frames omitted");
            counts[0] = 0;
        }
    }

    /** A frame {@code at <appPackage>...}; other lines ({@code ... 42 more}) are framework artefacts. */
    static boolean application(String frame, String appPackage) {
        String trimmed = frame.strip();
        return trimmed.startsWith("at ") && trimmed.substring(3).startsWith(appPackage);
    }

    /** {@code Suppressed:} lines and indented {@code Caused by:} lines inside a suppressed block are kept. */
    static boolean structural(String line) {
        String stripped = line.strip();
        return !line.isEmpty() && (stripped.startsWith("Suppressed:")
                || stripped.startsWith("Caused by:") && Character.isWhitespace(line.charAt(0)));
    }

    /** Cuts to {@code max} lines, keeping the top header, the root cause header and its first frames. */
    private static List<String> cap(List<String> lines, List<Segment> segments, int max) {
        String rootHeader = header(segments.get(segments.size() - 1).header());
        int at = lines.lastIndexOf(rootHeader);
        if (segments.size() <= 1 || at >= 0 && at < max - 1) {
            return new ArrayList<>(lines.subList(0, max));
        }
        List<String> result = new ArrayList<>(List.of(lines.get(0), "\t... (intermediate frames truncated)", rootHeader));
        int from = at + 1;
        lines.subList(Math.min(from, lines.size()), Math.min(lines.size(), from + Math.max(0, max - 3)))
                .forEach(result::add);
        return result;
    }

    /** A header cut to {@link #MAX_HEADER_LENGTH} characters with "..." appended. */
    static String header(String header) {
        return header == null || header.length() <= MAX_HEADER_LENGTH
                ? header : header.substring(0, MAX_HEADER_LENGTH) + "...";
    }

    /** The package root used to tell application frames: the first two segments of the test class's package. */
    static String appPackage(String testClass) {
        int last = testClass.lastIndexOf('.');
        if (last < 0) {
            return "";
        }
        String[] parts = testClass.substring(0, last).split("\\.");
        return parts.length == 1 ? parts[0] + "." : parts[0] + "." + parts[1] + ".";
    }
}
