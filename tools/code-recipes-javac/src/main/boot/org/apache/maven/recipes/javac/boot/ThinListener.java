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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/**
 * The only listener javac holds for the plugin. It forwards every event to the implementation's listener, which runs
 * in a class loader of its own over the {@code impl=} jars (or this class's loader when there are none), so neither
 * annotation processors nor the compile see the implementation's dependencies. Anything that escapes the
 * implementation drops it and closes that class loader, so its classes and statics become collectible, and javac
 * goes on as without the plugin.
 */
final class ThinListener implements TaskListener {
    private static final String IMPLEMENTATION = "org.apache.maven.recipes.javac.LstCollector";
    private static final String ENTRY = "listener";

    private TaskListener delegate;
    private URLClassLoader loader;

    private ThinListener() {}

    /** Starts the implementation and registers the listener; on any problem, one notice and nothing registered. */
    static void register(JavacTask task, BootOptions options) {
        ThinListener thin = new ThinListener();
        try {
            if (thin.start(task, options)) {
                task.addTaskListener(thin);
            }
        } catch (Throwable problem) {
            thin.close();
            MavenRecipesPlugin.notice("off: implementation not started: " + describe(problem));
        }
    }

    private boolean start(JavacTask task, BootOptions options) throws ReflectiveOperationException, IOException {
        Class<?> implementation = Class.forName(IMPLEMENTATION, true, loader(options.implementation()));
        Method entry = implementation.getMethod(ENTRY, JavacTask.class, BooleanSupplier.class, String[].class);
        Optional<?> listener =
                (Optional<?>) entry.invoke(null, task, new Headroom(options.headroomPercent()), options.rest());
        if (listener.isPresent()) {
            delegate = (TaskListener) listener.get();
            return true;
        }
        close();
        return false;
    }

    private ClassLoader loader(URL[] implementation) {
        if (implementation.length == 0) {
            return ThinListener.class.getClassLoader();
        }
        loader = new URLClassLoader(implementation, ThinListener.class.getClassLoader());
        return loader;
    }

    @Override
    public void started(TaskEvent event) {
        TaskListener listener = delegate;
        if (listener != null) {
            try {
                listener.started(event);
            } catch (Throwable problem) {
                drop(problem);
            }
        }
    }

    @Override
    public void finished(TaskEvent event) {
        TaskListener listener = delegate;
        if (listener != null) {
            try {
                listener.finished(event);
            } catch (Throwable problem) {
                drop(problem);
            }
        }
    }

    private void drop(Throwable problem) {
        delegate = null;
        close();
        MavenRecipesPlugin.notice("stopped for the rest of this compile: " + describe(problem));
    }

    private void close() {
        URLClassLoader own = loader;
        loader = null;
        try {
            if (own != null) {
                own.close();
            }
        } catch (IOException ignored) {
            // the jars stay open until the class loader is collected
        }
    }

    /** The one-line description of a problem, unwrapping reflection. */
    static String describe(Throwable problem) {
        Throwable cause = problem instanceof InvocationTargetException && problem.getCause() != null
                ? problem.getCause()
                : problem;
        return String.valueOf(cause).replaceAll("[\\t\\r\\n]+", " ");
    }

    /**
     * The heap gate, from {@link Runtime} only: true while the free part of the maximum heap, counting what the heap
     * holds now (garbage included), is at least the given percentage.
     */
    static final class Headroom implements BooleanSupplier {
        private static final int PERCENT = 100;
        private final int percent;

        Headroom(int percent) {
            this.percent = percent;
        }

        @Override
        public boolean getAsBoolean() {
            Runtime runtime = Runtime.getRuntime();
            long max = runtime.maxMemory();
            long used = runtime.totalMemory() - runtime.freeMemory();
            return max - used >= max / PERCENT * percent;
        }
    }
}
