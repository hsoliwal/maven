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
import com.sun.tools.javac.util.Options;

/**
 * The javac plugin {@code -Xplugin:MavenRecipes}: builds the OpenRewrite tree of every compiled source file from the
 * trees of the running compile, so no recipe runner parses the sources a second time.
 *
 * <p>Options, separated by spaces after the name: {@code census=<file>} writes {@link LstCensus#HEADER LST_CENSUS.tsv}
 * when the compilation finishes; {@code root=<dir>} is the directory tree source paths are made relative to (default:
 * the working directory). javac splits plugin options on spaces, so neither path may contain one.
 *
 * <p>The plugin changes one compiler option: {@code allowStringFolding=false}, without which javac's parser folds
 * {@code "a" + "b"} into one literal and the tree no longer matches the source text. Constant expressions are still
 * folded during attribution, so the generated class files stay the same (the tests compare them byte for byte). javac
 * itself turns on comment retention and end positions as soon as a task listener is registered
 * ({@code JavaCompiler.parse}, JDK 21), which is why the plugin needs no other compiler change.
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
        Context context = ((BasicJavacTask) task).getContext();
        PluginOptions options = PluginOptions.parse(args);
        Options.instance(context).put("allowStringFolding", "false");
        LstCollector collector = new LstCollector(context, options);
        context.put(LstCollector.class, collector);
        task.addTaskListener(collector);
    }
}
