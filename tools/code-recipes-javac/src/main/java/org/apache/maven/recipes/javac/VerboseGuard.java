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
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

/**
 * Where the plugin cannot keep {@code -verbose} output unchanged, it stays off. The command-line javac keeps doc
 * comments only when some task listener is registered (or a processor runs), and with doc comments kept it gives a
 * {@code package-info.java} that has only a doc comment an env of its own (Enter.java:329-333, JDK 21), which
 * {@code -verbose} reports as one more {@code [checking <anonymous >]} line. A javax.tools compile
 * ({@code JavacTaskImpl}) keeps doc comments anyway, so there the plugin changes nothing.
 */
final class VerboseGuard {
    static final String NOTICE = "off: -verbose in a command-line javac: javac keeps doc comments once a listener is"
            + " registered, which adds -verbose lines; no trees and no census for this compile";

    private VerboseGuard() {}

    /** True, after one notice, when the plugin must stay off for this task. */
    static boolean off(JavacTask task, Context context) {
        boolean off =
                !(task instanceof JavacTaskImpl) && Options.instance(context).isSet(Option.VERBOSE);
        if (off) {
            Notices.print(context, NOTICE);
        }
        return off;
    }
}
