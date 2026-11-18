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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Runs every contract of the native build engine; the exec-maven-plugin runs it in verify (argument: a scratch
 * directory, emptied first). Fails with an AssertionError when any check fails.
 */
public final class NativeBuildContracts {
    private NativeBuildContracts() {}

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args.length > 0 ? args[0] : "target/native-build-contracts").toAbsolutePath();
        empty(scratch);
        Checks checks = new Checks(System.out, scratch);
        runAll(checks);
        System.out.println("native-build contracts: " + checks.passed() + " passed, " + checks.failed() + " failed");
        if (checks.failed() > 0) {
            throw new AssertionError(checks.failed() + " native-build contract checks failed");
        }
    }

    private static void runAll(Checks checks) throws Exception {
        PlatformContract.run(checks);
        DetectorContract.run(checks);
        PlannerContract.run(checks);
        TaskFileContract.run(checks);
        IncrementalContract.run(checks);
        ReportsContract.run(checks);
        CompileDbContract.run(checks);
        ToolchainsContract.run(checks);
        StageContract.run(checks);
        ReviewRegressionContract.run(checks);
        ReviewFixContract.run(checks);
        Round3Contract.run(checks);
    }

    private static void empty(Path scratch) throws Exception {
        if (Files.exists(scratch)) {
            try (Stream<Path> walk = Files.walk(scratch)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(scratch);
    }
}
