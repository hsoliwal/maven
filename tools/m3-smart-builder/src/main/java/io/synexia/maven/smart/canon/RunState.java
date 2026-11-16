// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Progress of one gate run (M3-SCALE v3.0 sections 6.4 and 6.5). Atom: run state. Pattern: checkpoint state that is
 * rebuilt from the run directory on resume, so a restart continues from the last completed stage.
 */
final class RunState {
    String head = "";
    String base = "";
    String signature = "";
    String preDigest = "";
    String atoms = "not checked";
    String checks = "not checked";
    Map<String, String> preDirty = new TreeMap<>();
    List<String> shard = new ArrayList<>();
    final Set<String> passed = new TreeSet<>();
    final List<StageRun> stages = new ArrayList<>();
    boolean failed;
    int seq;

    /**
     * One executed verification stage.
     *
     * @param stage stage name from {@link Packet#ORDER}
     * @param result {@code PASS} or {@code FAIL}
     * @param exit process exit status
     * @param log log path relative to the run directory
     */
    record StageRun(String stage, String result, int exit, String log) { }
}
