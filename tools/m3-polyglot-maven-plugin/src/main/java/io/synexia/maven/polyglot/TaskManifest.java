/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * A bounded, declarative build graph. Ant-like targets and Make-like prerequisites,
 * with original compilers/build tools remaining authoritative for their work.
 * The manifest contains arguments, not shell scripts; no command is executed here.
 */
public final class TaskManifest {
    public static final String MAGIC = "M3-POLYGLOT-TASKS-V1";
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_LINE = 4096;
    private static final int MAX_TARGETS = 256;
    private static final int MAX_FACTS = 8192;

    /** Validated immutable task; '-' as tool denotes an aggregation-only target. */
    public record Task(
            String name,
            String tool,
            String workingDir,
            List<String> arguments,
            List<String> prerequisites,
            List<String> inputs,
            List<String> outputs) {
        public Task {
            arguments = List.copyOf(arguments);
            prerequisites = List.copyOf(prerequisites);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    private static final class Draft {
        String tool;
        String workingDir;
        boolean defined;
        final List<String> args = new ArrayList<>();
        final Set<String> prerequisites = new LinkedHashSet<>();
        final Set<String> inputs = new LinkedHashSet<>();
        final Set<String> outputs = new LinkedHashSet<>();
    }

    private final Map<String, Task> tasks;

    private TaskManifest(Map<String, Task> tasks) {
        this.tasks = Collections.unmodifiableMap(new TreeMap<>(tasks));
    }

    /** Loads a strict LF-terminated UTF-8 manifest without interpolation or tool execution. */
    public static TaskManifest load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            throw new IOException("polyglot manifest must be a regular file under 1 MiB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("polyglot manifest changed or exceeds byte budget");
        }
        final String data;
        try {
            data = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("polyglot manifest must be valid UTF-8", invalid);
        }
        if (data.isEmpty() || data.charAt(0) == '\ufeff' || !data.endsWith("\n") || data.indexOf('\r') >= 0) {
            throw new IOException("polyglot manifest requires LF lines, final LF and no BOM");
        }
        String[] lines = data.split("\n", -1);
        if (!MAGIC.equals(lines[0])) {
            throw new IOException("unknown polyglot manifest version");
        }
        Map<String, Draft> drafts = new TreeMap<>();
        int facts = 0;
        for (int index = 1; index < lines.length - 1; index++) {
            String row = lines[index];
            if (row.length() > MAX_LINE) {
                throw new IOException("oversized polyglot manifest line " + (index + 1));
            }
            if (row.isEmpty() || row.startsWith("#")) {
                continue;
            }
            String[] parts = row.split("\t", -1);
            if (parts.length < 3) {
                throw new IOException("invalid polyglot row at line " + (index + 1));
            }
            String name = parts[1];
            targetName(name);
            Draft draft = drafts.computeIfAbsent(name, ignored -> new Draft());
            if (++facts > MAX_FACTS || drafts.size() > MAX_TARGETS) {
                throw new IOException("polyglot target or fact budget exceeded");
            }
            switch (parts[0]) {
                case "task" -> {
                    if (parts.length != 4 || draft.defined) {
                        throw new IOException("duplicate or malformed task declaration: " + name);
                    }
                    executable(parts[2]);
                    relativePath(parts[3], true);
                    draft.tool = parts[2];
                    draft.workingDir = parts[3];
                    draft.defined = true;
                }
                case "dep" -> {
                    if (parts.length != 3 || !draft.prerequisites.add(targetName(parts[2]))) {
                        throw new IOException("duplicate or malformed dependency: " + name);
                    }
                }
                case "arg" -> {
                    if (parts.length != 3 || parts[2].isEmpty()) {
                        throw new IOException("invalid argument row for " + name);
                    }
                    text(parts[2], "argument");
                    draft.args.add(parts[2]);
                }
                case "input", "output" -> {
                    if (parts.length != 3) {
                        throw new IOException("invalid input/output row for " + name);
                    }
                    String path = relativePath(parts[2], false);
                    Set<String> collection = "input".equals(parts[0]) ? draft.inputs : draft.outputs;
                    if (!collection.add(path)) {
                        throw new IOException("duplicate declared path: " + path);
                    }
                }
                default -> throw new IOException("unrecognized polyglot record: " + parts[0]);
            }
        }
        Map<String, Task> validated = new TreeMap<>();
        Map<String, String> outputOwners = new HashMap<>();
        for (Map.Entry<String, Draft> entry : drafts.entrySet()) {
            String name = entry.getKey();
            Draft draft = entry.getValue();
            if (!draft.defined || ("-".equals(draft.tool) && !draft.args.isEmpty())) {
                throw new IOException("undefined target or arguments on aggregation-only task: " + name);
            }
            for (String parent : draft.prerequisites) {
                if (!drafts.containsKey(parent) || !drafts.get(parent).defined) {
                    throw new IOException("unknown prerequisite " + parent + " for " + name);
                }
            }
            for (String output : draft.outputs) {
                String original = outputOwners.putIfAbsent(output, name);
                if (original != null) {
                    throw new IOException("two tasks claim output " + output + ": " + original + ", " + name);
                }
            }
            validated.put(name, new Task(
                    name, draft.tool, draft.workingDir, draft.args,
                    new ArrayList<>(draft.prerequisites),
                    new ArrayList<>(draft.inputs), new ArrayList<>(draft.outputs)));
        }
        if (validated.isEmpty()) {
            throw new IOException("polyglot manifest declares no targets");
        }
        TaskManifest result = new TaskManifest(validated);
        result.orderAll(); // Fail on cycles, including tasks not in the requested subset.
        return result;
    }

    /** List tasks in a deterministic, dependency-before-dependent order. */
    public List<Task> order(String target) {
        targetName(target);
        if (!tasks.containsKey(target)) {
            throw new IllegalArgumentException("unknown polyglot target: " + target);
        }
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.push(target);
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (reachable.add(name)) {
                tasks.get(name).prerequisites().forEach(pending::push);
            }
        }
        return topological(reachable);
    }

    /** List all tasks, validating the complete graph including unselected branches. */
    public List<Task> orderAll() {
        return topological(tasks.keySet());
    }

    private List<Task> topological(Set<String> selected) {
        Map<String, Integer> degree = new HashMap<>();
        Map<String, List<String>> consumers = new HashMap<>();
        for (String task : selected) {
            int count = 0;
            for (String parent : tasks.get(task).prerequisites()) {
                if (selected.contains(parent)) {
                    count++;
                    consumers.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(task);
                }
            }
            degree.put(task, count);
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        degree.forEach((name, count) -> {
            if (count == 0) {
                ready.add(name);
            }
        });
        List<Task> ordered = new ArrayList<>(selected.size());
        while (!ready.isEmpty()) {
            String name = ready.remove();
            ordered.add(tasks.get(name));
            for (String consumer : consumers.getOrDefault(name, List.of())) {
                if (degree.merge(consumer, -1, Integer::sum) == 0) {
                    ready.add(consumer);
                }
            }
        }
        if (ordered.size() != selected.size()) {
            throw new IllegalArgumentException("cycle in polyglot prerequisites");
        }
        return List.copyOf(ordered);
    }

    private static String targetName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("invalid polyglot target name: " + name);
        }
        return name;
    }

    private static void executable(String value) throws IOException {
        text(value, "tool");
        if (value.isBlank() || value.length() > 1024) {
            throw new IOException("tool executable must be explicit");
        }
    }

    private static String relativePath(String value, boolean allowDot) throws IOException {
        text(value, "project-relative path");
        if (allowDot && ".".equals(value)) {
            return value;
        }
        if (value.isBlank() || value.length() > 1024 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IOException("noncanonical project-relative path: " + value);
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("unsafe path segment in: " + value);
            }
        }
        return value;
    }

    private static void text(String value, String label) throws IOException {
        if (value == null || value.indexOf('\0') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("control character in " + label);
        }
    }
}
