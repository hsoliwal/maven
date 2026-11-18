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
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.nativebuild.engine.NativePhase;

/**
 * Configures the native build in generate-sources: writes the toolchain fingerprint, then runs autoreconf (only
 * when configure is missing), cmake, configure or meson setup. Make and Ninja projects have no configure step.
 */
@Mojo(name = "configure", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class ConfigureMojo extends AbstractNativeMojo {
    @Override
    protected void runNative() throws IOException, InterruptedException {
        NativeSession session = open(NativeSession.Use.CONFIGURE);
        if (session.found() && !session.run(NativePhase.BOOTSTRAP, NativePhase.CONFIGURE)) {
            getLog().info("no configure step for " + session.context().system().id());
        }
    }
}
