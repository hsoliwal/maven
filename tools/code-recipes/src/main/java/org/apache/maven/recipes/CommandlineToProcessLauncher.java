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
package org.apache.maven.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;

/**
 * Marks, in Maven 4 API plugins, the uses of the plexus-utils and maven-shared-utils {@code cli} packages with the
 * {@code ProcessLauncher} call that replaces each one.
 */
public final class CommandlineToProcessLauncher extends Recipe {

    @Override
    public String getDisplayName() {
        return "Move Commandline and CommandLineUtils to ProcessLauncher";
    }

    @Override
    public String getDescription() {
        return "In sources that use the Maven 4 API (`org.apache.maven.api`), marks each `Commandline` constructor "
                + "and each `Commandline` and `CommandLineUtils` call of plexus-utils (`org.codehaus.plexus.util.cli`) "
                + "and maven-shared-utils (`org.apache.maven.shared.utils.cli`) with the `ProcessLauncher` or "
                + "`ProcessLaunchRequest` builder call that replaces it. `Shell.DEFAULT` keeps the shell and quoting "
                + "of `Commandline`; an expired timeout becomes `ProcessLaunchResult.isTimedOut()` instead of a "
                + "`CommandLineTimeOutException`. Search only: a `Commandline` is built over several statements and its "
                + "checked exceptions differ, so the code is not rewritten. Maven 3 plugins are left alone.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.and(
                        new UsesType<>("org.apache.maven.api..*", false),
                        Preconditions.or(
                                new UsesType<>(CommandlineCalls.PLEXUS_PACKAGE + "..*", false),
                                new UsesType<>(CommandlineCalls.SHARED_UTILS_PACKAGE + "..*", false))),
                new CommandlineCalls());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CommandlineToProcessLauncher;
    }

    @Override
    public int hashCode() {
        return CommandlineToProcessLauncher.class.hashCode();
    }
}
