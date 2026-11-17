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
package org.apache.maven.plugins.nativebuild;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.NativeStep;
import org.apache.maven.plugins.nativebuild.engine.TaskFileWriter;

/**
 * Writes the native steps as a chained polyglot manifest in the project directory (m3-build.tsv by default), with
 * the aggregation targets compile, test and package, for builds that use the polyglot lifecycle participant instead
 * of this plugin's goals. An existing manifest is kept unless {@code overwrite} is set. No default phase.
 */
@Mojo(name = "polyglot-tasks", threadSafe = true)
public final class PolyglotTasksMojo extends AbstractNativeMojo {
    /** The manifest to write. */
    @Parameter(property = "native.polyglotManifest", defaultValue = "${project.basedir}/m3-build.tsv")
    File manifest;

    /** Replaces an existing manifest. */
    @Parameter(property = "native.polyglotManifest.overwrite", defaultValue = "false")
    boolean overwrite;

    @Override
    protected void runNative() throws IOException {
        NativeSession session = NativeSession.open(this, false, true);
        if (!session.found()) {
            return;
        }
        if (manifest.exists() && !overwrite) {
            getLog().info("KEEP " + manifest + " (native.polyglotManifest.overwrite=true replaces it)");
            return;
        }
        TaskFileWriter.write(manifest.toPath(), TaskFileWriter.render(withPrepare(session), true));
        getLog().info("WROTE " + manifest);
    }

    /** Adds a mkdir step when a step works in a directory below target, which a clean build does not have. */
    private static List<NativeStep> withPrepare(NativeSession session) {
        Layout layout = session.context().layout();
        List<String> missing = session.steps().stream().map(NativeStep::workingDir)
                .filter(dir -> dir.startsWith(layout.build())).distinct().toList();
        if (missing.isEmpty()) {
            return session.steps();
        }
        List<NativeStep> steps = new ArrayList<>();
        steps.add(new NativeStep(NativePhase.PREPARE, session.context().tool("mkdir"), ".",
                Stream.concat(Stream.of("-p"), missing.stream()).toList(), List.of(), List.of()));
        steps.addAll(session.steps());
        return steps;
    }
}
