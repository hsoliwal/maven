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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A native step that failed, with the last lines of its output; {@link #notStarted()} when the tool never ran. */
public final class StepFailure extends IOException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> tail;

    /** Wraps the engine's failure with the step's last output lines. */
    public StepFailure(IOException cause, List<String> tail, Path log) {
        super(cause.getMessage() + (tail.isEmpty() ? " (no output)" : "; last " + tail.size() + " lines of " + log
                + ":" + System.lineSeparator() + "  " + String.join(System.lineSeparator() + "  ", tail)), cause);
        this.tail = List.copyOf(tail);
    }

    /** The last output lines. */
    public List<String> tail() {
        return tail;
    }

    /** True when the shim reported that the tool could not be started. */
    public boolean notStarted() {
        return tail.stream().anyMatch(l -> l.startsWith(StepShim.CANNOT_START));
    }
}
