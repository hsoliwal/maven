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

import java.util.List;
import java.util.Set;

import org.openrewrite.Cursor;
import org.openrewrite.xml.tree.Xml;

/**
 * Tells whether a POM tag sets a Java level: a {@code maven.compiler.release}, {@code source} or {@code target}
 * property (and their {@code test} forms) of the project or a profile, or the same parameter in the
 * {@code <configuration>} of {@code maven-compiler-plugin} or of one of its executions; and whether a tag is an
 * entry of that configuration's {@code <compilerArgs>} list.
 */
final class CompilerLevelTags {
    private static final Set<String> PARAMETERS =
            Set.of("release", "source", "target", "testRelease", "testSource", "testTarget");
    private static final String PROPERTY_PREFIX = "maven.compiler.";
    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    private static final String PLUGINS_GROUP = "org.apache.maven.plugins";

    private CompilerLevelTags() {}

    static boolean isLevelTag(Cursor cursor) {
        List<Xml.Tag> tags = tags(cursor);
        return isProperty(tags) || isPluginParameter(tags);
    }

    /** An entry of {@code <compilerArgs>} in a {@code maven-compiler-plugin} configuration. */
    static boolean isCompilerArg(Cursor cursor) {
        List<Xml.Tag> tags = tags(cursor);
        return tags.size() >= 2 && "compilerArgs".equals(tags.get(1).getName()) && isCompilerConfiguration(tags, 2);
    }

    private static List<Xml.Tag> tags(Cursor cursor) {
        return cursor.getPathAsStream()
                .filter(Xml.Tag.class::isInstance)
                .map(Xml.Tag.class::cast)
                .toList();
    }

    private static boolean isProperty(List<Xml.Tag> tags) {
        String name = tags.get(0).getName();
        return tags.size() >= 3
                && name.startsWith(PROPERTY_PREFIX)
                && PARAMETERS.contains(name.substring(PROPERTY_PREFIX.length()))
                && "properties".equals(tags.get(1).getName())
                && Set.of("project", "profile").contains(tags.get(2).getName());
    }

    private static boolean isPluginParameter(List<Xml.Tag> tags) {
        return PARAMETERS.contains(tags.get(0).getName()) && isCompilerConfiguration(tags, 1);
    }

    /** The tag at {@code index} is the {@code <configuration>} of the compiler plugin or of one of its executions. */
    private static boolean isCompilerConfiguration(List<Xml.Tag> tags, int index) {
        if (tags.size() < index + 2 || !"configuration".equals(tags.get(index).getName())) {
            return false;
        }
        boolean inExecution = tags.size() >= index + 4
                && "execution".equals(tags.get(index + 1).getName())
                && "executions".equals(tags.get(index + 2).getName());
        return isCompilerPlugin(tags.get(index + (inExecution ? 3 : 1)));
    }

    private static boolean isCompilerPlugin(Xml.Tag plugin) {
        return "plugin".equals(plugin.getName())
                && plugin.getChildValue("artifactId").map(String::trim).filter(COMPILER_PLUGIN::equals).isPresent()
                && plugin.getChildValue("groupId").map(String::trim).orElse(PLUGINS_GROUP).equals(PLUGINS_GROUP);
    }
}
