/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Precomputed reverse dependency DAG in compact CSR-style array lanes.
 *
 * <p>Prepared exactly once per reactor/module graph rather than rebuilding a
 * HashMap of dependent lists for every executed task. The arrays represent
 * both plain tasks and aggregation nodes; transitive invalidation therefore
 * follows the original TaskManifest edges without needing per-edge objects.
 */
final class TaskInvalidationIndex {
    private final String[] names;
    private final Map<String, Integer> ids;
    private final int[] offsets;
    private final int[] consumers;
    private final int[] visitGeneration;
    private final int[] queue;
    private int epoch;

    TaskInvalidationIndex(TaskManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        List<TaskManifest.Task> tasks = manifest.orderAll();
        int count = tasks.size();
        names = new String[count];
        ids = new HashMap<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            names[i] = tasks.get(i).name();
            if (ids.put(names[i], i) != null) throw new IllegalArgumentException("duplicate task name");
        }
        int[] degrees = new int[count];
        int edgeCount = 0;
        for (TaskManifest.Task task : tasks) {
            for (String prerequisite : task.prerequisites()) {
                int parent = requireTask(prerequisite);
                degrees[parent]++;
                edgeCount++;
            }
        }
        offsets = new int[count + 1];
        for (int i = 0; i < count; i++) offsets[i + 1] = offsets[i] + degrees[i];
        consumers = new int[edgeCount];
        int[] next = Arrays.copyOf(offsets, count);
        for (TaskManifest.Task task : tasks) {
            int child = requireTask(task.name());
            for (String prerequisite : task.prerequisites()) {
                consumers[next[requireTask(prerequisite)]++] = child;
            }
        }
        visitGeneration = new int[count];
        queue = new int[count];
    }

    /**
     * Includes the task itself and all transitive dependents. A diamond's
     * shared descendants appear exactly once. Repeated calls cost O(V+E)
     * only for the affected subtree, not for rebuilding the graph.
     */
    List<String> closure(String taskName) {
        int origin = requireTask(taskName);
        if (epoch == Integer.MAX_VALUE) {
            Arrays.fill(visitGeneration, 0);
            epoch = 0;
        }
        int generation = ++epoch;
        int head = 0, tail = 0;
        queue[tail++] = origin;
        visitGeneration[origin] = generation;
        while (head < tail) {
            int parent = queue[head++];
            for (int edge = offsets[parent]; edge < offsets[parent + 1]; edge++) {
                int child = consumers[edge];
                if (visitGeneration[child] != generation) {
                    visitGeneration[child] = generation;
                    queue[tail++] = child;
                }
            }
        }
        ArrayList<String> result = new ArrayList<>(tail);
        for (int i = 0; i < tail; i++) result.add(names[queue[i]]);
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private int requireTask(String name) {
        Integer id = ids.get(name);
        if (id == null) throw new IllegalArgumentException("unknown module task: " + name);
        return id;
    }
}
