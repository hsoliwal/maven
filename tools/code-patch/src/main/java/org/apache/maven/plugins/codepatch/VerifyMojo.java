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
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Atomizes the staged after-images and the current content of the same files and writes COMPARE.tsv with the code
 * clustering plugin's states, plus a NO_ATOMS row (and a warning) for every changed file that gives no atoms. With
 * {@code failOn} empty it only reports. To require that a mechanical refactoring patch keeps logic:
 * {@code -Dpatch.failOn=CHANGED,LITERALS_CHANGED,ADDED,REMOVED}; add {@code NO_ATOMS} to fail on files it cannot
 * compare. Changes no source file.
 */
@Mojo(name = "verify", aggregator = true, requiresProject = false, threadSafe = true)
public final class VerifyMojo extends AbstractPatchMojo {
    private static final int SHOWN = 20;

    /** Comma-separated states that fail the build (code clustering states and NO_ATOMS); empty reports only. */
    @Parameter(property = "patch.failOn", defaultValue = "")
    private String failOn;

    @Override protected void run(Path base, Path out) throws IOException, PatchException, MojoFailureException {
        FailOn gate = FailOn.parse(failOn);
        CodePatch.Verification v = CodePatch.verify(base, out, patches(), gate);
        getLog().info("plan " + v.plan().id() + " " + v.summary() + "; see " + v.report());
        if (!v.noAtoms().isEmpty()) {
            getLog().warn(v.noAtoms().size() + " changed files give no atoms and were not compared ("
                    + FailOn.NO_ATOMS + "): " + files(v.noAtoms()));
        }
        if (!v.failing().isEmpty()) {
            v.failing().stream().limit(SHOWN).forEach(getLog()::error);
            throw new MojoFailureException(v.failing().size() + " rows in a failing state (" + failOn + "): "
                    + files(v.failing()) + "; see " + v.report());
        }
    }

    private static String files(List<String> rows) {
        String shown = rows.stream().limit(SHOWN).map(NoAtomRows::file).distinct().collect(Collectors.joining(", "));
        return rows.size() > SHOWN ? shown + ", ..." : shown;
    }
}
