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
package org.apache.maven.plugin.internal;

/*
 * Modified 2026-10-21 by Hitesh Soliwal and Contributors to the Synexia Project:
 * Add opt-in invocation-scoped shared-AST build-fact receipts without changing default compilation.
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * Opt-in M3JDK compiler integration for this Maven distribution.
 *
 * <p>The plugin manager calls this after merging and interpolating the execution configuration,
 * for both the Maven 3 and Maven 4 mojo APIs. Only the standard compiler plugin's Java compilation
 * goals are affected. The compiler plugin still owns its compiler, toolchain, processors and realms.
 */
final class M3AstCompilerConfiguration {
    private static final String ENABLED_PROPERTY = "m3.ast";
    private static final String PLUGIN_ARGUMENT = "-Xplugin:M3AST";
    private static final String BUILD_FACTS_PROPERTY = "m3.ast.buildFacts";

    private M3AstCompilerConfiguration() {}

    static PlexusConfiguration configure(
            MavenSession session, MojoDescriptor mojo, PlexusConfiguration configuration, ExpressionEvaluator evaluator)
            throws PluginConfigurationException {
        PluginDescriptor plugin = mojo.getPluginDescriptor();
        if (!"org.apache.maven.plugins".equals(plugin.getGroupId())
                || !"maven-compiler-plugin".equals(plugin.getArtifactId())
                || !("compile".equals(mojo.getGoal()) || "testCompile".equals(mojo.getGoal()))) {
            return configuration;
        }
        boolean buildFacts = Boolean.parseBoolean(property(session, BUILD_FACTS_PROPERTY));
        String astSetting = property(session, ENABLED_PROPERTY);
        if (buildFacts && astSetting != null && !Boolean.parseBoolean(astSetting)) {
            throw new PluginConfigurationException(
                    plugin, "m3.ast.buildFacts=true conflicts with an explicit m3.ast=false setting");
        }
        if (!buildFacts && !Boolean.parseBoolean(astSetting)) {
            return configuration;
        }

        PlexusConfiguration compilerId = configuration.getChild("compilerId", false);
        String effectiveCompilerId = compilerId == null ? null : effectiveValue(compilerId, evaluator, plugin);
        if (effectiveCompilerId != null && !"javac".equals(effectiveCompilerId.trim())) {
            throw new PluginConfigurationException(
                    plugin, "m3.ast requires the javac compiler; configured compilerId is " + effectiveCompilerId);
        }

        PlexusConfiguration arguments = configuration.getChild("compilerArgs", false);
        int existingIndex = -1;
        String existingArgument = null;
        if (arguments != null) {
            PlexusConfiguration[] children = arguments.getChildren();
            for (int index = 0; index < children.length; index++) {
                String value = effectiveValue(children[index], evaluator, plugin);
                if (selectsM3Ast(value)) {
                    if (!buildFacts) {
                        return configuration;
                    }
                    if (existingIndex >= 0) {
                        throw new PluginConfigurationException(
                                plugin, "m3.ast.buildFacts requires a single -Xplugin:M3AST argument");
                    }
                    for (String option : value.split("\\s+")) {
                        if (option.startsWith("facts64=") || option.startsWith("factsid=")) {
                            throw new PluginConfigurationException(
                                    plugin,
                                    "m3.ast.buildFacts owns facts64/factsid; remove explicit receipt arguments");
                        }
                    }
                    existingIndex = index;
                    existingArgument = value;
                }
            }
        }

        // XmlPlexusConfiguration may be backed by an immutable XmlNode. Build a private copy
        // rather than mutating an execution/project model or a transient child wrapper.
        PlexusConfiguration configured = copy(configuration);
        PlexusConfiguration configuredArguments = configured.getChild("compilerArgs", true);
        String argumentValue = existingArgument == null ? PLUGIN_ARGUMENT : existingArgument;
        if (buildFacts) {
            argumentValue += buildFactsArguments(session, mojo, plugin);
        }
        if (existingIndex >= 0) {
            configuredArguments.getChildren()[existingIndex].setValue(argumentValue);
        } else {
            PlexusConfiguration argument = new DefaultPlexusConfiguration("arg");
            argument.setValue(argumentValue);
            configuredArguments.addChild(argument);
        }
        return configured;
    }

    private static String effectiveValue(
            PlexusConfiguration configuration, ExpressionEvaluator evaluator, PluginDescriptor plugin)
            throws PluginConfigurationException {
        // Plugin descriptors retain expression/default-value pairs until the ComponentConfigurator
        // runs. Resolve only our decision with that same execution's evaluator; keep the original
        // values/attributes intact for the ordinary plugin configuration and type conversion.
        try {
            Object value = evaluator.evaluate(configuration.getValue());
            if (value == null) {
                value = evaluator.evaluate(configuration.getAttribute("default-value"));
            }
            return value == null ? null : value.toString();
        } catch (ExpressionEvaluationException failure) {
            throw new PluginConfigurationException(
                    plugin, "Unable to evaluate compiler configuration for m3.ast", (Throwable) failure);
        }
    }

    private static String property(MavenSession session, String name) {
        String value = session.getUserProperties().getProperty(name);
        if (value == null) {
            MavenProject project = session.getCurrentProject();
            if (project != null) {
                value = project.getProperties().getProperty(name);
            }
        }
        if (value == null) {
            value = session.getSystemProperties().getProperty(name);
        }
        return value;
    }

    /**
     * Ask the existing javac plugin to publish one PARTIAL fact receipt. The UUID
     * is caller-owned freshness evidence, not proof that the dependency graph is
     * complete. No directory or receipt is created while configuring a mojo.
     */
    private static String buildFactsArguments(MavenSession session, MojoDescriptor mojo, PluginDescriptor plugin)
            throws PluginConfigurationException {
        MavenProject project = session.getCurrentProject();
        if (project == null
                || project.getBuild() == null
                || project.getBuild().getDirectory() == null
                || project.getBuild().getDirectory().isBlank()) {
            throw new PluginConfigurationException(
                    plugin, "m3.ast.buildFacts requires a current project with a build directory");
        }
        try {
            Path directory = Path.of(project.getBuild().getDirectory());
            if (!directory.isAbsolute()) {
                if (project.getBasedir() == null) {
                    throw new PluginConfigurationException(
                            plugin, "m3.ast.buildFacts requires a project base directory for relative build output");
                }
                directory = project.getBasedir().toPath().resolve(directory);
            }
            String invocation = UUID.randomUUID().toString();
            Path output = directory
                    .toAbsolutePath()
                    .normalize()
                    .resolve("m3-ast-build-facts")
                    .resolve(mojo.getGoal() + "-" + invocation + ".tsv");
            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(output.toString().getBytes(StandardCharsets.UTF_8));
            return " facts64=" + encoded + " factsid=" + invocation;
        } catch (InvalidPathException failure) {
            throw new PluginConfigurationException(
                    plugin, "m3.ast.buildFacts requires a valid build output path", (Throwable) failure);
        }
    }

    private static boolean selectsM3Ast(String argument) {
        return argument != null
                && (argument.equals(PLUGIN_ARGUMENT)
                        || (argument.startsWith(PLUGIN_ARGUMENT)
                                && argument.length() > PLUGIN_ARGUMENT.length()
                                && Character.isWhitespace(argument.charAt(PLUGIN_ARGUMENT.length()))));
    }

    private static PlexusConfiguration copy(PlexusConfiguration original) {
        PlexusConfiguration result = new DefaultPlexusConfiguration(original.getName());
        result.setValue(original.getValue());
        for (String name : original.getAttributeNames()) {
            result.setAttribute(name, original.getAttribute(name));
        }
        for (PlexusConfiguration child : original.getChildren()) {
            result.addChild(copy(child));
        }
        return result;
    }
}
