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
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.nativebuild.engine.CompileCommandsPublisher;
import org.apache.maven.plugins.nativebuild.engine.CompileDbSource;
import org.apache.maven.plugins.nativebuild.engine.GeneratedFiles;
import org.apache.maven.plugins.nativebuild.engine.PlanContext;
import org.apache.maven.plugins.nativebuild.engine.WindowsPaths;

/**
 * Produces compile_commands.json (CMake, Meson, ninja -t compdb, Bear, or a make dry run) and publishes it for clangd
 * and other language servers: a copy at the project root by default, a {@code .clangd} pointer with
 * {@code publish=clangd-config}. A file the plugin did not write is kept; what it wrote is recorded in
 * {@code .m3/native-build}, which {@code mvn clean} keeps, so a later build still updates it and the clean goal
 * removes it.
 */
@Mojo(name = "compile-commands", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class CompileCommandsMojo extends AbstractNativeMojo {
    /** auto, cmake, meson, ninja, make-dry-run, bear or none. */
    @Parameter(property = "native.compileCommands", defaultValue = "auto")
    String compileCommands;

    /** copy, clangd-config or none. */
    @Parameter(property = "native.compileCommands.publish", defaultValue = "copy")
    String publish;

    /** Where to publish; default compile_commands.json (copy) or .clangd (clangd-config) in the project directory. */
    @Parameter(property = "native.compileCommands.path")
    File path;

    @Override
    protected void runNative() throws IOException, InterruptedException {
        NativeSession session = open(NativeSession.Use.RUN);
        if (!session.found()) {
            return;
        }
        PlanContext c = session.context();
        CompileDbSource.Mode mode = CompileDbSource.effective(CompileDbSource.Mode.parse(compileCommands), c.system(),
                c.tools().has("bear"));
        CompileDbSource.Result result = CompileDbSource.produce(c, session.planner(), mode, session.timeout());
        if (result.database().isEmpty()) {
            getLog().info("COMPILE-COMMANDS " + mode + ": " + result.note());
            return;
        }
        publish(c, mode, result);
    }

    private void publish(PlanContext c, CompileDbSource.Mode mode, CompileDbSource.Result result) throws IOException {
        CompileCommandsPublisher.Publish how = CompileCommandsPublisher.Publish.parse(publish);
        Path database = result.database().orElseThrow();
        Path basedir = c.layout().basedir();
        Path target = path != null ? path.toPath() : basedir.resolve(
                how == CompileCommandsPublisher.Publish.CLANGD_CONFIG ? ".clangd" : "compile_commands.json");
        Path ownership = basedir.resolve(GeneratedFiles.RECORDS).resolve(target.getFileName() + ".sha256");
        CompileCommandsPublisher.Outcome outcome = CompileCommandsPublisher.publish(how, database, target, ownership,
                WindowsPaths.relative(basedir, database.getParent()));
        getLog().info("COMPILE-COMMANDS " + mode + " " + database + " -> " + target + " " + outcome + " ("
                + result.note() + ")");
        if (outcome == CompileCommandsPublisher.Outcome.KEPT_FOREIGN) {
            getLog().warn(target + " was not written by this plugin and is kept; delete it to let the plugin publish");
        }
    }
}
