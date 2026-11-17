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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.StageCollector;

/**
 * Installs into the stage in prepare-package: cmake --install --prefix, make install DESTDIR, meson install
 * --destdir. A build system without an install step stages the declared {@code outputs} instead. Without
 * incremental mode the stage is emptied first, so it holds exactly this build's files.
 */
@Mojo(name = "stage", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public final class StageMojo extends AbstractNativeMojo {
    @Override
    protected void runNative() throws IOException, InterruptedException {
        NativeSession session = open(false);
        if (!session.found()) {
            return;
        }
        Layout layout = session.context().layout();
        if (!incremental) {
            session.clean(layout.stage());
        }
        if (!session.run(NativePhase.INSTALL)) {
            stageOutputs(session, layout);
        }
        report(StageCollector.classify(StageCollector.root(layout.path(layout.stage()), prefix)));
    }

    private void stageOutputs(NativeSession session, Layout layout) throws IOException {
        List<String> copied = StageCollector.copyOutputs(layout.basedir(), session.context().options().outputs(),
                layout.path(layout.stage()));
        getLog().info("no install step for " + session.context().system().id() + "; staged declared outputs " + copied);
    }

    private void report(StageCollector.Staged staged) {
        Path root = staged.root();
        getLog().info("STAGE " + root + ": " + staged.staticLibraries().size() + " static, "
                + staged.sharedLibraries().size() + " shared, " + staged.executables().size() + " executables, "
                + staged.headers().size() + " headers, " + staged.other().size() + " other");
        staged.all().forEach(file -> getLog().info("  " + file));
    }
}
