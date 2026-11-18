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
import java.util.Optional;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.nativebuild.engine.FileTrees;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;
import org.apache.maven.plugins.nativebuild.engine.StageCollector;

/**
 * Installs into the stage in prepare-package: cmake --install --prefix with DESTDIR, make install DESTDIR, meson
 * install --destdir; files land in {@code <stage>/<prefix>}, and anything an install put elsewhere in the stage (an
 * absolute destination) is reported and not attached. The stage is emptied whenever the install really runs (also in
 * incremental mode), so it holds exactly this build's files. A build system without an install step stages the declared
 * {@code outputs} instead; an install the plugin refuses (a makefile that ignores DESTDIR, a stage path make cannot
 * quote) fails unless outputs are declared.
 */
@Mojo(name = "stage", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class StageMojo extends AbstractNativeMojo {
    @Override
    protected void runNative() throws IOException, InterruptedException, MojoFailureException {
        NativeSession session = open(NativeSession.Use.RUN);
        if (!session.found()) {
            return;
        }
        Layout layout = session.context().layout();
        if (!session.run(NativePhase.INSTALL)) {
            refused(session);
            if (layout.stageDisposable()) {
                FileTrees.delete(layout.path(layout.stage()));
            }
            stageOutputs(session, layout);
        }
        report(StageCollector.classify(root(layout.path(layout.stage()), session.context().options().prefix())));
    }

    /** The stage root; files the install put elsewhere in the stage are named, as they are not attached. */
    private Path root(Path stage, String prefix) throws IOException {
        Path root = StageCollector.root(stage, prefix);
        List<String> stray = StageCollector.outsideRoot(stage, root);
        if (!stray.isEmpty()) {
            getLog().warn("installed outside the prefix " + root + ", so not attached: " + stray);
        }
        return root;
    }

    private void refused(NativeSession session) throws IOException, MojoFailureException {
        Optional<String> refusal = session.planner().installRefusal(session.context());
        if (refusal.isPresent() && session.context().options().outputs().isEmpty()) {
            throw new MojoFailureException("native install refused: " + refusal.get());
        }
        refusal.ifPresent(r -> getLog().warn("native install refused: " + r + "; staging the declared outputs"));
    }

    private void stageOutputs(NativeSession session, Layout layout) throws IOException {
        List<String> copied = StageCollector.copyOutputs(layout.basedir(), session.context().options().outputs(),
                layout.path(layout.stage()));
        getLog().info("no install step for " + session.context().system().id() + "; staged declared outputs " + copied);
    }

    private void report(StageCollector.Staged staged) {
        Path root = staged.root();
        info("STAGE " + root + ": " + staged.staticLibraries().size() + " static, "
                + staged.sharedLibraries().size() + " shared, " + staged.executables().size() + " executables, "
                + staged.headers().size() + " headers, " + staged.other().size() + " other");
        staged.all().forEach(file -> info("  " + file));
    }
}
