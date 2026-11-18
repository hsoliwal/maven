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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.nativebuild.engine.CapturedProcess;
import org.apache.maven.plugins.nativebuild.engine.CleanPlan;
import org.apache.maven.plugins.nativebuild.engine.FileTrees;
import org.apache.maven.plugins.nativebuild.engine.GeneratedFiles;
import org.apache.maven.plugins.nativebuild.engine.Layout;

/**
 * Removes in clean what the native build left outside {@code target}: runs {@code make clean} (when the makefile
 * declares it) or {@code ninja -t clean} for in-place Make and Ninja builds, deletes a CMake preset binaryDir outside
 * target, the files autoreconf created (only recorded paths inside the project, never through a link), the
 * compile_commands.json or .clangd the plugin published (only while unchanged), and the plugin's stamps and records in
 * {@code .m3}; links are deleted as links, never followed. make clean gets the make steps' PATH. Needs no file below
 * target.
 */
@Mojo(name = "clean", defaultPhase = LifecyclePhase.CLEAN, threadSafe = true)
public final class CleanMojo extends AbstractNativeMojo {
    @Override
    protected void runNative() throws IOException, InterruptedException {
        NativeSession session = open(NativeSession.Use.CLEAN);
        if (!session.found()) {
            return;
        }
        Layout layout = session.context().layout();
        warnRefused(layout);
        CleanPlan plan = CleanPlan.of(session.context(), session.planner());
        if (!plan.command().isEmpty()) {
            nativeClean(plan, layout);
        }
        for (String tree : plan.trees()) {
            FileTrees.delete(layout.path(tree));
            getLog().info("DELETED " + tree);
        }
        plan.files().forEach(file -> deleteIfEmptyOrFile(layout.path(file), file));
    }

    private void warnRefused(Layout layout) throws IOException {
        GeneratedFiles.refused(layout).forEach(entry -> getLog().warn("not deleting " + entry + " from "
                + GeneratedFiles.BOOTSTRAP + ": it leaves the project, lies in target or a VCS folder, or passes "
                + "a link"));
    }

    private void nativeClean(CleanPlan plan, Layout layout) throws IOException, InterruptedException {
        getLog().info("native clean: " + String.join(" ", plan.command()));
        CapturedProcess.Result result = CapturedProcess.run(plan.command(), plan.environment(),
                layout.path(plan.workingDir()), Duration.ofSeconds(timeoutSeconds));
        (result.out() + result.err()).lines().forEach(line -> getLog().info(line));
        if (result.exit() != 0) {
            getLog().warn("native clean exited " + result.exit() + "; continuing with the plugin's own files");
        }
    }

    private void deleteIfEmptyOrFile(Path path, String relative) {
        try {
            if (!nonEmptyDirectory(path)) {
                Files.deleteIfExists(path);
                getLog().info("DELETED " + relative);
            }
        } catch (IOException failure) {
            getLog().warn("cannot delete " + path + ": " + failure.getMessage());
        }
    }

    private static boolean nonEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isPresent();
        }
    }
}
