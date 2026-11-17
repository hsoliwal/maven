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

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/** Marks {@code Commandline} constructors and {@code Commandline}/{@code CommandLineUtils} calls. */
final class CommandlineCalls extends JavaIsoVisitor<ExecutionContext> {

    static final String PLEXUS_PACKAGE = "org.codehaus.plexus.util.cli";
    static final String SHARED_UTILS_PACKAGE = "org.apache.maven.shared.utils.cli";

    static final String NEW_COMMANDLINE = "ProcessLaunchRequest.builder().shell(Shell.DEFAULT)";

    private static final Set<String> TYPES = Set.of(
            PLEXUS_PACKAGE + ".Commandline",
            PLEXUS_PACKAGE + ".CommandLineUtils",
            SHARED_UTILS_PACKAGE + ".Commandline",
            SHARED_UTILS_PACKAGE + ".CommandLineUtils");

    /** The replacement of each method, by method name. */
    private static final Map<String, String> REPLACEMENTS = Map.ofEntries(
            Map.entry("executeCommandLine", "ProcessLauncher.execute(ProcessLaunchRequest)"),
            Map.entry("executeCommandLineAsCallable", "ProcessLauncher.start(ProcessLaunchRequest)"),
            Map.entry("translateCommandline", "ProcessLauncher.tokenize(String)"),
            Map.entry("execute", "ProcessLauncher.start(ProcessLaunchRequest)"),
            Map.entry("setExecutable", "ProcessLaunchRequestBuilder.executable(String)"),
            Map.entry("addArguments", "ProcessLaunchRequestBuilder.arguments(String...)"),
            Map.entry("createArg", "ProcessLaunchRequestBuilder.argument(String) or maskedArgument(String)"),
            Map.entry("createArgument", "ProcessLaunchRequestBuilder.argument(String) or maskedArgument(String)"),
            Map.entry("addEnvironment", "ProcessLaunchRequestBuilder.environment(String, String)"),
            Map.entry("setShellEnvironmentInherited", "ProcessLaunchRequestBuilder.inheritEnvironment(boolean)"),
            Map.entry("setWorkingDirectory", "ProcessLaunchRequestBuilder.workingDirectory(Path)"),
            Map.entry("toString", "ProcessLauncher.describe(ProcessLaunchRequest)"));

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
        return replacement(visited.getMethodType())
                .map(text -> SearchResult.found(visited, text))
                .orElse(visited);
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
        J.NewClass visited = super.visitNewClass(newClass, ctx);
        boolean commandline = Optional.ofNullable(TypeUtils.asFullyQualified(visited.getType()))
                .map(JavaType.FullyQualified::getFullyQualifiedName)
                .filter(name -> name.endsWith(".Commandline") && TYPES.contains(name))
                .isPresent();
        return commandline ? SearchResult.found(visited, NEW_COMMANDLINE) : visited;
    }

    private static Optional<String> replacement(JavaType.Method method) {
        return Optional.ofNullable(method)
                .filter(m -> TYPES.contains(m.getDeclaringType().getFullyQualifiedName()))
                .map(m -> REPLACEMENTS.get(m.getName()));
    }
}
