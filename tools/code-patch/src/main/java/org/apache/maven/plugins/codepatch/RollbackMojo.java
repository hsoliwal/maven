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
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Restores the files of an applied plan from its receipt, after checking that every file still has the content apply
 * gave it; otherwise refuses and names the files. Needs no patch files.
 */
@Mojo(name = "rollback", aggregator = true, requiresProject = false, threadSafe = true)
public final class RollbackMojo extends AbstractPatchMojo {
    /** The plan id of the receipt to roll back ({@code receipts/<id>/} in the output directory). */
    @Parameter(property = "patch.receipt")
    private String receipt;

    @Override protected void run(Path base, Path out) throws IOException, PatchException {
        if (receipt == null || receipt.isBlank()) throw new PatchException("rollback needs -Dpatch.receipt=<plan id>");
        List<PlanRow> rows = CodePatch.rollback(base, out, receipt);
        rows.forEach(r -> getLog().info("  " + undone(r)));
        getLog().info("rolled back plan " + receipt.strip() + ": " + rows.size() + " files");
    }

    private static String undone(PlanRow row) {
        String what = switch (row.operation()) {
            case ADD -> "removed " + row.path();
            case RENAME -> "restored " + row.fromPath() + ", removed " + row.path();
            default -> "restored " + row.path();
        };
        return what + " (" + row.operation() + " undone)";
    }
}
