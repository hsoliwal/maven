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
package org.apache.maven.extensions.clustering;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * One maven-compiler-plugin execution's configuration before the mojo is configured: parameters read with their
 * expressions evaluated, and the two edits the wiring makes ({@code compilerArgs}, {@code annotationProcessorPaths}).
 */
final class CompilerConfig {
    private final Xpp3Dom config;
    private final PluginParameterExpressionEvaluator evaluator;

    CompilerConfig(MavenSession session, MojoExecution mojo) {
        this.config = mojo.getConfiguration();
        this.evaluator = new PluginParameterExpressionEvaluator(session, mojo);
    }

    boolean present() {
        return config != null;
    }

    boolean has(String name) {
        return config.getChild(name) != null;
    }

    /** A parameter's evaluated value: its text, else its default; null when neither gives one. */
    String value(String name) {
        Xpp3Dom child = config == null ? null : config.getChild(name);
        if (child == null) return null;
        String raw = child.getValue() != null ? child.getValue() : child.getAttribute("default-value");
        return evaluate(raw);
    }

    /** The configured compilerArgs, evaluated, plus the words of compilerArgument. */
    List<String> arguments() {
        List<String> args = new ArrayList<>();
        Xpp3Dom list = config.getChild("compilerArgs");
        if (list != null) Stream.of(list.getChildren()).map(c -> evaluate(c.getValue())).forEach(args::add);
        String single = value("compilerArgument");
        if (single != null) args.addAll(List.of(single.trim().split("\\s+")));
        args.removeIf(Objects::isNull);
        return args;
    }

    /** The evaluated entries of {@code compileSourceRoots}; empty when it is not configured. */
    List<String> compileSourceRoots() {
        Xpp3Dom roots = config.getChild("compileSourceRoots");
        return roots == null ? List.of() : Stream.of(roots.getChildren()).map(c -> evaluate(c.getValue()))
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    /** The configured {@code annotationProcessorPaths} with at least one path, else null. */
    Xpp3Dom processorPaths() {
        Xpp3Dom paths = config.getChild("annotationProcessorPaths");
        return paths != null && paths.getChildCount() > 0 ? paths : null;
    }

    /** Processors named by {@code annotationProcessors} or a {@code -processor} argument. */
    boolean namedProcessors() {
        Xpp3Dom named = config.getChild("annotationProcessors");
        boolean configured = named != null && (named.getChildCount() > 0 || evaluate(named.getValue()) != null);
        return configured || arguments().contains("-processor");
    }

    /** Whether the configuration decides about annotation processing itself: proc, -proc:, or named processors. */
    boolean processingConfigured() {
        return value("proc") != null || namedProcessors() || arguments().stream().anyMatch(a -> a.startsWith("-proc:"));
    }

    /**
     * A processor path given anywhere javac takes one: {@code compilerArgs}, {@code compilerArgument} or a key of the
     * {@code compilerArguments} map.
     */
    boolean processorPathSet() {
        Xpp3Dom map = config.getChild("compilerArguments");
        Stream<String> keys = map == null ? Stream.empty() : Stream.of(map.getChildren()).map(Xpp3Dom::getName);
        return Stream.concat(arguments().stream(), keys).anyMatch(CompilerConfig::processorPathOption);
    }

    /** {@code -processorpath}, {@code --processor-path[=...]}, {@code --processor-module-path}, any number of dashes. */
    static boolean processorPathOption(String option) {
        String name = option.replaceFirst("^-+", "").replaceFirst("=.*", "");
        return name.equals("processorpath") || name.equals("processor-path") || name.equals("processor-module-path");
    }

    /** Appends the arguments to {@code compilerArgs}, creating it when absent. */
    void addArguments(List<String> added) {
        Xpp3Dom args = config.getChild("compilerArgs");
        if (args == null) {
            args = new Xpp3Dom("compilerArgs");
            config.addChild(args);
        }
        Xpp3Dom list = args;
        added.forEach(a -> list.addChild(element("arg", a)));
    }

    /** Appends a {@code path} with these coordinates to {@code annotationProcessorPaths}. */
    void addProcessorPath(String groupId, String artifactId, String version) {
        Xpp3Dom path = new Xpp3Dom("path");
        path.addChild(element("groupId", groupId));
        path.addChild(element("artifactId", artifactId));
        path.addChild(element("version", version));
        processorPaths().addChild(path);
    }

    String evaluate(String raw) {
        try {
            Object value = raw == null ? null : evaluator.evaluate(raw);
            return value == null || value.toString().isBlank() ? null : value.toString();
        } catch (ExpressionEvaluationException e) {
            throw new IllegalStateException("cannot evaluate " + raw + ": " + e.getMessage(), e);
        }
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }
}
