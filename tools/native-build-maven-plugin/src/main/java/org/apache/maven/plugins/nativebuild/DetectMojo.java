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
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.MsvcLocator;
import org.apache.maven.plugins.nativebuild.engine.PlanContext;
import org.apache.maven.plugins.nativebuild.engine.StepPlanner;

/**
 * Prints what the other goals would do, and runs nothing: the detected build system, classifier, directories, each
 * resolved tool with its origin, and every planned step's Maven phase and argument list.
 */
@Mojo(name = "detect", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public final class DetectMojo extends AbstractNativeMojo {
    @Override
    protected void runNative() throws IOException {
        NativeSession session = open(false);
        if (session.found()) {
            printLayout(session);
            printTools(session.context());
            session.steps().forEach(s -> getLog().info("PLAN " + s.phase().mavenPhase() + " " + s.name() + " ["
                    + s.workingDir() + "] " + s.display()));
            printMsvc(session.context());
        }
    }

    private void printLayout(NativeSession session) {
        PlanContext c = session.context();
        Layout l = c.layout();
        getLog().info(session.detection().line());
        getLog().info("CLASSIFIER " + session.classifier() + " (platform " + c.platform().os() + "-"
                + c.platform().arch() + ")");
        getLog().info("LAYOUT source=" + l.source() + " build=" + l.build() + " stage=" + l.stage() + " reports="
                + l.reports());
    }

    private void printTools(PlanContext c) {
        c.tools().all().forEach(t -> getLog().info("TOOL " + t.name() + " " + t.origin() + " " + t.path()));
        StepPlanner.toolNames(c.system()).stream().filter(n -> !c.tools().has(n))
                .forEach(n -> getLog().info("TOOL " + n + " NOT FOUND"));
    }

    private void printMsvc(PlanContext c) {
        if (c.platform().windows()) {
            getLog().info("MSVC developer environment: " + MsvcLocator.developerEnvironment(c.environment())
                    + ", Visual Studio C++ tools: " + MsvcLocator.installation(c.platform(), c.environment()).orElse("none"));
        }
    }
}
