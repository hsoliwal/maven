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
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

/**
 * The plugin's warnings: one raw line on javac's notice writer, never a diagnostic, so javac's error and warning
 * counts, {@code -Werror} and a DiagnosticListener see nothing. The line starts with {@code [MavenRecipes]}, which
 * plexus-compiler's javac output parser files as an informational message, not as a warning or an error.
 */
final class Notices {
    /** The prefix of every notice line. */
    static final String PREFIX = "[" + MavenRecipesPlugin.NAME + "] ";

    private Notices() {}

    /** Prints a notice on the compile's log. */
    static void print(Context context, String message) {
        try {
            Log log = Log.instance(context);
            log.printRawLines(Log.WriterKind.NOTICE, PREFIX + message);
            log.flush(Log.WriterKind.NOTICE);
        } catch (RuntimeException | LinkageError e) {
            System.err.println(PREFIX + message);
        }
    }

    /** Prints a notice on the log of a task, or on standard error when its context cannot be reached. */
    static void print(JavacTask task, String message) {
        try {
            print(((BasicJavacTask) task).getContext(), message);
        } catch (RuntimeException | LinkageError e) {
            System.err.println(PREFIX + message);
        }
    }

    /** The one-line description of a problem, without line breaks or tabs. */
    static String describe(Throwable problem) {
        return problem.toString().replaceAll("[\\t\\r\\n]+", " ");
    }
}
