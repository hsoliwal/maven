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
package org.apache.maven.recipes.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Context;

/**
 * The javac plugin {@code -Xplugin:MavenRecipes}: builds the OpenRewrite tree of every compiled source file from the
 * trees of the running compile, so no recipe runner parses the sources a second time.
 *
 * <p>Options, separated by spaces after the name ({@link PluginOptions}): {@code census=<file>} writes
 * {@link LstCensus#HEADER LST_CENSUS.tsv} when the compilation finishes; {@code root=<dir>} is the directory tree source
 * paths are made relative to (default: the working directory); {@code buildDir=<dir>} is the build directory, whose
 * sources get no tree (default: {@code <root>/target}).
 *
 * <p>The plugin changes no compiler option and no compiler field. javac itself turns on comment retention and end
 * positions as soon as a task listener is registered ({@code JavaCompiler.parse}, JDK 21). javac keeps its default
 * string folding; {@link FoldedStrings} gives each folded chain back its {@code +} nodes.
 *
 * <p>The plugin never fails a compile: a bad option, a census that cannot be written or any failure of its own is one
 * {@link Notices notice} line, and the compile goes on without trees or census.
 */
public final class MavenRecipesPlugin implements Plugin {
    /** The plugin name given to {@code -Xplugin:}. */
    public static final String NAME = "MavenRecipes";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        try {
            Context context = ((BasicJavacTask) task).getContext();
            LstCollector collector = new LstCollector(context, PluginOptions.parse(args));
            context.put(LstCollector.class, collector);
            task.addTaskListener(collector);
        } catch (IllegalArgumentException e) {
            Notices.print(task, "options not understood, no trees and no census for this compile: " + e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            Notices.print(task, "not started, no trees and no census for this compile: " + Notices.describe(e));
        }
    }
}
