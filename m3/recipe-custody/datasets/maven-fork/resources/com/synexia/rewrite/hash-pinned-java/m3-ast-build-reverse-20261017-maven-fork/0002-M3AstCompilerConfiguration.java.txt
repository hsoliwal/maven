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

    private M3AstCompilerConfiguration() {}

    static PlexusConfiguration configure(
            MavenSession session, MojoDescriptor mojo, PlexusConfiguration configuration, ExpressionEvaluator evaluator)
            throws PluginConfigurationException {
        PluginDescriptor plugin = mojo.getPluginDescriptor();
        if (!"org.apache.maven.plugins".equals(plugin.getGroupId())
                || !"maven-compiler-plugin".equals(plugin.getArtifactId())
                || !("compile".equals(mojo.getGoal()) || "testCompile".equals(mojo.getGoal()))
                || !isEnabled(session)) {
            return configuration;
        }

        PlexusConfiguration compilerId = configuration.getChild("compilerId", false);
        String effectiveCompilerId = compilerId == null ? null : effectiveValue(compilerId, evaluator, plugin);
        if (effectiveCompilerId != null && !"javac".equals(effectiveCompilerId.trim())) {
            throw new PluginConfigurationException(
                    plugin, "m3.ast requires the javac compiler; configured compilerId is " + effectiveCompilerId);
        }

        PlexusConfiguration arguments = configuration.getChild("compilerArgs", false);
        if (arguments != null) {
            for (PlexusConfiguration argument : arguments.getChildren()) {
                if (selectsM3Ast(effectiveValue(argument, evaluator, plugin))) {
                    return configuration;
                }
            }
        }

        // XmlPlexusConfiguration may be backed by an immutable XmlNode. Build a private copy
        // rather than mutating an execution/project model or a transient child wrapper.
        PlexusConfiguration configured = copy(configuration);
        PlexusConfiguration configuredArguments = configured.getChild("compilerArgs", true);
        PlexusConfiguration argument = new DefaultPlexusConfiguration("arg");
        argument.setValue(PLUGIN_ARGUMENT);
        configuredArguments.addChild(argument);
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

    private static boolean isEnabled(MavenSession session) {
        String value = session.getUserProperties().getProperty(ENABLED_PROPERTY);
        if (value == null) {
            MavenProject project = session.getCurrentProject();
            if (project != null) {
                value = project.getProperties().getProperty(ENABLED_PROPERTY);
            }
        }
        if (value == null) {
            value = session.getSystemProperties().getProperty(ENABLED_PROPERTY);
        }
        return Boolean.parseBoolean(value);
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
