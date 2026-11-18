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
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Applies a reviewed plan: recomputes it, refuses unless its id is {@code approvedPlan} (so a changed patch or a
 * target file changed since the review refuses), then writes backups and a receipt and promotes file by file. Stop
 * other editors and builds of the tree first.
 */
@Mojo(name = "apply", aggregator = true, requiresProject = false, threadSafe = true)
public final class ApplyMojo extends AbstractPatchMojo {
    /** The 64-hex-digit plan id that {@code plan} printed and a reviewer approved. */
    @Parameter(property = "patch.approvedPlan")
    private String approvedPlan;

    @Override protected void run(Path base, Path out) throws IOException, PatchException {
        if (approvedPlan == null || approvedPlan.isBlank()) {
            throw new PatchException("apply needs -Dpatch.approvedPlan=<plan id>; run code-patch:plan and review PLAN.tsv first");
        }
        Plan plan = CodePatch.apply(base, out, patches(), approvedPlan);
        logPlan(plan);
        getLog().info("applied; receipt in " + CodePatch.receipt(out, plan.id()) + "; undo with -Dpatch.receipt=" + plan.id());
    }
}
