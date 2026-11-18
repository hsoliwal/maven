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
package org.apache.maven.plugins.nativebuild.engine;

import java.util.Arrays;

/**
 * A stand-in native tool that prints every argument it received as {@code ARG<value>} on its own line, so a contract
 * can compare the argument list a process really got. {@code --fail=N} as first argument prints a sentinel on both
 * streams and exits with N.
 */
public final class ArgvMain {
    /** Printed by a failing run, on standard output and standard error. */
    public static final String SENTINEL = "ARGV-MAIN-SENTINEL";

    private ArgvMain() {}

    public static void main(String[] args) {
        Arrays.stream(args).forEach(a -> System.out.println("ARG<" + a + ">"));
        Arrays.stream(args).filter(a -> a.startsWith("--env=")).map(a -> a.substring(6))
                .forEach(name -> System.out.println("ENV<" + name + "=" + System.getenv(name) + ">"));
        if (args.length > 0 && args[0].startsWith("--fail=")) {
            System.out.println(SENTINEL + " on stdout");
            System.err.println(SENTINEL + " on stderr");
            System.exit(Integer.parseInt(args[0].substring("--fail=".length())));
        }
    }
}
