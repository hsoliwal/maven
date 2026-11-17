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
package org.apache.maven.recipes.javac.boot;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

/**
 * {@code -Xplugin:MavenRecipes}: the class javac loads. It is compiled for Java 8 (class file version 52), so every
 * javac can load it, and it decides before any class built for Java 21 is touched whether the plugin can run without
 * changing the compile. Unless the running javac is 21 and the maximum heap is at least the floor, it prints one
 * notice line and registers nothing. Otherwise it registers {@link ThinListener}, the only listener javac holds, which
 * runs the implementation from the {@code impl=} jars in a class loader of its own.
 *
 * <p>Its own options are {@code impl=} (the implementation's class path: {@code file:} URIs or paths separated by
 * {@code |}, or {@code @<file>} with one per line; absent, the implementation is loaded from this class's own class
 * loader), {@code minHeap=} (the floor in MB, default 256) and {@code headroom=} (the percentage of the maximum heap
 * that must be free before each file is converted, default 30). Every other option goes to the implementation.
 */
public final class MavenRecipesPlugin implements Plugin {
    /** The plugin name given to {@code -Xplugin:}. */
    public static final String NAME = "MavenRecipes";

    /** The only javac the implementation (rewrite-java-21) is proven on. */
    static final String SUPPORTED = "21";

    private static final int MB_SHIFT = 20;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        try {
            BootOptions options = BootOptions.parse(args);
            String off = offReason(options, System.getProperty("java.specification.version"));
            if (off.isEmpty()) {
                ThinListener.register(task, options);
            } else {
                notice("off: " + off);
            }
        } catch (Throwable problem) {
            notice("off: not started: " + ThinListener.describe(problem));
        }
    }

    /** Why the plugin cannot run in this javac, or the empty string when it can. */
    static String offReason(BootOptions options, String javaVersion) {
        if (!SUPPORTED.equals(javaVersion)) {
            return "javac " + javaVersion + " is not a supported javac (" + SUPPORTED + ")";
        }
        long maxMb = Runtime.getRuntime().maxMemory() >> MB_SHIFT;
        if (maxMb < options.minHeapMb()) {
            return "maximum heap " + maxMb + " MB is below " + options.minHeapMb() + " MB (minHeap=)";
        }
        return "";
    }

    /** One notice line on standard error, where javac's internals may not be reachable yet. */
    static void notice(String message) {
        System.err.println("[" + NAME + "] " + message);
        System.err.flush();
    }
}
