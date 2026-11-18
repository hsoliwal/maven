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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Applies every hunk in memory with exact context and writes PLAN.tsv and the plan id. Fails with the file and hunk
 * of the first hunk that does not apply. Changes no source file.
 */
@Mojo(name = "plan", aggregator = true, requiresProject = false, threadSafe = true)
public final class PlanMojo extends AbstractPatchMojo {
    @Override protected void run(Path base, Path out) throws IOException, PatchException {
        Plan plan = CodePatch.plan(base, out, patches());
        logPlan(plan);
        getLog().info("wrote " + out.resolve("PLAN.tsv") + "; after review, apply with -Dpatch.approvedPlan=" + plan.id());
    }
}
